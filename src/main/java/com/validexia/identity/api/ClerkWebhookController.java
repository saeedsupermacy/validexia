package com.validexia.identity.api;

import com.validexia.identity.application.SvixSignatureVerifier;
import com.validexia.identity.application.WebhookEventService;
import com.validexia.platform.config.ClerkProperties;
import com.validexia.platform.errors.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Clerk webhook receiver (M02-1 v2.6 §2). Unauthenticated by design (it is not behind the token
 * filter); authenticity comes from the Svix signature over the RAW request body.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class ClerkWebhookController {

  private final ClerkProperties properties;
  private final SvixSignatureVerifier verifier;
  private final WebhookEventService service;
  private final ObjectMapper mapper;

  public ClerkWebhookController(
      ClerkProperties properties,
      SvixSignatureVerifier verifier,
      WebhookEventService service,
      ObjectMapper mapper) {
    this.properties = properties;
    this.verifier = verifier;
    this.service = service;
    this.mapper = mapper;
  }

  @PostMapping("/clerk")
  public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
    if (!properties.hasWebhookSecret()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    byte[] body = request.getInputStream().readAllBytes();
    String svixId = request.getHeader("svix-id");
    boolean verified =
        verifier.verify(
            properties.webhookSigningSecret(),
            svixId,
            request.getHeader("svix-timestamp"),
            request.getHeader("svix-signature"),
            body);
    if (!verified || svixId == null) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "WEBHOOK_SIGNATURE_INVALID", "Webhook signature invalid");
    }
    JsonNode event;
    try {
      event = mapper.readTree(body);
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "WEBHOOK_SIGNATURE_INVALID", "Webhook body is not valid JSON");
    }
    service.process(svixId, event);
    return ResponseEntity.ok().build();
  }
}
