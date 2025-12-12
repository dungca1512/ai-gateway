package com.aigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiProviderConfig {
    
    private Map<String, ProviderSettings> providers;
    private RoutingSettings routing;
    private RateLimitSettings rateLimit;
    private CacheSettings cache;
    
    @Data
    public static class ProviderSettings {
        private boolean enabled = true;
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private int timeoutSeconds = 30;
        private int priority = 10;
    }
    
    @Data
    public static class RoutingSettings {
        private String defaultProvider = "openai";
        private boolean fallbackEnabled = true;
        private int maxRetries = 2;
        private int retryDelayMs = 1000;
    }
    
    @Data
    public static class RateLimitSettings {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
        private int tokensPerMinute = 100000;
    }
    
    @Data
    public static class CacheSettings {
        private boolean enabled = true;
        private int ttlSeconds = 3600;
        private int maxSize = 10000;
    }
}
