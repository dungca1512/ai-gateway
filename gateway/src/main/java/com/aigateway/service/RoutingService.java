package com.aigateway.service;

import com.aigateway.config.AiProviderConfig;
import com.aigateway.model.ChatModels;
import com.aigateway.model.EmbeddingModels;
import com.aigateway.provider.AiProvider;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final List<AiProvider> providers;
    private final AiProviderConfig config;
    private final MeterRegistry meterRegistry;

    /**
     * Route chat request to appropriate provider with fallback
     */
    public Mono<ChatModels.ChatResponse> routeChat(ChatModels.ChatRequest request) {
        String requestedProvider = request.getProvider();
        String requestedModel = request.getModel();

        log.info("Routing chat request: provider={}, model={}", requestedProvider, requestedModel);

        // Get ordered list of providers to try
        List<AiProvider> providersToTry = getProvidersToTry(requestedProvider, requestedModel);

        if (providersToTry.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers for request"));
        }

        // Try providers in order with fallback
        return tryProvidersInOrder(providersToTry, request);
    }

    /**
     * Route embedding request to appropriate provider
     */
    public Mono<EmbeddingModels.EmbeddingResponse> routeEmbed(EmbeddingModels.EmbeddingRequest request) {
        String requestedProvider = request.getProvider();

        log.info("Routing embedding request: provider={}", requestedProvider);

        List<AiProvider> providersToTry = getProvidersToTry(requestedProvider, request.getModel());

        // Filter providers that support embeddings (exclude Claude)
        providersToTry = providersToTry.stream()
                .filter(p -> !p.getName().equals("claude"))
                .collect(Collectors.toList());

        if (providersToTry.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers for embedding request"));
        }

        return tryEmbedProvidersInOrder(providersToTry, request);
    }

    /**
     * Route streaming chat request
     */
    public Flux<ChatModels.ChatResponse> routeChatStream(ChatModels.ChatRequest request) {
        String requestedProvider = request.getProvider();
        String requestedModel = request.getModel();

        List<AiProvider> providersToTry = getProvidersToTry(requestedProvider, requestedModel);

        if (providersToTry.isEmpty()) {
            return Flux.error(new IllegalStateException("No available providers for request"));
        }

        // For streaming, just use the first available provider
        AiProvider provider = providersToTry.get(0);
        log.info("Streaming via provider: {}", provider.getName());

        return provider.chatStream(request);
    }

    /**
     * Get all available providers status
     */
    public Mono<Map<String, ProviderStatus>> getProvidersStatus() {
        return Flux.fromIterable(providers)
                .flatMap(provider -> provider.healthCheck()
                        .map(healthy -> Map.entry(provider.getName(), 
                                new ProviderStatus(provider.isAvailable(), healthy, provider.getPriority()))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private List<AiProvider> getProvidersToTry(String requestedProvider, String requestedModel) {
        List<AiProvider> availableProviders = providers.stream()
                .filter(AiProvider::isAvailable)
                .sorted(Comparator.comparingInt(AiProvider::getPriority))
                .collect(Collectors.toList());

        // If specific provider requested, put it first
        if (requestedProvider != null && !requestedProvider.isEmpty()) {
            Optional<AiProvider> requested = availableProviders.stream()
                    .filter(p -> p.getName().equalsIgnoreCase(requestedProvider))
                    .findFirst();

            if (requested.isPresent()) {
                availableProviders.remove(requested.get());
                availableProviders.add(0, requested.get());
            }
        }

        // If specific model requested, filter to providers that support it
        if (requestedModel != null && !requestedModel.isEmpty()) {
            // First, check if any provider explicitly supports the model
            List<AiProvider> modelProviders = availableProviders.stream()
                    .filter(p -> p.supportsModel(requestedModel))
                    .collect(Collectors.toList());

            if (!modelProviders.isEmpty()) {
                availableProviders = modelProviders;
            }
        }

        // Apply fallback setting
        if (!config.getRouting().isFallbackEnabled() && !availableProviders.isEmpty()) {
            return List.of(availableProviders.get(0));
        }

        return availableProviders;
    }

    private Mono<ChatModels.ChatResponse> tryProvidersInOrder(List<AiProvider> providers, 
                                                               ChatModels.ChatRequest request) {
        if (providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No providers available"));
        }

        AiProvider primaryProvider = providers.get(0);
        List<AiProvider> fallbackProviders = providers.subList(1, providers.size());

        return primaryProvider.chat(request)
                .retryWhen(Retry.backoff(config.getRouting().getMaxRetries(), 
                        Duration.ofMillis(config.getRouting().getRetryDelayMs()))
                        .filter(this::isRetryableError)
                        .doBeforeRetry(signal -> {
                            log.warn("Retrying {} after error: {}", 
                                    primaryProvider.getName(), signal.failure().getMessage());
                            meterRegistry.counter("ai.routing.retry",
                                    "provider", primaryProvider.getName()).increment();
                        }))
                .onErrorResume(e -> {
                    log.error("Provider {} failed: {}", primaryProvider.getName(), e.getMessage());
                    meterRegistry.counter("ai.routing.fallback",
                            "from", primaryProvider.getName()).increment();

                    if (fallbackProviders.isEmpty()) {
                        return Mono.error(e);
                    }

                    log.info("Falling back to next provider");
                    return tryProvidersInOrder(fallbackProviders, request)
                            .map(response -> {
                                // Update gateway metadata to indicate fallback
                                if (response.getGateway() != null) {
                                    response.getGateway().setRetryCount(
                                            response.getGateway().getRetryCount() + 1);
                                }
                                return response;
                            });
                });
    }

    private Mono<EmbeddingModels.EmbeddingResponse> tryEmbedProvidersInOrder(List<AiProvider> providers,
                                                                              EmbeddingModels.EmbeddingRequest request) {
        if (providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No providers available"));
        }

        AiProvider primaryProvider = providers.get(0);
        List<AiProvider> fallbackProviders = providers.subList(1, providers.size());

        return primaryProvider.embed(request)
                .retryWhen(Retry.backoff(config.getRouting().getMaxRetries(),
                        Duration.ofMillis(config.getRouting().getRetryDelayMs()))
                        .filter(this::isRetryableError))
                .onErrorResume(e -> {
                    log.error("Provider {} failed for embedding: {}", primaryProvider.getName(), e.getMessage());

                    if (fallbackProviders.isEmpty()) {
                        return Mono.error(e);
                    }

                    return tryEmbedProvidersInOrder(fallbackProviders, request);
                });
    }

    private boolean isRetryableError(Throwable t) {
        // Retry on timeout, connection errors, 5xx errors
        String message = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("503") ||
                message.contains("502") ||
                message.contains("504") ||
                message.contains("429"); // Rate limit
    }

    public record ProviderStatus(boolean configured, boolean healthy, int priority) {}
}
