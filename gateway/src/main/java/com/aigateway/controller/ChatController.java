package com.aigateway.controller;

import com.aigateway.model.ChatModels;
import com.aigateway.service.CacheService;
import com.aigateway.service.RateLimitService;
import com.aigateway.service.RoutingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final RoutingService routingService;
    private final CacheService cacheService;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    /**
     * Chat completion endpoint (OpenAI-compatible)
     */
    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<ChatModels.ChatResponse>> chatCompletion(
            @Valid @RequestBody ChatModels.ChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {

        String identifier = extractIdentifier(authorization, apiKey);
        Timer.Sample sample = Timer.start(meterRegistry);

        log.info("Chat request received: model={}, messages={}, provider={}", 
                request.getModel(), 
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getProvider());

        // Rate limit check
        if (!rateLimitService.tryConsume(identifier)) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(createRateLimitError()));
        }

        // Check streaming
        if (Boolean.TRUE.equals(request.getStream())) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(createError("Use /chat/completions/stream for streaming")));
        }

        // Try cache first
        return cacheService.getCachedResponse(request)
                .switchIfEmpty(Mono.defer(() -> 
                    routingService.routeChat(request)
                            .flatMap(response -> 
                                cacheService.cacheResponse(request, response)
                                        .thenReturn(response))))
                .map(response -> {
                    sample.stop(meterRegistry.timer("ai.request.latency", "operation", "chat"));
                    
                    RateLimitService.RateLimitInfo rateLimitInfo = rateLimitService.getRateLimitInfo(identifier);
                    
                    return ResponseEntity.ok()
                            .header("X-RateLimit-Limit", String.valueOf(rateLimitInfo.limit()))
                            .header("X-RateLimit-Remaining", String.valueOf(rateLimitInfo.remaining()))
                            .header("X-RateLimit-Reset", String.valueOf(rateLimitInfo.resetSeconds()))
                            .header("X-Request-Id", response.getGateway() != null ? 
                                    response.getGateway().getRequestId() : "unknown")
                            .body(response);
                })
                .onErrorResume(e -> {
                    log.error("Chat request failed", e);
                    meterRegistry.counter("ai.request.error", "operation", "chat").increment();
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(createError(e.getMessage())));
                });
    }

    /**
     * Streaming chat completion endpoint
     */
    @PostMapping(value = "/chat/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatModels.ChatResponse> chatCompletionStream(
            @Valid @RequestBody ChatModels.ChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {

        String identifier = extractIdentifier(authorization, apiKey);

        // Rate limit check
        if (!rateLimitService.tryConsume(identifier)) {
            return Flux.error(new RuntimeException("Rate limit exceeded"));
        }

        request.setStream(true);

        return routingService.routeChatStream(request)
                .doOnError(e -> log.error("Streaming chat failed", e));
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

    private ChatModels.ChatResponse createError(String message) {
        ChatModels.ChatResponse response = new ChatModels.ChatResponse();
        response.setId("error");
        response.setObject("error");
        response.setGateway(ChatModels.GatewayMetadata.builder()
                .provider("gateway")
                .build());
        response.setChoices(List.of(ChatModels.Choice.builder()
                .index(0)
                .message(ChatModels.Message.builder()
                        .role("system")
                        .content("Error: " + message)
                        .build())
                .finishReason("error")
                .build()));
        return response;
    }

    private ChatModels.ChatResponse createRateLimitError() {
        return createError("Rate limit exceeded. Please try again later.");
    }
}
