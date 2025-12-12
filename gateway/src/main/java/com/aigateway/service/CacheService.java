package com.aigateway.service;

import com.aigateway.config.AiProviderConfig;
import com.aigateway.model.ChatModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private static final String CACHE_PREFIX = "ai:cache:";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProviderConfig config;
    private final MeterRegistry meterRegistry;

    /**
     * Try to get cached response for chat request
     */
    public Mono<ChatModels.ChatResponse> getCachedResponse(ChatModels.ChatRequest request) {
        if (!config.getCache().isEnabled()) {
            return Mono.empty();
        }

        String cacheKey = generateCacheKey(request);
        
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(cached -> {
                    try {
                        ChatModels.ChatResponse response = objectMapper.readValue(cached, ChatModels.ChatResponse.class);
                        // Mark as cached
                        if (response.getGateway() != null) {
                            response.getGateway().setCached(true);
                        } else {
                            response.setGateway(ChatModels.GatewayMetadata.builder()
                                    .cached(true)
                                    .build());
                        }
                        
                        meterRegistry.counter("ai.cache", "status", "hit").increment();
                        log.debug("Cache hit for key: {}", cacheKey);
                        
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize cached response", e);
                        return Mono.empty();
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    meterRegistry.counter("ai.cache", "status", "miss").increment();
                    return Mono.empty();
                }));
    }

    /**
     * Cache a chat response
     */
    public Mono<Void> cacheResponse(ChatModels.ChatRequest request, ChatModels.ChatResponse response) {
        if (!config.getCache().isEnabled()) {
            return Mono.empty();
        }

        // Don't cache streaming responses or error responses
        if (Boolean.TRUE.equals(request.getStream()) || response.getChoices() == null || response.getChoices().isEmpty()) {
            return Mono.empty();
        }

        String cacheKey = generateCacheKey(request);
        
        try {
            String serialized = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofSeconds(config.getCache().getTtlSeconds());
            
            return redisTemplate.opsForValue().set(cacheKey, serialized, ttl)
                    .doOnSuccess(success -> log.debug("Cached response for key: {}", cacheKey))
                    .doOnError(e -> log.error("Failed to cache response", e))
                    .then();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for caching", e);
            return Mono.empty();
        }
    }

    /**
     * Invalidate cache for a specific pattern
     */
    public Mono<Long> invalidateCache(String pattern) {
        return redisTemplate.keys(CACHE_PREFIX + pattern)
                .flatMap(redisTemplate::delete)
                .reduce(0L, Long::sum);
    }

    /**
     * Generate deterministic cache key from request
     */
    private String generateCacheKey(ChatModels.ChatRequest request) {
        try {
            // Create a canonical representation for hashing
            StringBuilder sb = new StringBuilder();
            sb.append(request.getModel() != null ? request.getModel() : "default");
            sb.append("|");
            sb.append(request.getTemperature() != null ? request.getTemperature() : 0.7);
            sb.append("|");
            
            if (request.getMessages() != null) {
                for (ChatModels.Message msg : request.getMessages()) {
                    sb.append(msg.getRole()).append(":").append(msg.getContent()).append("|");
                }
            }

            // Hash the content
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            String hashString = HexFormat.of().formatHex(hash).substring(0, 32);
            
            return CACHE_PREFIX + hashString;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
