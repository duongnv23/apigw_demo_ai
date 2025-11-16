package com.rezo.apigw.gateway;

import com.rezo.apigw.config.GatewayLoggingProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger accessLog = LoggerFactory.getLogger(LoggingGlobalFilter.class);

    private final GatewayLoggingProperties props;

    @Override
    public int getOrder() {
        // Ensure we run early to wrap request/response
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }

        Instant start = Instant.now();
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = getOrCreateCorrelationId(request.getHeaders());
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder -> builder.header("X-Correlation-Id", correlationId))
                .build();

        // Capture and possibly log request headers and body
        return decorateRequest(mutatedExchange, correlationId)
                .flatMap(decoratedExchange -> decorateResponse(decoratedExchange, start, correlationId))
                .flatMap(chain::filter)
                .then(Mono.fromRunnable(() -> {
                    // ensure end log when response done if not logged (handled in response decorator)
                }));
    }

    private Mono<ServerWebExchange> decorateRequest(ServerWebExchange exchange, String correlationId) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        URI uri = request.getURI();

        // Log headers
        if (props.isLogHeaders()) {
            String maskedHeaders = maskHeaders(headers);
            accessLog.info("[{}] -> {} {}{} Headers: {}", correlationId, method, uri.getPath(),
                    uri.getQuery() != null ? ("?" + uri.getQuery()) : "", toSingleLine(maskedHeaders));
        } else {
            accessLog.info("[{}] -> {} {}{}", correlationId, method, uri.getPath(),
                    uri.getQuery() != null ? ("?" + uri.getQuery()) : "");
        }

        if (!props.isLogRequestBody()) {
            return Mono.just(exchange);
        }

        boolean loggableContentType = isLoggableContentType(headers.getContentType());
        if (!loggableContentType) {
            return Mono.just(exchange);
        }

        // Buffer the request body for logging and forwarding
        AtomicReference<byte[]> cachedBodyRef = new AtomicReference<>(new byte[0]);

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    int max = Math.min(bytes.length, props.getMaxBodySize());
                    byte[] limited = Arrays.copyOf(bytes, max);
                    cachedBodyRef.set(limited);
                    String bodyStr = new String(limited, StandardCharsets.UTF_8);
                    String maskedBody = maybeMaskBody(headers.getContentType(), bodyStr);
                    accessLog.info("[{}] -> BODY: {}", correlationId, toSingleLine(maskedBody));
                    return limited;
                })
                .map(bytes -> new ServerHttpRequestDecorator(request) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
                        return Flux.defer(() -> {
                            DataBuffer buffer = bufferFactory.wrap(cachedBodyRef.get());
                            return Flux.just(buffer);
                        });
                    }
                })
                .map(decoratedRequest -> exchange.mutate().request(decoratedRequest).build());
    }

    private Mono<ServerWebExchange> decorateResponse(ServerWebExchange exchange, Instant start, String correlationId) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                MediaType contentType = getHeaders().getContentType();
                boolean logBody = props.isLogResponseBody() && isLoggableContentType(contentType);
                if (logBody) {
                    Flux<? extends DataBuffer> flux = Flux.from(body);
                    return super.writeWith(
                            flux.map(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                String bodyString = new String(bytes, StandardCharsets.UTF_8);
                                String masked = maybeMaskBody(contentType, bodyString);
                                Duration latency = Duration.between(start, Instant.now());
                                HttpStatusCode status = getStatusCode();
                                if (props.isLogHeaders()) {
                                    accessLog.info("[{}] <- {} {} ms Headers: {} BODY: {}", correlationId,
                                            status != null ? status.value() : 0,
                                            latency.toMillis(), toSingleLine(maskHeaders(getHeaders())), toSingleLine(masked));
                                } else {
                                    accessLog.info("[{}] <- {} {} ms BODY: {}", correlationId,
                                            status != null ? status.value() : 0, latency.toMillis(), toSingleLine(masked));
                                }
                                return bufferFactory().wrap(bytes);
                            })
                    );
                }
                // Fallback: no body or not loggable content type
                Duration latency = Duration.between(start, Instant.now());
                HttpStatusCode status = getStatusCode();
                if (props.isLogHeaders()) {
                    accessLog.info("[{}] <- {} {} ms Headers: {}", correlationId,
                            status != null ? status.value() : 0,
                            latency.toMillis(), toSingleLine(maskHeaders(getHeaders())));
                } else {
                    accessLog.info("[{}] <- {} {} ms", correlationId,
                            status != null ? status.value() : 0, latency.toMillis());
                }
                return super.writeWith(body);
            }
        };
        return Mono.just(exchange.mutate().response(decorated).build());
    }

    private boolean isLoggableContentType(MediaType mediaType) {
        if (mediaType == null) return true; // treat unknown as loggable (e.g., most JSON defaults)
        String mt = mediaType.toString().toLowerCase(Locale.ROOT);
        return props.getContentTypeIncludes().stream().anyMatch(mt::contains);
    }

    private String maskHeaders(HttpHeaders headers) {
        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            String keyLower = k.toLowerCase(Locale.ROOT);
            if (props.getMaskedHeaders().contains(keyLower)) {
                masked.put(k, Collections.singletonList("****"));
            } else {
                masked.put(k, v);
            }
        });
        return masked.toString();
    }

    private String maybeMaskBody(MediaType contentType, String body) {
        if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return maskJsonFields(body, props.getMaskedJsonFields());
        }
        // basic masking for form fields in x-www-form-urlencoded
        if (contentType != null && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)) {
            return maskFormFields(body, props.getMaskedJsonFields());
        }
        return body;
    }

    private String maskJsonFields(String json, List<String> fields) {
        String masked = json;
        for (String field : fields) {
            // regex to replace "field":"value" or 'field':'value'
            String pattern = "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"(.*?)\\\"";
            masked = masked.replaceAll(pattern, "\"" + field + "\":\"****\"");
        }
        return masked;
    }

    private String maskFormFields(String form, List<String> fields) {
        String masked = form;
        for (String field : fields) {
            String pattern = "(?i)(^|&)(" + Pattern.quote(field) + ")=([^&]*)";
            masked = masked.replaceAll(pattern, "$1$2=****");
        }
        return masked;
    }

    private String getOrCreateCorrelationId(HttpHeaders headers) {
        String id = headers.getFirst("X-Correlation-Id");
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    // Normalize any text to a single line for access.log: replace newlines/tabs/control chars with space,
    // collapse multiple spaces, and trim
    private String toSingleLine(String s) {
        if (s == null) return "";
        String normalized = s
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
        // Remove other control characters, collapse spaces and trim
        normalized = normalized.replaceAll("\\p{Cntrl}", " ");
        normalized = normalized.replaceAll(" +", " ");
        return normalized.trim();
    }
}
