package dev.untangle.webhook.api;

import dev.untangle.common.api.PageResponse;
import dev.untangle.webhook.application.PartnerAdmin;
import dev.untangle.webhook.application.PartnerAdmin.CreatePartner;
import dev.untangle.webhook.application.PartnerAdmin.DeliveryView;
import dev.untangle.webhook.application.PartnerAdmin.IssuedSecret;
import dev.untangle.webhook.application.PartnerAdmin.PartnerView;
import dev.untangle.webhook.application.PartnerAdmin.SetActive;
import dev.untangle.webhook.application.PartnerAdmin.UpdatePartner;
import dev.untangle.webhook.domain.DeliveryOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin: partners, secret rotation and the webhook delivery log (§7.3, §12.5 screen 7). */
@Tag(name = "Admin: partners & webhooks")
@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class PartnerAdminController {

  private final PartnerAdmin admin;

  public PartnerAdminController(PartnerAdmin admin) {
    this.admin = admin;
  }

  @GetMapping("/partners")
  @PreAuthorize("hasAuthority('PARTNER_MANAGE')")
  public List<PartnerView> list() {
    return admin.list();
  }

  @Operation(summary = "Create a partner. The response contains the secret ONCE.")
  @PostMapping("/partners")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('PARTNER_MANAGE')")
  public IssuedSecret create(@Valid @RequestBody CreatePartner request) {
    return admin.create(request);
  }

  @PatchMapping("/partners/{id}")
  @PreAuthorize("hasAuthority('PARTNER_MANAGE')")
  public PartnerView update(@PathVariable UUID id, @Valid @RequestBody UpdatePartner request) {
    return admin.update(id, request);
  }

  @Operation(
      summary =
          "Rotate the secret. New secret shown ONCE; the old one keeps working for the grace window.")
  @PostMapping("/partners/{id}/rotate-secret")
  @PreAuthorize("hasAuthority('PARTNER_MANAGE')")
  public IssuedSecret rotate(@PathVariable UUID id) {
    return admin.rotateSecret(id);
  }

  @PatchMapping("/partners/{id}/active")
  @PreAuthorize("hasAuthority('PARTNER_MANAGE')")
  public PartnerView setActive(@PathVariable UUID id, @Valid @RequestBody SetActive request) {
    return admin.setActive(id, request.active());
  }

  @Operation(summary = "Every webhook attempt with its outcome and precise reason (newest first)")
  @GetMapping("/webhook-deliveries")
  @PreAuthorize("hasAuthority('WEBHOOK_LOG_VIEW')")
  public PageResponse<DeliveryView> deliveries(
      @RequestParam(required = false) UUID partnerId,
      @RequestParam(required = false) DeliveryOutcome outcome,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
    return admin.deliveries(partnerId, outcome, from, to, page, size);
  }
}
