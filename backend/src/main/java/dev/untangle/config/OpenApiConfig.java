package dev.untangle.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 description of the API, served at {@code /v3/api-docs} with Swagger UI at {@code
 * /swagger-ui}. The frontend generates its TypeScript types from this spec (openapi-typescript), so
 * client and server can't silently drift apart.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  @Bean
  OpenAPI untangleOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Untangle API")
                .version("v1")
                .description(
                    "Every complaint, untangled. Errors are RFC 9457 Problem Details with a"
                        + " machine-readable `code` and a `traceId`."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }
}
