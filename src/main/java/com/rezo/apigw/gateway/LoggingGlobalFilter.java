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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;

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
        // Extract username for logging (from Basic or Bearer JWT)
        String username = extractUsername(request);
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder -> builder.header("X-Correlation-Id", correlationId))
                .build();
        // store username in exchange attributes for later logs
        mutatedExchange.getAttributes().put("log.username", username);

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

        // Resolve username saved earlier
        String username = safeUsername(exchange.getAttribute("log.username"));
        // Log headers
        if (props.isLogHeaders()) {
            String maskedHeaders = maskHeaders(headers);
            accessLog.info("[{}][user={}] -> {} {}{} Headers: {}", correlationId, username, method, uri.getPath(),
                    uri.getQuery() != null ? ("?" + uri.getQuery()) : "", toSingleLine(maskedHeaders));
        } else {
            accessLog.info("[{}][user={}] -> {} {}{}", correlationId, username, method, uri.getPath(),
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
                    String uname = safeUsername(exchange.getAttribute("log.username"));
                    accessLog.info("[{}][user={}] -> BODY: {}", correlationId, uname, toSingleLine(maskedBody));
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
                                String uname = safeUsername(exchange.getAttribute("log.username"));
                                if (props.isLogHeaders()) {
                                    accessLog.info("[{}][user={}] <- {} {} ms Headers: {} BODY: {}", correlationId, uname,
                                            status != null ? status.value() : 0,
                                            latency.toMillis(), toSingleLine(maskHeaders(getHeaders())), toSingleLine(masked));
                                } else {
                                    accessLog.info("[{}][user={}] <- {} {} ms BODY: {}", correlationId, uname,
                                            status != null ? status.value() : 0, latency.toMillis(), toSingleLine(masked));
                                }
                                return bufferFactory().wrap(bytes);
                            })
                    );
                }
                // Fallback: no body or not loggable content type
                Duration latency = Duration.between(start, Instant.now());
                HttpStatusCode status = getStatusCode();
                String uname = safeUsername(exchange.getAttribute("log.username"));
                if (props.isLogHeaders()) {
                    accessLog.info("[{}][user={}] <- {} {} ms Headers: {}", correlationId, uname,
                            status != null ? status.value() : 0,
                            latency.toMillis(), toSingleLine(maskHeaders(getHeaders())));
                } else {
                    accessLog.info("[{}][user={}] <- {} {} ms", correlationId, uname,
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
            return maskFormFields(body, props.getMaskedFormFields());
        }
        // multipart/form-data masking (text fields only)
        if (contentType != null && MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            return maskMultipartForm(body, contentType, props.getMaskedFormFields());
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

    // Basic, tolerant masking for multipart/form-data bodies. We do not parse fully,
    // just split by boundary and replace part bodies whose name matches a configured field.
    private String maskMultipartForm(String raw, MediaType contentType, List<String> fields) {
        if (raw == null || raw.isEmpty()) return raw;
        String boundary = null;
        try {
            Map<String, String> params = contentType.getParameters();
            if (params != null) {
                boundary = params.get("boundary");
            }
        } catch (Exception ignored) {}
        if (boundary == null || boundary.isEmpty()) {
            // No boundary info; avoid risky logging changes
            return raw;
        }
        String delimiter = "--" + boundary;
        String closeDelimiter = delimiter + "--";
        // Normalize to \n processing but preserve original lines when reconstructing
        String[] parts = raw.split("(?s)" + Pattern.quote(delimiter));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                // the text before the first delimiter
                out.append(part);
                continue;
            }
            // Append delimiter back (except possibly for the very beginning)
            out.append(delimiter);
            // If this is the closing delimiter part, keep as is
            if (part.startsWith("--")) {
                out.append(part);
                continue;
            }
            // Try to locate header/body separator (\r\n\r\n or \n\n)
            int idx = indexOfDoubleNewline(part);
            if (idx < 0) {
                out.append(part); // unknown structure
                continue;
            }
            String headers = part.substring(0, idx);
            String body = part.substring(idx);
            String name = extractMultipartName(headers);
            if (name != null && matchesAnyIgnoreCase(name, fields)) {
                // Replace body content, but preserve leading CRLFs
                String leading = leadingNewlines(body);
                out.append(headers).append(leading).append("****");
                // If original had trailing CRLF before next boundary, try to preserve by ensuring newline end
                if (!body.endsWith("\r\n") && !body.endsWith("\n")) {
                    // leave as is
                }
            } else {
                out.append(headers).append(body);
            }
        }
        // Ensure closing delimiter exists if it was present originally (kept by split rebuild)
        String result = out.toString();
        // Append close delimiter if raw ended with it but reconstruction missed it (unlikely)
        if (raw.contains(closeDelimiter) && !result.contains(closeDelimiter)) {
            if (!result.endsWith("\r\n") && !result.endsWith("\n")) result += "\r\n";
            result += closeDelimiter;
        }
        return result;
    }

    private int indexOfDoubleNewline(String s) {
        int idx = s.indexOf("\r\n\r\n");
        if (idx >= 0) return idx + 4; // position after separator
        idx = s.indexOf("\n\n");
        if (idx >= 0) return idx + 2;
        return -1;
    }

    private String leadingNewlines(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') i++; else break;
        }
        return s.substring(0, i);
    }

    private String extractMultipartName(String headersSection) {
        // Look for Content-Disposition: form-data; name="field"; filename="..."
        String[] lines = headersSection.split("\r?\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-disposition:")) {
                Matcher m = Pattern.compile(";\\s*name=\"(.*?)\"").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private boolean matchesAnyIgnoreCase(String val, List<String> fields) {
        for (String f : fields) {
            if (f != null && val != null && f.equalsIgnoreCase(val)) return true;
        }
        return false;
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

    // === Username extraction helpers ===
    private String safeUsername(Object val) {
        String s = (val == null) ? null : String.valueOf(val);
        if (s == null || s.isBlank()) return "-";
        return toSingleLine(s);
    }

    private String extractUsername(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        String path = request.getURI() != null ? request.getURI().getPath() : "";

        // 1) If login request and Basic auth available -> decode username from Basic
        if (path != null && path.toLowerCase(Locale.ROOT).contains("/login") && auth != null && auth.toLowerCase(Locale.ROOT).startsWith("basic ")) {
            String fromBasic = extractFromBasic(auth);
            if (fromBasic != null) return fromBasic;
        }

        // 2) Try explicit username headers often used by upstreams/proxies
        String explicit = firstNonBlank(
                headers.getFirst("X-Username"),
                headers.getFirst("X-User"),
                headers.getFirst("X-Auth-User"),
                headers.getFirst("Username"),
                headers.getFirst("User")
        );
        if (explicit != null && !explicit.isBlank()) return explicit;

        // 3) Bearer JWT -> parse payload and read preferred claim keys
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            String fromJwt = extractFromBearer(auth);
            if (fromJwt != null) return fromJwt;
        }

        // 4) Nothing found
        return "";
    }

    private String extractFromBasic(String authorization) {
        try {
            String token = authorization.substring(6).trim(); // after 'Basic '
            byte[] decoded = Base64.getDecoder().decode(token);
            String pair = new String(decoded, StandardCharsets.UTF_8);
            int idx = pair.indexOf(':');
            return idx >= 0 ? pair.substring(0, idx) : pair;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFromBearer(String authorization) {
        try {
            String jwt = authorization.substring(7).trim(); // after 'Bearer '
            return parseJwtUsername(jwt);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseJwtUsername(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payloadB64 = parts[1];
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
            String json = new String(payloadBytes, StandardCharsets.UTF_8);
            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(json);
            for (String key : props.getUsernameClaimKeys()) {
                if (node.hasNonNull(key)) {
                    return node.get(key).asText();
                }
            }
            // common fallbacks
            if (node.hasNonNull("preferred_username")) return node.get("preferred_username").asText();
            if (node.hasNonNull("name")) return node.get("name").asText();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
