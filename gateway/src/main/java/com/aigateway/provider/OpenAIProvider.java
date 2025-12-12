package com.aigateway.provider;

import com.aigateway.config.AiProviderConfig;
import com.aigateway.model.ChatModels;
import com.aigateway.model.EmbeddingModels;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class OpenAIProvider implements AiProvider {

    private static final String PROVIDER_NAME = "openai";
    private static final List<String> SUPPORTED_MODELS = List.of(
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
            "text-embedding-3-small", "text-embedding-3-large", "text-embedding-ada-002"
    );

    private final WebClient webClient;
    private final AiProviderConfig.ProviderSettings settings;
    private final MeterRegistry meterRegistry;

    public OpenAIProvider(WebClient.Builder webClientBuilder, 
                          AiProviderConfig config,
                          MeterRegistry meterRegistry) {
        this.settings = config.getProviders().get(PROVIDER_NAME);
        this.meterRegistry = meterRegistry;
        
        if (settings != null && settings.getApiKey() != null && !settings.getApiKey().isEmpty()) {
            this.webClient = webClientBuilder
                    .baseUrl(settings.getBaseUrl())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
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
    @CircuitBreaker(name = "openai", fallbackMethod = "chatFallback")
    public Mono<ChatModels.ChatResponse> chat(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("OpenAI provider is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String requestId = UUID.randomUUID().toString();
        
        // Use default model if not specified
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();

        log.debug("OpenAI chat request: model={}, requestId={}", model, requestId);

        // Create clean request for OpenAI (without provider, metadata fields)
        var openAiRequest = new java.util.HashMap<String, Object>();
        openAiRequest.put("model", model);
        openAiRequest.put("messages", request.getMessages());
        if (request.getTemperature() != null) {
            openAiRequest.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            openAiRequest.put("max_tokens", request.getMaxTokens());
        }
        if (request.getTopP() != null) {
            openAiRequest.put("top_p", request.getTopP());
        }
        if (request.getFrequencyPenalty() != null) {
            openAiRequest.put("frequency_penalty", request.getFrequencyPenalty());
        }
        if (request.getPresencePenalty() != null) {
            openAiRequest.put("presence_penalty", request.getPresencePenalty());
        }
        if (request.getStop() != null) {
            openAiRequest.put("stop", request.getStop());
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            openAiRequest.put("stream", true);
        }

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(openAiRequest)
                .retrieve()
                .bodyToMono(ChatModels.ChatResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency", 
                            "provider", PROVIDER_NAME, "operation", "chat"));
                    
                    // Add gateway metadata
                    response.setGateway(ChatModels.GatewayMetadata.builder()
                            .provider(PROVIDER_NAME)
                            .originalModel(model)
                            .latencyMs(latency / 1_000_000) // Convert nanoseconds to milliseconds
                            .cached(false)
                            .retryCount(0)
                            .requestId(requestId)
                            .estimatedCost(calculateCost(response.getUsage()))
                            .build());
                    
                    meterRegistry.counter("ai.provider.requests", 
                            "provider", PROVIDER_NAME, "status", "success").increment();
                    
                    return response;
                })
                .doOnError(e -> {
                    log.error("OpenAI chat error: {}", e.getMessage());
                    meterRegistry.counter("ai.provider.requests", 
                            "provider", PROVIDER_NAME, "status", "error").increment();
                });
    }

    @Override
    public Flux<ChatModels.ChatResponse> chatStream(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Flux.error(new IllegalStateException("OpenAI provider is not available"));
        }

        request.setStream(true);
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();
        request.setModel(model);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatModels.ChatResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()));
    }

    @Override
    @CircuitBreaker(name = "openai", fallbackMethod = "embedFallback")
    public Mono<EmbeddingModels.EmbeddingResponse> embed(EmbeddingModels.EmbeddingRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("OpenAI provider is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String model = request.getModel() != null ? request.getModel() : "text-embedding-3-small";
        request.setModel(model);

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingModels.EmbeddingResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency",
                            "provider", PROVIDER_NAME, "operation", "embed"));
                    
                    response.setGateway(ChatModels.GatewayMetadata.builder()
                            .provider(PROVIDER_NAME)
                            .originalModel(model)
                            .latencyMs(latency / 1_000_000)
                            .cached(false)
                            .build());
                    
                    return response;
                });
    }

    @Override
    public Mono<Boolean> healthCheck() {
        if (!isAvailable()) {
            return Mono.just(false);
        }

        return webClient.get()
                .uri("/models")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false)
                .timeout(Duration.ofSeconds(5));
    }

    private Double calculateCost(ChatModels.Usage usage) {
        if (usage == null) return 0.0;
        // Approximate pricing for GPT-4o-mini
        double inputCost = (usage.getPromptTokens() != null ? usage.getPromptTokens() : 0) * 0.00000015;
        double outputCost = (usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0) * 0.0000006;
        return inputCost + outputCost;
    }

    // Fallback methods
    @SuppressWarnings("unused")
    private Mono<ChatModels.ChatResponse> chatFallback(ChatModels.ChatRequest request, Throwable t) {
        log.warn("OpenAI chat fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("OpenAI temporarily unavailable: " + t.getMessage(), t));
    }

    @SuppressWarnings("unused")
    private Mono<EmbeddingModels.EmbeddingResponse> embedFallback(EmbeddingModels.EmbeddingRequest request, Throwable t) {
        log.warn("OpenAI embed fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("OpenAI temporarily unavailable: " + t.getMessage(), t));
    }
}
