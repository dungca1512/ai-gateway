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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class PythonWorkerProvider implements AiProvider {

    private static final String PROVIDER_NAME = "local-worker";
    private static final List<String> SUPPORTED_MODELS = List.of(
            "local-llm", "local-embed", "qwen", "llama", "mistral"
    );

    private final WebClient webClient;
    private final AiProviderConfig.ProviderSettings settings;
    private final MeterRegistry meterRegistry;

    public PythonWorkerProvider(WebClient.Builder webClientBuilder,
                                 AiProviderConfig config,
                                 MeterRegistry meterRegistry) {
        this.settings = config.getProviders().get(PROVIDER_NAME);
        this.meterRegistry = meterRegistry;

        if (settings != null && settings.getBaseUrl() != null) {
            this.webClient = webClientBuilder
                    .baseUrl(settings.getBaseUrl())
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
        return settings != null && settings.isEnabled() && webClient != null;
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
    @CircuitBreaker(name = "local-worker", fallbackMethod = "chatFallback")
    public Mono<ChatModels.ChatResponse> chat(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Python Worker is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String requestId = UUID.randomUUID().toString();
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();
        request.setModel(model);

        log.debug("Python Worker chat request: model={}, requestId={}", model, requestId);

        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatModels.ChatResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency",
                            "provider", PROVIDER_NAME, "operation", "chat"));

                    response.setGateway(ChatModels.GatewayMetadata.builder()
                            .provider(PROVIDER_NAME)
                            .originalModel(model)
                            .latencyMs(latency / 1_000_000)
                            .cached(false)
                            .retryCount(0)
                            .requestId(requestId)
                            .estimatedCost(0.0) // Local model, no cost
                            .build());

                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "success").increment();

                    return response;
                })
                .doOnError(e -> {
                    log.error("Python Worker chat error: {}", e.getMessage());
                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "error").increment();
                });
    }

    @Override
    public Flux<ChatModels.ChatResponse> chatStream(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Flux.error(new IllegalStateException("Python Worker is not available"));
        }

        request.setStream(true);
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();
        request.setModel(model);

        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatModels.ChatResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()));
    }

    @Override
    @CircuitBreaker(name = "local-worker", fallbackMethod = "embedFallback")
    public Mono<EmbeddingModels.EmbeddingResponse> embed(EmbeddingModels.EmbeddingRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Python Worker is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);

        return webClient.post()
                .uri("/v1/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingModels.EmbeddingResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency",
                            "provider", PROVIDER_NAME, "operation", "embed"));

                    response.setGateway(ChatModels.GatewayMetadata.builder()
                            .provider(PROVIDER_NAME)
                            .originalModel("local-embed")
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
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false)
                .timeout(Duration.ofSeconds(5));
    }

    // Fallback methods
    @SuppressWarnings("unused")
    private Mono<ChatModels.ChatResponse> chatFallback(ChatModels.ChatRequest request, Throwable t) {
        log.warn("Python Worker chat fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("Python Worker temporarily unavailable: " + t.getMessage(), t));
    }

    @SuppressWarnings("unused")
    private Mono<EmbeddingModels.EmbeddingResponse> embedFallback(EmbeddingModels.EmbeddingRequest request, Throwable t) {
        log.warn("Python Worker embed fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("Python Worker temporarily unavailable: " + t.getMessage(), t));
    }
}
