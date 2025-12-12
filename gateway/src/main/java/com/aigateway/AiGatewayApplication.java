package com.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}
