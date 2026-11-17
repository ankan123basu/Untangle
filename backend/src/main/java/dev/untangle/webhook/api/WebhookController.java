package dev.untangle.webhook.api;

import dev.untangle.webhook.application.WebhookService;
import dev.untangle.webhook.application.WebhookService.Headers;
import dev.untangle.webhook.application.WebhookService.WebhookResponse;
import dev.untangle.webhook.hmac.WebhookBodyLimitFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner door (§7). No JWT: authentication is the HMAC signature. The body is taken as raw
 * bytes ({@code byte[]}) so the signature is checked against exactly what was sent, before any JSON
 * parsing. See docs/webhook-integration-guide.md.
 */
@Tag(name = "Partner webhook")
@RestController
public class WebhookController {

  private final WebhookService webhooks;

  public WebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @Operation(
      summary = "Create a ticket from a signed partner event",
      description =
          "201 = created, 200 = duplicate event (same ticket returned). Signature: X-Signature:"
              + " sha256=hex(HMAC-SHA256(secret, X-Timestamp + \".\" + rawBody)).")
  @SecurityRequirements // not bearer: HMAC
  @PostMapping(path = WebhookBodyLimitFilter.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebhookResponse> receive(
      @RequestHeader(name = "X-Partner-Id", required = false) String partnerId,
      @RequestHeader(name = "X-Event-Id", required = false) String eventId,
      @RequestHeader(name = "X-Timestamp", required = false) String timestamp,
      @RequestHeader(name = "X-Signature", required = false) String signature,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    WebhookResponse response =
        webhooks.receive(
            new Headers(partnerId, eventId, timestamp, signature),
            body == null ? new byte[0] : body,
            request.getRemoteAddr());
    return ResponseEntity.status(response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(response);
  }
}
