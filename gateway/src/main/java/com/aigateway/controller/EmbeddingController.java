package com.aigateway.controller;

import com.aigateway.model.EmbeddingModels;
import com.aigateway.service.RateLimitService;
import com.aigateway.service.RoutingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class EmbeddingController {

    private final RoutingService routingService;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    /**
     * Generate embeddings (OpenAI-compatible)
     */
    @PostMapping("/embeddings")
    public Mono<ResponseEntity<EmbeddingModels.EmbeddingResponse>> createEmbedding(
            @Valid @RequestBody EmbeddingModels.EmbeddingRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {

        String identifier = extractIdentifier(authorization, apiKey);
        Timer.Sample sample = Timer.start(meterRegistry);

        log.info("Embedding request received: model={}, provider={}", 
                request.getModel(), request.getProvider());

        // Rate limit check
        if (!rateLimitService.tryConsume(identifier)) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(createRateLimitError()));
        }

        return routingService.routeEmbed(request)
                .map(response -> {
                    sample.stop(meterRegistry.timer("ai.request.latency", "operation", "embedding"));
                    
                    RateLimitService.RateLimitInfo rateLimitInfo = rateLimitService.getRateLimitInfo(identifier);
                    
                    return ResponseEntity.ok()
                            .header("X-RateLimit-Limit", String.valueOf(rateLimitInfo.limit()))
                            .header("X-RateLimit-Remaining", String.valueOf(rateLimitInfo.remaining()))
                            .header("X-RateLimit-Reset", String.valueOf(rateLimitInfo.resetSeconds()))
                            .body(response);
                })
                .onErrorResume(e -> {
                    log.error("Embedding request failed", e);
                    meterRegistry.counter("ai.request.error", "operation", "embedding").increment();
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(createError(e.getMessage())));
                });
    }

    private String extractIdentifier(String authorization, String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return "anonymous";
    }

    private EmbeddingModels.EmbeddingResponse createError(String message) {
        return EmbeddingModels.EmbeddingResponse.builder()
                .object("error")
                .model("error")
                .build();
    }

    private EmbeddingModels.EmbeddingResponse createRateLimitError() {
        return createError("Rate limit exceeded. Please try again later.");
    }
}
