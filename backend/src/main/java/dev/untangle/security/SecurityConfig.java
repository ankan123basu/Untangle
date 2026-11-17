package dev.untangle.security;

import dev.untangle.security.jwt.JwtAgentAuthenticationConverter;
import dev.untangle.security.rbac.Permission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * HTTP security for the whole API (MASTER_PROMPT §6).
 *
 * <ul>
 *   <li><b>Stateless:</b> no server session; every request carries a bearer JWT, so any backend
 *       instance can serve any request.
 *   <li><b>Default deny:</b> only login/refresh/logout and health probes are public; everything
 *       else needs a valid token. Fine-grained rules use {@code @PreAuthorize} on controllers.
 *   <li><b>CSRF</b> is disabled. Bearer tokens are not sent automatically by browsers, so they
 *       can't be forged cross-site. The only cookie (refresh token) is {@code SameSite=Strict} and
 *       scoped to {@code /api/v1/auth}.
 *   <li>401/403 go through {@code GlobalExceptionHandler}, so they are Problem Details like every
 *       other error.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

  /** Public endpoints. Everything not listed requires authentication. */
  private static final String[] PUBLIC_POST = {
    "/api/v1/auth/login",
    "/api/v1/auth/refresh",
    "/api/v1/auth/logout",
    // Partner webhook: authenticated by HMAC signature in WebhookService, not by JWT.
    "/api/v1/webhooks/partners/tickets"
  };

  private static final String[] PUBLIC_GET = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    // Prometheus scrape: in prod it only exists on the unpublished management port (8081).
    "/actuator/prometheus",
    "/livez",
    "/readyz",
    // API documentation (no data, only the contract)
    "/v3/api-docs",
    "/v3/api-docs/**",
    "/swagger-ui.html",
    "/swagger-ui/**"
  };

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      JwtAgentAuthenticationConverter jwtConverter,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(HttpMethod.POST, PUBLIC_POST)
                .permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_GET)
                .permitAll()
                .requestMatchers("/error")
                .permitAll()
                // Coarse rules evaluated BEFORE the request body is parsed and validated, so an
                // unauthorised caller gets 403 instead of a 400 that reveals field names.
                // @PreAuthorize on each controller method remains the fine-grained second layer.
                .requestMatchers("/api/v1/admin/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/agents")
                .hasAuthority(Permission.AGENT_MANAGE.name())
                .requestMatchers(HttpMethod.PATCH, "/api/v1/agents/**")
                .hasAuthority(Permission.AGENT_MANAGE.name())
                .requestMatchers(HttpMethod.POST, "/api/v1/tickets/bulk/**")
                .hasAuthority(Permission.TICKET_ASSIGN_ANY.name())
                .requestMatchers("/api/v1/dashboard/agents-workload")
                .hasAuthority(Permission.AGENT_WORKLOAD_VIEW.name())
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(oauth ->
            oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                .authenticationEntryPoint((request, response, ex) ->
                    exceptionResolver.resolveException(request, response, null, ex)))
        .exceptionHandling(errors ->
            errors
                .authenticationEntryPoint((request, response, ex) ->
                    exceptionResolver.resolveException(request, response, null, ex))
                .accessDeniedHandler((request, response, ex) ->
                    exceptionResolver.resolveException(request, response, null, ex)))
        .headers(headers ->
            headers.referrerPolicy(referrer ->
                referrer.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
        .build();
  }

  /**
   * CORS is off unless origins are configured. In Docker and in Vite dev the SPA and API share one
   * origin through a proxy, so no CORS is needed. Set {@code CORS_ALLOWED_ORIGINS} only for a
   * split-origin deployment.
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (!properties.corsAllowedOrigins().isEmpty()) {
      CorsConfiguration config = new CorsConfiguration();
      config.setAllowedOrigins(properties.corsAllowedOrigins());
      config.addAllowedMethod("*");
      config.addAllowedHeader("*");
      config.addExposedHeader("X-Request-Id");
      config.setAllowCredentials(true);
      source.registerCorsConfiguration("/api/**", config);
    }
    return source;
  }

  /** BCrypt, cost 12 in production (~250 ms per hash); tests use 4 for speed. */
  @Bean
  PasswordEncoder passwordEncoder(SecurityProperties properties) {
    return new BCryptPasswordEncoder(properties.bcryptStrength());
  }
}
