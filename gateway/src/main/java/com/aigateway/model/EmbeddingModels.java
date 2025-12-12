package com.aigateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class EmbeddingModels {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddingRequest {
        @NotNull(message = "Input cannot be null")
        @NotEmpty(message = "Input cannot be empty")
        private Object input; // String or List<String>
        
        @Builder.Default
        private String model = "text-embedding-3-small";
        
        @JsonProperty("encoding_format")
        @Builder.Default
        private String encodingFormat = "float";
        
        private Integer dimensions;
        
        private String user;
        
        private String provider;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddingResponse {
        private String object;
        private List<EmbeddingData> data;
        private String model;
        private Usage usage;
        
        private ChatModels.GatewayMetadata gateway;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddingData {
        private String object;
        private List<Double> embedding;
        private Integer index;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
