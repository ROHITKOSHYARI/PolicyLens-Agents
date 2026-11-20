package com.policylens.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides descriptive metadata for generated Swagger documentation. */
@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI policyLensOpenApi() {
        return new OpenAPI().info(new Info().title("PolicyLens Agents API").version("0.1.0")
                .description("Local enterprise policy assistant API."));
    }
}
