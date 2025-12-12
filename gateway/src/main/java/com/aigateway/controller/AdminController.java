package com.aigateway.controller;

import com.aigateway.service.CacheService;
import com.aigateway.service.RateLimitService;
import com.aigateway.service.RoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final RoutingService routingService;
    private final CacheService cacheService;
    private final RateLimitService rateLimitService;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "healthy",
                "timestamp", Instant.now().toString(),
                "service", "ai-gateway"
        )));
    }

    /**
     * Detailed health with provider status
     */
    @GetMapping("/health/detailed")
    public Mono<ResponseEntity<Map<String, Object>>> detailedHealth() {
        return routingService.getProvidersStatus()
                .map(providers -> ResponseEntity.ok(Map.of(
                        "status", "healthy",
                        "timestamp", Instant.now().toString(),
                        "service", "ai-gateway",
                        "providers", providers
                )));
    }

    /**
     * List available models
     */
    @GetMapping("/v1/models")
    public Mono<ResponseEntity<Map<String, Object>>> listModels() {
        return routingService.getProvidersStatus()
                .map(providers -> {
                    var models = new java.util.ArrayList<Map<String, Object>>();
                    
                    // Add models based on available providers
                    if (providers.containsKey("openai") && providers.get("openai").configured()) {
                        models.add(Map.of("id", "gpt-4o", "provider", "openai"));
                        models.add(Map.of("id", "gpt-4o-mini", "provider", "openai"));
                        models.add(Map.of("id", "gpt-4-turbo", "provider", "openai"));
                    }
                    if (providers.containsKey("gemini") && providers.get("gemini").configured()) {
                        models.add(Map.of("id", "gemini-1.5-flash", "provider", "gemini"));
                        models.add(Map.of("id", "gemini-1.5-pro", "provider", "gemini"));
                    }
                    if (providers.containsKey("claude") && providers.get("claude").configured()) {
                        models.add(Map.of("id", "claude-3-5-sonnet-20241022", "provider", "claude"));
                        models.add(Map.of("id", "claude-3-5-haiku-20241022", "provider", "claude"));
                    }
                    if (providers.containsKey("local-worker") && providers.get("local-worker").configured()) {
                        models.add(Map.of("id", "local-llm", "provider", "local-worker"));
                    }
                    
                    return ResponseEntity.ok(Map.of(
                            "object", "list",
                            "data", models
                    ));
                });
    }

    /**
     * Clear cache (admin)
     */
    @DeleteMapping("/admin/cache")
    public Mono<ResponseEntity<Map<String, Object>>> clearCache(
            @RequestParam(defaultValue = "*") String pattern) {
        return cacheService.invalidateCache(pattern)
                .map(count -> ResponseEntity.ok(Map.of(
                        "status", "success",
                        "cleared", count
                )));
    }

    /**
     * Reset rate limit for user (admin)
     */
    @DeleteMapping("/admin/ratelimit/{identifier}")
    public ResponseEntity<Map<String, Object>> resetRateLimit(@PathVariable String identifier) {
        rateLimitService.resetLimit(identifier);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "identifier", identifier
        ));
    }

    /**
     * Get rate limit info
     */
    @GetMapping("/admin/ratelimit/{identifier}")
    public ResponseEntity<Map<String, Object>> getRateLimitInfo(@PathVariable String identifier) {
        RateLimitService.RateLimitInfo info = rateLimitService.getRateLimitInfo(identifier);
        return ResponseEntity.ok(Map.of(
                "identifier", identifier,
                "limit", info.limit(),
                "remaining", info.remaining(),
                "resetSeconds", info.resetSeconds()
        ));
    }
}
