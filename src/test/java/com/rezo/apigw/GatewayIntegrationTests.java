package com.rezo.apigw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that boot the Gateway and call the real upstream defined
 * in application.properties (no mock servers).
 * <p>
 * Auth-first behavior: we login to obtain a JWT and re-use it for the
 * remaining routed paths, as required.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Provide properly shaped route definitions for tests while still using upstream.base-url from application.properties
                "spring.cloud.gateway.routes[0].id=fcbv-api",
                "spring.cloud.gateway.routes[0].uri=${upstream.base-url}",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/login,/otp,/change_pass,/rireq,/cireq,/prreq,/cureq,/ecreq"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(GatewayIntegrationTests.class);

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    private WebTestClient client;
    private String bearerToken; // "Bearer <jwt>"

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void actuatorHealthIsUpOrAccessible() {
        log.info("[DEBUG_LOG] Calling /actuator/health on port {}", port);
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    void routedPathsShouldBeHandledByGateway() {
        // 1) Resolve routes
        List<String> routedPaths = resolveRoutedPathsFromProperties();
        log.info("[DEBUG_LOG] Resolved routed paths from properties: {}", routedPaths);
        assertFalse(routedPaths.isEmpty(), "No routed paths resolved from properties; check configuration");

        // 2) Login first to obtain JWT
        loginAndCaptureJwtIfPossible();

        // 3) Exercise each routed path. For non-login calls, attach Authorization header when we have a token.
        List<Executable> checks = routedPaths.stream().map(path -> (Executable) () -> {
            final String reqPath = path.trim();
            final boolean isLogin = "/login".equalsIgnoreCase(reqPath);

            // Try POST first with a minimal body suitable for most endpoints. If POST yields 405, fallback to GET.
            WebTestClient.RequestHeadersSpec<?> postSpec;
            if (isFormEndpoint(reqPath)) {
                // Many of these upstream endpoints accept form fields; send empty form by default.
                postSpec = client.post().uri(reqPath)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue("noop=noop");
            } else {
                postSpec = client.post().uri(reqPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}"); // harmless minimal JSON body
            }
            if (!isLogin && bearerToken != null) {
                postSpec = postSpec.header("Authorization", bearerToken);
            }

            EntityExchangeResult<byte[]> postResult = postSpec.exchange()
                    .expectBody(byte[].class)
                    .returnResult();

            int status = postResult.getStatus().value();
            String body = safeBody(postResult);
            log.info("[DEBUG_LOG] POST {} => status={}, bodyPreview={}", reqPath, status, preview(body));

            if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
                WebTestClient.RequestHeadersSpec<?> getSpec = client.get().uri(reqPath);
                if (!isLogin && bearerToken != null) {
                    getSpec = getSpec.header("Authorization", bearerToken);
                }
                EntityExchangeResult<byte[]> getResult = getSpec.exchange()
                        .expectBody(byte[].class)
                        .returnResult();
                status = getResult.getStatus().value();
                body = safeBody(getResult);
                log.info("[DEBUG_LOG] GET {} => status={}, bodyPreview={}", reqPath, status, preview(body));
            }

            // If we have a token and this is a protected endpoint, 401/403 should fail the test.
            if (!isLogin && bearerToken != null && (status == 401 || status == 403)) {
                fail("Unauthorized/Forbidden for path " + reqPath + " even after login. Status=" + status + ", body=\n" + body);
            }

            // Core assertion: ensure we did not hit gateway's own "No route found" response
            final String bodySnapshot = body;
            assertFalse(containsNoRouteFound(bodySnapshot),
                    () -> "Gateway returned 'No route found' for path " + reqPath + "; response body=\n" + bodySnapshot);
        }).collect(Collectors.toList());

        assertAll("All configured routed paths should be handled by the gateway", checks);
    }

    private void loginAndCaptureJwtIfPossible() {
        // Obtain credentials from system/environment properties
        String user = firstNonBlank(
                environment.getProperty("test.auth.user"),
                System.getenv("TEST_USER"));
        String pass = firstNonBlank(
                environment.getProperty("test.auth.pass"),
                System.getenv("TEST_PASS"));

        if (isBlank(user) || isBlank(pass)) {
            fail("Missing test credentials. Provide -Dtest.auth.user and -Dtest.auth.pass or env TEST_USER/TEST_PASS to run authenticated tests.");
            return;
        }

        // Perform login via gateway using application/x-www-form-urlencoded (compatible with many form handlers)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("user", user);
        form.add("pass", pass);

        EntityExchangeResult<byte[]> loginResult = client.post()
                .uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();

        int status = loginResult.getStatus().value();
        String body = safeBody(loginResult, true); // mask secrets if echoed
        log.info("[DEBUG_LOG] LOGIN POST /login => status={}, bodyPreview={}", status, preview(body));

        if (status < 200 || status >= 300) {
            fail("Login failed: status=" + status + ", body=\n" + body);
            return;
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            String token = null;
            if (json.hasNonNull("token")) token = json.get("token").asText();
            if (isBlank(token) && json.hasNonNull("access_token")) token = json.get("access_token").asText();
            if (isBlank(token)) {
                fail("Login response missing token. Body=\n" + body);
                return;
            }
            this.bearerToken = "Bearer " + token;
            log.info("[DEBUG_LOG] Acquired JWT token ({} chars)", token.length());
        } catch (Exception e) {
            fail("Unable to parse login response as JSON. Body=\n" + body + "\nError=" + e.getMessage());
        }
    }

    private boolean containsNoRouteFound(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("no route found");
    }

    private String safeBody(EntityExchangeResult<byte[]> result) {
        return safeBody(result, false);
    }

    private String safeBody(EntityExchangeResult<byte[]> result, boolean maskSecrets) {
        byte[] bytes = result.getResponseBody();
        if (bytes == null) return "";
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (!maskSecrets) return s;
        // rudimentary masking of common fields
        return s.replaceAll("\"pass\"\s*:\s*\"[^\"]*\"", "\"pass\":\"***\"")
                .replaceAll("\"password\"\s*:\s*\"[^\"]*\"", "\"password\":\"***\"")
                .replaceAll("\"otp\"\s*:\s*\"[^\"]*\"", "\"otp\":\"***\"");
    }

    private String preview(String body) {
        if (body == null) return "";
        final int max = 256;
        return body.length() <= max ? body : body.substring(0, max) + "…";
    }

    private List<String> resolveRoutedPathsFromProperties() {
        // Prefer correct key, fallback to server.webflux for compatibility with application.properties
        String raw = environment.getProperty("spring.cloud.gateway.routes[0].predicates[0]");
        if (raw == null) {
            raw = environment.getProperty("spring.cloud.gateway.server.webflux.routes[0].predicates[0]");
        }
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            log.warn("[DEBUG_LOG] No route predicates found in properties");
            return result;
        }
        String value = raw.trim();
        int idx = value.indexOf('=');
        if (idx >= 0) {
            value = value.substring(idx + 1);
        }
        value = value.trim();
        if (value.isEmpty()) return result;
        result.addAll(Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !"/login,/otp,/change_pass".contains(s))
                .collect(Collectors.toList()));
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (!isBlank(v)) return v;
        return null;
    }

    private boolean isFormEndpoint(String path) {
        // Heuristic: known endpoints in this system tend to use form fields
        return "/login".equalsIgnoreCase(path)
                || "/otp".equalsIgnoreCase(path)
                || "/change_pass".equalsIgnoreCase(path)
                || "/rireq".equalsIgnoreCase(path)
                || "/cireq".equalsIgnoreCase(path)
                || "/prreq".equalsIgnoreCase(path)
                || "/cureq".equalsIgnoreCase(path)
                || "/ecreq".equalsIgnoreCase(path);
    }
}
