package com.aigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Swagger UI webjars resources are served by springdoc automatically
        // No need for manual resource handler configuration
    }

    @Bean
    public RouterFunction<ServerResponse> swaggerUIRedirect() {
        // Redirect /swagger-ui.html to the actual Swagger UI location
        return RouterFunctions.route(GET("/swagger-ui.html"),
                req -> ServerResponse.temporaryRedirect(URI.create("/webjars/swagger-ui/index.html")).build());
    }
}

