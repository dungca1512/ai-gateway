package com.aigateway.provider;

import com.aigateway.model.ChatModels;
import com.aigateway.model.EmbeddingModels;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for all AI providers (OpenAI, Gemini, Claude, Local Worker)
 */
public interface AiProvider {
    
    /**
     * Get the provider name
     */
    String getName();
    
    /**
     * Check if the provider is enabled and properly configured
     */
    boolean isAvailable();
    
    /**
     * Get provider priority (lower = higher priority)
     */
    int getPriority();
    
    /**
     * Check if the provider supports the given model
     */
    boolean supportsModel(String model);
    
    /**
     * Perform chat completion
     */
    Mono<ChatModels.ChatResponse> chat(ChatModels.ChatRequest request);
    
    /**
     * Perform streaming chat completion
     */
    Flux<ChatModels.ChatResponse> chatStream(ChatModels.ChatRequest request);
    
    /**
     * Generate embeddings
     */
    Mono<EmbeddingModels.EmbeddingResponse> embed(EmbeddingModels.EmbeddingRequest request);
    
    /**
     * Health check
     */
    Mono<Boolean> healthCheck();
}
