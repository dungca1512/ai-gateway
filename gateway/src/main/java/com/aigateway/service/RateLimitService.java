package com.aigateway.service;

import com.aigateway.config.AiProviderConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final AiProviderConfig config;
    private final MeterRegistry meterRegistry;
    
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    // Default bucket for unauthenticated requests
    private Bucket defaultBucket;

    @PostConstruct
    public void init() {
        if (config.getRateLimit().isEnabled()) {
            defaultBucket = createBucket(config.getRateLimit().getRequestsPerMinute());
            log.info("Rate limiting enabled: {} requests/minute", config.getRateLimit().getRequestsPerMinute());
        }
    }

    /**
     * Check if request is allowed for given user/API key
     */
    public boolean tryConsume(String identifier) {
        if (!config.getRateLimit().isEnabled()) {
            return true;
        }

        Bucket bucket = buckets.computeIfAbsent(
                identifier != null ? identifier : "default",
                k -> createBucket(config.getRateLimit().getRequestsPerMinute())
        );

        boolean allowed = bucket.tryConsume(1);
        
        if (!allowed) {
            meterRegistry.counter("ai.ratelimit", "status", "exceeded", "identifier", identifier != null ? identifier : "default").increment();
            log.warn("Rate limit exceeded for: {}", identifier);
        } else {
            meterRegistry.counter("ai.ratelimit", "status", "allowed", "identifier", identifier != null ? identifier : "default").increment();
        }

        return allowed;
    }

    /**
     * Check remaining tokens for user
     */
    public long getRemainingTokens(String identifier) {
        if (!config.getRateLimit().isEnabled()) {
            return Long.MAX_VALUE;
        }

        Bucket bucket = buckets.getOrDefault(
                identifier != null ? identifier : "default",
                defaultBucket
        );

        return bucket.getAvailableTokens();
    }

    /**
     * Get rate limit info for response headers
     */
    public RateLimitInfo getRateLimitInfo(String identifier) {
        if (!config.getRateLimit().isEnabled()) {
            return new RateLimitInfo(Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        }

        Bucket bucket = buckets.getOrDefault(
                identifier != null ? identifier : "default",
                defaultBucket
        );

        return new RateLimitInfo(
                config.getRateLimit().getRequestsPerMinute(),
                (int) bucket.getAvailableTokens(),
                60 // Reset in seconds
        );
    }

    /**
     * Reset rate limit for a specific identifier (admin function)
     */
    public void resetLimit(String identifier) {
        buckets.remove(identifier);
        log.info("Rate limit reset for: {}", identifier);
    }

    private Bucket createBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1))
        );
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    public record RateLimitInfo(int limit, int remaining, int resetSeconds) {}
}
