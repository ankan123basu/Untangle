package dev.untangle.auth.api;

import dev.untangle.auth.application.AuthService;
import dev.untangle.auth.application.AuthService.AuthResult;
import dev.untangle.auth.application.AuthViews.CurrentAgent;
import dev.untangle.auth.application.AuthViews.LoginRequest;
import dev.untangle.auth.application.AuthViews.TokenResponse;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (MASTER_PROMPT §6.1).
 *
 * <p>The refresh token lives only in a cookie that is:
 *
 * <ul>
 *   <li>{@code HttpOnly}: JavaScript (including injected scripts) can't read it;
 *   <li>{@code Secure}: only sent over HTTPS (localhost counts as secure in modern browsers);
 *   <li>{@code SameSite=Strict}: never sent on cross-site requests, which blocks CSRF;
 *   <li>{@code Path=/api/v1/auth}: not even sent to the rest of the API.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  public static final String REFRESH_COOKIE = "untangle_refresh";
  static final String COOKIE_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final boolean secureCookie;

  public AuthController(AuthService authService, SecurityProperties properties) {
    this.authService = authService;
    this.secureCookie = properties.refresh().cookieSecure();
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    AuthResult result =
        authService.login(
            request.email(), request.password(), http.getRemoteAddr(), userAgent(http));
    return withRefreshCookie(result);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletRequest http) {
    AuthResult result = authService.refresh(refreshToken, http.getRemoteAddr(), userAgent(http));
    return withRefreshCookie(result);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    authService.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  @GetMapping("/me")
  public CurrentAgent me(@AuthenticationPrincipal AuthenticatedAgent me) {
    return authService.currentAgent(me.id(), me.fullName(), me.email(), me.role());
  }

  private ResponseEntity<TokenResponse> withRefreshCookie(AuthResult result) {
    ResponseCookie cookie = cookie(result.refreshTokenValue(), result.refreshTtl());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(result.body());
  }

  private ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(secureCookie)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }

  private static String userAgent(HttpServletRequest http) {
    return http.getHeader(HttpHeaders.USER_AGENT);
  }
}
