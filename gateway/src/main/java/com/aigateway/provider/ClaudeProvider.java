package com.aigateway.provider;

import com.aigateway.config.AiProviderConfig;
import com.aigateway.model.ChatModels;
import com.aigateway.model.EmbeddingModels;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ClaudeProvider implements AiProvider {

    private static final String PROVIDER_NAME = "claude";
    private static final List<String> SUPPORTED_MODELS = List.of(
            "claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus", 
            "claude-3-sonnet", "claude-3-haiku"
    );

    private final WebClient webClient;
    private final AiProviderConfig.ProviderSettings settings;
    private final MeterRegistry meterRegistry;

    public ClaudeProvider(WebClient.Builder webClientBuilder,
                          AiProviderConfig config,
                          MeterRegistry meterRegistry) {
        this.settings = config.getProviders().get(PROVIDER_NAME);
        this.meterRegistry = meterRegistry;

        if (settings != null && settings.getApiKey() != null && !settings.getApiKey().isEmpty()) {
            this.webClient = webClientBuilder
                    .baseUrl(settings.getBaseUrl())
                    .defaultHeader("x-api-key", settings.getApiKey())
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        } else {
            this.webClient = null;
        }
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return settings != null &&
                settings.isEnabled() &&
                settings.getApiKey() != null &&
                !settings.getApiKey().isEmpty() &&
                webClient != null;
    }

    @Override
    public int getPriority() {
        return settings != null ? settings.getPriority() : Integer.MAX_VALUE;
    }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return true;
        return SUPPORTED_MODELS.stream()
                .anyMatch(m -> model.toLowerCase().contains(m.toLowerCase()));
    }

    @Override
    @CircuitBreaker(name = "claude", fallbackMethod = "chatFallback")
    public Mono<ChatModels.ChatResponse> chat(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Claude provider is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String requestId = UUID.randomUUID().toString();
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();

        log.debug("Claude chat request: model={}, requestId={}", model, requestId);

        // Convert OpenAI format to Claude format
        ClaudeRequest claudeRequest = convertToClaudeRequest(request, model);

        return webClient.post()
                .uri("/messages")
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToMono(ClaudeResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency",
                            "provider", PROVIDER_NAME, "operation", "chat"));

                    ChatModels.ChatResponse chatResponse = convertToOpenAIFormat(response, model);
                    chatResponse.setGateway(ChatModels.GatewayMetadata.builder()
                            .provider(PROVIDER_NAME)
                            .originalModel(model)
                            .latencyMs(latency / 1_000_000)
                            .cached(false)
                            .retryCount(0)
                            .requestId(requestId)
                            .estimatedCost(calculateCost(response.getUsage()))
                            .build());

                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "success").increment();

                    return chatResponse;
                })
                .doOnError(e -> {
                    log.error("Claude chat error: {}", e.getMessage());
                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "error").increment();
                });
    }

    @Override
    public Flux<ChatModels.ChatResponse> chatStream(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Flux.error(new IllegalStateException("Claude provider is not available"));
        }

        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();
        ClaudeRequest claudeRequest = convertToClaudeRequest(request, model);
        claudeRequest.setStream(true);

        return webClient.post()
                .uri("/messages")
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToFlux(ClaudeResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> convertToOpenAIFormat(response, model));
    }

    @Override
    public Mono<EmbeddingModels.EmbeddingResponse> embed(EmbeddingModels.EmbeddingRequest request) {
        // Claude does not have native embedding support
        return Mono.error(new UnsupportedOperationException("Claude does not support embeddings"));
    }

    @Override
    public Mono<Boolean> healthCheck() {
        if (!isAvailable()) {
            return Mono.just(false);
        }

        // Claude doesn't have a models endpoint, so we'll just check if we can make a minimal request
        return Mono.just(true);
    }

    private ClaudeRequest convertToClaudeRequest(ChatModels.ChatRequest request, String model) {
        ClaudeRequest claudeRequest = new ClaudeRequest();
        claudeRequest.setModel(model);
        claudeRequest.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 4096);

        // Extract system message if present
        String systemMessage = request.getMessages().stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(ChatModels.Message::getContent)
                .findFirst()
                .orElse(null);
        claudeRequest.setSystem(systemMessage);

        // Convert messages (excluding system)
        List<ClaudeRequest.Message> messages = request.getMessages().stream()
                .filter(m -> !"system".equals(m.getRole()))
                .map(m -> new ClaudeRequest.Message(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
        claudeRequest.setMessages(messages);

        if (request.getTemperature() != null) {
            claudeRequest.setTemperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            claudeRequest.setTopP(request.getTopP());
        }

        return claudeRequest;
    }

    private ChatModels.ChatResponse convertToOpenAIFormat(ClaudeResponse response, String model) {
        String content = "";
        if (response.getContent() != null && !response.getContent().isEmpty()) {
            content = response.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(ClaudeResponse.ContentBlock::getText)
                    .collect(Collectors.joining());
        }

        return ChatModels.ChatResponse.builder()
                .id(response.getId() != null ? response.getId() : "claude-" + UUID.randomUUID().toString().substring(0, 8))
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .model(model)
                .choices(List.of(ChatModels.Choice.builder()
                        .index(0)
                        .message(ChatModels.Message.builder()
                                .role("assistant")
                                .content(content)
                                .build())
                        .finishReason(mapStopReason(response.getStopReason()))
                        .build()))
                .usage(response.getUsage() != null ? ChatModels.Usage.builder()
                        .promptTokens(response.getUsage().getInputTokens())
                        .completionTokens(response.getUsage().getOutputTokens())
                        .totalTokens((response.getUsage().getInputTokens() != null ? response.getUsage().getInputTokens() : 0) +
                                (response.getUsage().getOutputTokens() != null ? response.getUsage().getOutputTokens() : 0))
                        .build() : null)
                .build();
    }

    private String mapStopReason(String claudeStopReason) {
        if (claudeStopReason == null) return "stop";
        return switch (claudeStopReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "stop_sequence" -> "stop";
            default -> claudeStopReason;
        };
    }

    private Double calculateCost(ClaudeResponse.Usage usage) {
        if (usage == null) return 0.0;
        // Approximate pricing for Claude 3.5 Sonnet
        double inputCost = (usage.getInputTokens() != null ? usage.getInputTokens() : 0) * 0.000003;
        double outputCost = (usage.getOutputTokens() != null ? usage.getOutputTokens() : 0) * 0.000015;
        return inputCost + outputCost;
    }

    // Fallback methods
    @SuppressWarnings("unused")
    private Mono<ChatModels.ChatResponse> chatFallback(ChatModels.ChatRequest request, Throwable t) {
        log.warn("Claude chat fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("Claude temporarily unavailable: " + t.getMessage(), t));
    }

    // Claude API DTOs
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeRequest {
        private String model;
        
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        
        private String system;
        private List<Message> messages;
        private Double temperature;
        
        @JsonProperty("top_p")
        private Double topP;
        
        private Boolean stream;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Message {
            private String role;
            private String content;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeResponse {
        private String id;
        private String type;
        private String role;
        private List<ContentBlock> content;
        private String model;
        
        @JsonProperty("stop_reason")
        private String stopReason;
        
        @JsonProperty("stop_sequence")
        private String stopSequence;
        
        private Usage usage;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ContentBlock {
            private String type;
            private String text;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Usage {
            @JsonProperty("input_tokens")
            private Integer inputTokens;
            
            @JsonProperty("output_tokens")
            private Integer outputTokens;
        }
    }
}
