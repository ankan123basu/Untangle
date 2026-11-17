package dev.untangle.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 description of the API, served at {@code /v3/api-docs} with Swagger UI at {@code
 * /swagger-ui}. The frontend generates its TypeScript types from this spec (openapi-typescript), so
 * the contract must be <b>precise</b> (ADR-023):
 *
 * <ul>
 *   <li>Response fields are always present (Jackson writes nulls too), so every property of a
 *       response schema is marked {@code required}. Without this, every field would be optional in
 *       TypeScript.
 *   <li>Fields that can be null are annotated {@code @jakarta.annotation.Nullable} and published as
 *       {@code T | null}. The frontend is then forced to handle exactly those, and no others.
 *   <li>Request bodies keep the required list derived from Bean Validation ({@code @NotNull} etc.).
 * </ul>
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

  /** {@code @Nullable} record components become {@code T | null} in the spec. */
  @Bean
  PropertyCustomizer nullableProperties() {
    return (property, type) -> {
      boolean nullable =
          type.getCtxAnnotations() != null
              && Arrays.stream(type.getCtxAnnotations()).anyMatch(a -> a instanceof Nullable);
      if (!nullable) {
        return property;
      }
      if (property.get$ref() != null) {
        Schema<Object> union = new Schema<>();
        union.setOneOf(List.of(new Schema<>().$ref(property.get$ref()), nullType()));
        return union;
      }
      Set<String> types =
          property.getTypes() == null ? new HashSet<>() : new HashSet<>(property.getTypes());
      if (property.getType() != null) {
        types.add(property.getType());
      }
      types.add("null");
      property.setTypes(types);
      return property;
    };
  }

  /** Every property of a response schema is always present in the JSON. */
  @Bean
  OpenApiCustomizer responseFieldsAreRequired() {
    return api -> {
      Set<String> requestSchemas = new HashSet<>();
      if (api.getPaths() != null) {
        api.getPaths()
            .values()
            .forEach(item ->
                item.readOperations()
                    .forEach(operation -> {
                      if (operation.getRequestBody() != null
                          && operation.getRequestBody().getContent() != null) {
                        operation
                            .getRequestBody()
                            .getContent()
                            .values()
                            .forEach(media -> collectRef(media.getSchema(), requestSchemas));
                      }
                    }));
      }
      if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
        return;
      }
      api.getComponents()
          .getSchemas()
          .forEach((name, schema) -> {
            if (!requestSchemas.contains(name) && schema.getProperties() != null) {
              schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
            }
          });
    };
  }

  private static void collectRef(Schema<?> schema, Set<String> names) {
    if (schema != null && schema.get$ref() != null) {
      names.add(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
    }
  }

  private static Schema<Object> nullType() {
    Schema<Object> schema = new Schema<>();
    schema.setTypes(Set.of("null"));
    return schema;
  }
}
