package com.rezo.apigw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.logging")
public class GatewayLoggingProperties {
    /** Enable/disable logging filter */
    private boolean enabled = true;
    /** Log request headers */
    private boolean logHeaders = true;
    /** Log request body */
    private boolean logRequestBody = true;
    /** Log response body */
    private boolean logResponseBody = true;
    /** Maximum bytes to buffer and log for bodies */
    private int maxBodySize = 1024 * 1024; // 1 MB
    /** Header names to mask */
    private List<String> maskedHeaders = List.of("authorization", "cookie", "set-cookie");
    /** JSON field names to mask in bodies */
    private List<String> maskedJsonFields = List.of("pass", "old_pass", "new_pass", "otp", "password", "token");
    /** Only log body if Content-Type matches these substrings */
    private List<String> contentTypeIncludes = List.of("application/json", "text/plain", "application/x-www-form-urlencoded", "multipart/form-data");
    /** Preferred JWT claim keys to read username from */
    private List<String> usernameClaimKeys = List.of("username", "sub", "user_name");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isLogHeaders() { return logHeaders; }
    public void setLogHeaders(boolean logHeaders) { this.logHeaders = logHeaders; }
    public boolean isLogRequestBody() { return logRequestBody; }
    public void setLogRequestBody(boolean logRequestBody) { this.logRequestBody = logRequestBody; }
    public boolean isLogResponseBody() { return logResponseBody; }
    public void setLogResponseBody(boolean logResponseBody) { this.logResponseBody = logResponseBody; }
    public int getMaxBodySize() { return maxBodySize; }
    public void setMaxBodySize(int maxBodySize) { this.maxBodySize = maxBodySize; }
    public List<String> getMaskedHeaders() { return maskedHeaders; }
    public void setMaskedHeaders(List<String> maskedHeaders) { this.maskedHeaders = maskedHeaders; }
    public List<String> getMaskedJsonFields() { return maskedJsonFields; }
    public void setMaskedJsonFields(List<String> maskedJsonFields) { this.maskedJsonFields = maskedJsonFields; }
    public List<String> getContentTypeIncludes() { return contentTypeIncludes; }
    public void setContentTypeIncludes(List<String> contentTypeIncludes) { this.contentTypeIncludes = contentTypeIncludes; }
    public List<String> getUsernameClaimKeys() { return usernameClaimKeys; }
    public void setUsernameClaimKeys(List<String> usernameClaimKeys) { this.usernameClaimKeys = usernameClaimKeys; }
}
