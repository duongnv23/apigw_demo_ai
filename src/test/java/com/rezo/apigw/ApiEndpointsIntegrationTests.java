package com.rezo.apigw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.http.client.MultipartBodyBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Dedicated integration tests for specific APIs using exact request bodies from the Postman collection.
 * Each test logs in first to obtain a JWT, then posts the JSON with Authorization to the gateway
 * which forwards to the real upstream defined in application.properties.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Use the same upstream and explicit route mapping for the tests
                "spring.cloud.gateway.routes[0].id=fcbv-api",
                "spring.cloud.gateway.routes[0].uri=${upstream.base-url}",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/login,/otp,/change_pass,/rireq,/cireq,/prreq,/cureq,/ecreq"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ApiEndpointsIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(ApiEndpointsIntegrationTests.class);

    @LocalServerPort
    int port;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper objectMapper;

    WebTestClient client;
    String bearer;

    @BeforeEach
    void setup() {
        if (client == null) {
            client = WebTestClient
                    .bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
        }
        if (bearer == null) {
            bearer = loginAndGetBearer();
        }
    }

    @Test
    void cireq_should_forward_and_not_unauthorized() {
        String json = load("payloads/cireq.json");
        assertForwarded(postAuthorizedJson("/cireq", json), "/cireq");
    }

    @Test
    void prreq_should_forward_and_not_unauthorized() {
        String json = load("payloads/prreq.json");
        assertForwarded(postAuthorizedJson("/prreq", json), "/prreq");
    }

    @Test
    void cureq_should_forward_and_not_unauthorized() {
        String json = load("payloads/cureq.json");
        assertForwarded(postAuthorizedJson("/cureq", json), "/cureq");
    }

    @Test
    void ecreq_should_forward_and_not_unauthorized() {
        String json = load("payloads/ecreq.json");
        assertForwarded(postAuthorizedJson("/ecreq", json), "/ecreq");
    }

    @Test
    void rireq_person_should_forward_and_not_unauthorized() {
        String json = load("payloads/rireq.person.json");
        assertForwarded(postAuthorizedJson("/rireq", json), "/rireq");
    }

    private String loginAndGetBearer() {
        String user = firstNonBlank(env.getProperty("test.auth.user"), System.getenv("TEST_USER"));
        String pass = firstNonBlank(env.getProperty("test.auth.pass"), System.getenv("TEST_PASS"));
        if (isBlank(user) || isBlank(pass)) {
            fail("Missing credentials: provide -Dtest.auth.user/-Dtest.auth.pass or env TEST_USER/TEST_PASS");
        }

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("user", user);
        mb.part("pass", pass);

        EntityExchangeResult<byte[]> res = client.post()
                .uri("/login")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .exchange()
                .expectBody(byte[].class)
                .returnResult();

        int status = res.getStatus().value();
        String body = bytesToString(res.getResponseBody());
        log.info("[DEBUG_LOG] LOGIN POST /login => status={}, bodyPreview={}", status, preview(body));
        if (status < 200 || status >= 300) {
            fail("Login failed: status=" + status + ", body=\n" + body);
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            String token = null;
            if (json.hasNonNull("token")) token = json.get("token").asText();
            if (!StringUtils.hasText(token) && json.hasNonNull("access_token")) token = json.get("access_token").asText();
            if (!StringUtils.hasText(token)) fail("Login response missing token. Body=\n" + body);
            log.info("[DEBUG_LOG] Acquired JWT token ({} chars)", token.length());
            return "Bearer " + token;
        } catch (Exception e) {
            fail("Unable to parse login response as JSON. Body=\n" + body + "\nError=" + e.getMessage());
            return null;
        }
    }

    private EntityExchangeResult<byte[]> postAuthorizedJson(String path, String json) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer)
                .bodyValue(json)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
    }

    private void assertForwarded(EntityExchangeResult<byte[]> res, String path) {
        int status = res.getStatus().value();
        String body = bytesToString(res.getResponseBody());
        log.info("[DEBUG_LOG] POST {} => status={}, bodyPreview={}", path, status, preview(body));
        if (status == 401 || status == 403) {
            fail("Unauthorized/Forbidden for path " + path + " even after login. Status=" + status + ", body=\n" + body);
        }
        assertFalse(containsNoRouteFound(body), () -> "Gateway returned 'No route found' for path " + path + "; response body=\n" + body);
    }

    private boolean containsNoRouteFound(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("no route found");
    }

    private String bytesToString(byte[] bytes) {
        if (bytes == null) return "";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String preview(String body) {
        if (body == null) return "";
        final int max = 256;
        return body.length() <= max ? body : body.substring(0, max) + "…";
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (!isBlank(v)) return v;
        return null;
    }

    private String load(String resourcePath) {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                fail("Missing resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource " + resourcePath, e);
        }
    }
}
