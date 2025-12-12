package com.aigateway.config;

import com.aigateway.model.ChatModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ChatModels.ErrorResponse>> handleValidationException(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");

        log.warn("Validation error: {}", message);

        return Mono.just(ResponseEntity.badRequest()
                .body(createErrorResponse("invalid_request_error", message, "validation_error")));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ChatModels.ErrorResponse>> handleIllegalStateException(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(createErrorResponse("service_unavailable", ex.getMessage(), "provider_error")));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public Mono<ResponseEntity<ChatModels.ErrorResponse>> handleUnsupportedOperationException(UnsupportedOperationException ex) {
        log.warn("Unsupported operation: {}", ex.getMessage());

        return Mono.just(ResponseEntity.badRequest()
                .body(createErrorResponse("invalid_request_error", ex.getMessage(), "unsupported_operation")));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ChatModels.ErrorResponse>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);

        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("server_error", "An unexpected error occurred", "internal_error")));
    }

    private ChatModels.ErrorResponse createErrorResponse(String type, String message, String code) {
        return ChatModels.ErrorResponse.builder()
                .error(ChatModels.ErrorResponse.Error.builder()
                        .type(type)
                        .message(message)
                        .code(code)
                        .build())
                .build();
    }
}
