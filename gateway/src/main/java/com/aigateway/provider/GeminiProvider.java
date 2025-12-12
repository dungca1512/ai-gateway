package com.aigateway.provider;

import com.aigateway.config.AiProviderConfig;
import com.aigateway.model.ChatModels;
import com.aigateway.model.EmbeddingModels;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GeminiProvider implements AiProvider {

    private static final String PROVIDER_NAME = "gemini";
    private static final List<String> SUPPORTED_MODELS = List.of(
            "gemini-2.5-flash", "gemini-2.5-pro",
            "gemini-2.0-flash", "gemini-2.0-pro",
            "text-embedding-004", "embedding-001"
    );

    private final WebClient webClient;
    private final AiProviderConfig.ProviderSettings settings;
    private final MeterRegistry meterRegistry;

    public GeminiProvider(WebClient.Builder webClientBuilder,
                          AiProviderConfig config,
                          MeterRegistry meterRegistry) {
        this.settings = config.getProviders().get(PROVIDER_NAME);
        this.meterRegistry = meterRegistry;

        if (settings != null && settings.getApiKey() != null && !settings.getApiKey().isEmpty()) {
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
    @CircuitBreaker(name = "gemini", fallbackMethod = "chatFallback")
    public Mono<ChatModels.ChatResponse> chat(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Gemini provider is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String requestId = UUID.randomUUID().toString();
        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();

        log.debug("Gemini chat request: model={}, requestId={}", model, requestId);

        // Convert OpenAI format to Gemini format
        GeminiRequest geminiRequest = convertToGeminiRequest(request);
        
        // Build full URL directly to avoid WebClient uriBuilder issues
        String url = settings.getBaseUrl() + "/models/" + model + ":generateContent?key=" + settings.getApiKey();
        log.debug("Gemini chat URL: {}", url.replaceAll("key=.*", "key=***"));

        return WebClient.create()
                .post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(geminiRequest)
                .retrieve()
                .bodyToMono(GeminiResponse.class)
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
                            .build());

                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "success").increment();

                    return chatResponse;
                })
                .doOnError(e -> {
                    log.error("Gemini chat error: {}", e.getMessage());
                    meterRegistry.counter("ai.provider.requests",
                            "provider", PROVIDER_NAME, "status", "error").increment();
                });
    }

    @Override
    public Flux<ChatModels.ChatResponse> chatStream(ChatModels.ChatRequest request) {
        if (!isAvailable()) {
            return Flux.error(new IllegalStateException("Gemini provider is not available"));
        }

        String model = request.getModel() != null ? request.getModel() : settings.getDefaultModel();
        GeminiRequest geminiRequest = convertToGeminiRequest(request);
        String apiKey = settings.getApiKey();

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/" + model + ":streamGenerateContent")
                        .queryParam("key", apiKey)
                        .build())
                .bodyValue(geminiRequest)
                .retrieve()
                .bodyToFlux(GeminiResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> convertToOpenAIFormat(response, model));
    }

    @Override
    @CircuitBreaker(name = "gemini", fallbackMethod = "embedFallback")
    public Mono<EmbeddingModels.EmbeddingResponse> embed(EmbeddingModels.EmbeddingRequest request) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Gemini provider is not available"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String model = "text-embedding-004";

        GeminiEmbedRequest embedRequest = new GeminiEmbedRequest();
        embedRequest.setModel("models/" + model);
        
        GeminiEmbedRequest.Content content = new GeminiEmbedRequest.Content();
        if (request.getInput() instanceof String) {
            content.setParts(List.of(new GeminiEmbedRequest.Part((String) request.getInput())));
        } else if (request.getInput() instanceof List) {
            content.setParts(((List<?>) request.getInput()).stream()
                    .map(s -> new GeminiEmbedRequest.Part(s.toString()))
                    .collect(Collectors.toList()));
        }
        embedRequest.setContent(content);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/" + model + ":embedContent")
                        .queryParam("key", settings.getApiKey())
                        .build())
                .bodyValue(embedRequest)
                .retrieve()
                .bodyToMono(GeminiEmbedResponse.class)
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .map(response -> {
                    long latency = sample.stop(meterRegistry.timer("ai.provider.latency",
                            "provider", PROVIDER_NAME, "operation", "embed"));

                    EmbeddingModels.EmbeddingResponse embedResponse = EmbeddingModels.EmbeddingResponse.builder()
                            .object("list")
                            .model(model)
                            .data(List.of(EmbeddingModels.EmbeddingData.builder()
                                    .object("embedding")
                                    .embedding(response.getEmbedding().getValues())
                                    .index(0)
                                    .build()))
                            .gateway(ChatModels.GatewayMetadata.builder()
                                    .provider(PROVIDER_NAME)
                                    .originalModel(model)
                                    .latencyMs(latency / 1_000_000)
                                    .cached(false)
                                    .build())
                            .build();

                    return embedResponse;
                });
    }

    @Override
    public Mono<Boolean> healthCheck() {
        if (!isAvailable()) {
            log.debug("Gemini healthCheck: not available");
            return Mono.just(false);
        }

        String apiKey = settings.getApiKey();
        String url = settings.getBaseUrl() + "/models?key=" + apiKey;
        log.debug("Gemini healthCheck: calling URL with prefix apiKey={}", 
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "null");
        
        // Use a fresh WebClient without any default headers
        return WebClient.create()
                .get()
                .uri(url)
                .exchangeToMono(response -> {
                    boolean success = response.statusCode().is2xxSuccessful();
                    log.debug("Gemini healthCheck: status={}, success={}", response.statusCode(), success);
                    return response.releaseBody().thenReturn(success);
                })
                .doOnError(e -> log.warn("Gemini healthCheck failed: {}", e.getMessage()))
                .onErrorReturn(false)
                .timeout(Duration.ofSeconds(10));
    }

    private GeminiRequest convertToGeminiRequest(ChatModels.ChatRequest request) {
        GeminiRequest geminiRequest = new GeminiRequest();
        
        List<GeminiRequest.Content> contents = new ArrayList<>();
        StringBuilder systemPrompt = new StringBuilder();
        
        for (ChatModels.Message msg : request.getMessages()) {
            String role = msg.getRole();
            
            // Gemini doesn't support "system" role directly, prepend to first user message
            if ("system".equals(role)) {
                systemPrompt.append(msg.getContent()).append("\n\n");
                continue;
            }
            
            GeminiRequest.Content content = new GeminiRequest.Content();
            // Map roles: assistant -> model, user -> user
            content.setRole("assistant".equals(role) ? "model" : "user");
            
            String messageContent = msg.getContent();
            // Prepend system prompt to first user message
            if ("user".equals(role) && systemPrompt.length() > 0) {
                messageContent = systemPrompt.toString() + messageContent;
                systemPrompt.setLength(0); // Clear after use
            }
            
            content.setParts(List.of(new GeminiRequest.Part(messageContent)));
            contents.add(content);
        }
        
        // If only system message, convert to user message
        if (contents.isEmpty() && systemPrompt.length() > 0) {
            GeminiRequest.Content content = new GeminiRequest.Content();
            content.setRole("user");
            content.setParts(List.of(new GeminiRequest.Part(systemPrompt.toString())));
            contents.add(content);
        }
        
        geminiRequest.setContents(contents);

        GeminiRequest.GenerationConfig config = new GeminiRequest.GenerationConfig();
        if (request.getTemperature() != null) {
            config.setTemperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            config.setMaxOutputTokens(request.getMaxTokens());
        }
        if (request.getTopP() != null) {
            config.setTopP(request.getTopP());
        }
        geminiRequest.setGenerationConfig(config);

        return geminiRequest;
    }

    private ChatModels.ChatResponse convertToOpenAIFormat(GeminiResponse response, String model) {
        String content = "";
        if (response.getCandidates() != null && !response.getCandidates().isEmpty()) {
            GeminiResponse.Candidate candidate = response.getCandidates().get(0);
            if (candidate.getContent() != null && candidate.getContent().getParts() != null) {
                content = candidate.getContent().getParts().stream()
                        .map(GeminiResponse.Part::getText)
                        .collect(Collectors.joining());
            }
        }

        return ChatModels.ChatResponse.builder()
                .id("gemini-" + UUID.randomUUID().toString().substring(0, 8))
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .model(model)
                .choices(List.of(ChatModels.Choice.builder()
                        .index(0)
                        .message(ChatModels.Message.builder()
                                .role("assistant")
                                .content(content)
                                .build())
                        .finishReason("stop")
                        .build()))
                .usage(response.getUsageMetadata() != null ? ChatModels.Usage.builder()
                        .promptTokens(response.getUsageMetadata().getPromptTokenCount())
                        .completionTokens(response.getUsageMetadata().getCandidatesTokenCount())
                        .totalTokens(response.getUsageMetadata().getTotalTokenCount())
                        .build() : null)
                .build();
    }

    // Fallback methods
    @SuppressWarnings("unused")
    private Mono<ChatModels.ChatResponse> chatFallback(ChatModels.ChatRequest request, Throwable t) {
        log.warn("Gemini chat fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("Gemini temporarily unavailable: " + t.getMessage(), t));
    }

    @SuppressWarnings("unused")
    private Mono<EmbeddingModels.EmbeddingResponse> embedFallback(EmbeddingModels.EmbeddingRequest request, Throwable t) {
        log.warn("Gemini embed fallback triggered: {}", t.getMessage());
        return Mono.error(new RuntimeException("Gemini temporarily unavailable: " + t.getMessage(), t));
    }

    // Gemini API DTOs
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeminiRequest {
        private List<Content> contents;
        private GenerationConfig generationConfig;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Content {
            private String role;
            private List<Part> parts;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Part {
            private String text;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class GenerationConfig {
            private Double temperature;
            private Integer maxOutputTokens;
            private Double topP;
            private Double topK;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeminiResponse {
        private List<Candidate> candidates;
        private UsageMetadata usageMetadata;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Candidate {
            private Content content;
            private String finishReason;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Content {
            private String role;
            private List<Part> parts;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Part {
            private String text;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class UsageMetadata {
            private Integer promptTokenCount;
            private Integer candidatesTokenCount;
            private Integer totalTokenCount;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeminiEmbedRequest {
        private String model;
        private Content content;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Content {
            private List<Part> parts;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Part {
            private String text;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeminiEmbedResponse {
        private Embedding embedding;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Embedding {
            private List<Double> values;
        }
    }
}
