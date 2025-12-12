package com.aigateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public class ChatModels {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String model;
        
        @NotNull(message = "Messages cannot be null")
        @NotEmpty(message = "Messages cannot be empty")
        private List<Message> messages;
        
        @Builder.Default
        private Double temperature = 0.7;
        
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        
        @Builder.Default
        private Boolean stream = false;
        
        @JsonProperty("top_p")
        private Double topP;
        
        @JsonProperty("frequency_penalty")
        private Double frequencyPenalty;
        
        @JsonProperty("presence_penalty")
        private Double presencePenalty;
        
        private List<String> stop;
        
        private String user;
        
        // Provider hint for routing
        private String provider;
        
        // Additional metadata
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        @NotNull
        private String role;
        
        @NotNull
        private String content;
        
        private String name;
        
        @JsonProperty("function_call")
        private Map<String, Object> functionCall;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        private String id;
        private String object;
        private Long created;
        private String model;
        private List<Choice> choices;
        private Usage usage;
        
        // Gateway metadata
        private GatewayMetadata gateway;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private Integer index;
        private Message message;
        
        @JsonProperty("finish_reason")
        private String finishReason;
        
        private Message delta; // For streaming
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GatewayMetadata {
        private String provider;
        private String originalModel;
        private Long latencyMs;
        private Boolean cached;
        private Integer retryCount;
        private String requestId;
        private Double estimatedCost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private Error error;
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Error {
            private String message;
            private String type;
            private String code;
            private String param;
        }
    }
}
