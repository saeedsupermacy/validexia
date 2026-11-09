package com.validexia.identity.application;

import com.validexia.identity.infrastructure.DirectoryRepository;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Clerk webhook processing (M02-1 v2.6 §2). Idempotency is insert-first: the event row, the
 * state change, and the outcome marking commit in ONE transaction. A transient failure rolls
 * everything back (the event row disappears), so Svix's redelivery reprocesses from scratch. A
 * deterministic "poison" event is instead recorded and marked {@code failed} in a separate
 * transaction, then acknowledged — redelivering it would never succeed.
 *
 * <p>Clerk webhook deliveries are not guaranteed and may arrive out of order or more than once;
 * convergence comes from the per-field timestamp ordering inside the directory functions.
 */
@Service
public class WebhookEventService {

  private static final Logger log = LoggerFactory.getLogger(WebhookEventService.class);

  /** A payload that can never be applied (malformed or missing required fields). */
  public static class PoisonEventException extends RuntimeException {
    public PoisonEventException(String message) {
      super(message);
    }
  }

  public enum Outcome {
    APPLIED,
    IGNORED,
    DUPLICATE
  }

  private final DirectoryRepository directory;
  private final TransactionTemplate transactions;

  public WebhookEventService(
      DirectoryRepository directory, PlatformTransactionManager transactionManager) {
    this.directory = directory;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public Outcome process(String svixId, JsonNode event) {
    String type = event.path("type").asString("");
    JsonNode data = event.path("data");
    try {
      return transactions.execute(
          tx -> {
            if (!directory.recordWebhookEvent(svixId, type)) {
              return Outcome.DUPLICATE;
            }
            Outcome outcome = dispatch(type, data, event);
            directory.markWebhookEvent(svixId, outcome == Outcome.APPLIED ? "applied" : "ignored");
            return outcome;
          });
    } catch (PoisonEventException e) {
      log.warn("Poison webhook event {} ({}): {}", svixId, type, e.getMessage());
      transactions.executeWithoutResult(
          tx -> {
            if (directory.recordWebhookEvent(svixId, type)) {
              directory.markWebhookEvent(svixId, "failed");
            }
          });
      return Outcome.IGNORED;
    }
  }

  private Outcome dispatch(String type, JsonNode data, JsonNode event) {
    return switch (type) {
      case "user.created", "user.updated" -> {
        applyUserUpsert(data, event);
        yield Outcome.APPLIED;
      }
      case "user.deleted" -> {
        String subject = requiredSubject(data);
        directory.applyLifecycle(subject, "deleted", null, null, null, eventTs(data, event));
        yield Outcome.APPLIED;
      }
      case "user.merged" -> {
        JsonNode survivor = data.path("user");
        JsonNode deleted = data.path("deleted_user");
        String survivorId = survivor.path("id").asString("");
        String deletedId = deleted.path("id").asString("");
        if (survivorId.isBlank() || deletedId.isBlank()) {
          throw new PoisonEventException("user.merged payload lacks user/deleted_user ids");
        }
        directory.mergeUsers(survivorId, deletedId, eventTs(survivor, event));
        yield Outcome.APPLIED;
      }
      case "session.revoked", "session.removed" -> {
        String sid = data.path("id").asString("");
        String subject = data.path("user_id").asString("");
        if (sid.isBlank() || subject.isBlank()) {
          throw new PoisonEventException("session event lacks id/user_id");
        }
        directory.recordSessionRevocation(sid, subject, Instant.now().plusSeconds(3600));
        yield Outcome.APPLIED;
      }
      default -> Outcome.IGNORED;
    };
  }

  private void applyUserUpsert(JsonNode data, JsonNode event) {
    String subject = requiredSubject(data);
    String email = null;
    JsonNode addresses = data.path("email_addresses");
    if (addresses.isArray() && !addresses.isEmpty()) {
      email = addresses.get(0).path("email_address").asString(null);
    }
    String first = data.path("first_name").asString("");
    String last = data.path("last_name").asString("");
    String displayName = (first + " " + last).trim();
    Instant ts = eventTs(data, event);
    directory.syncUserProfile(subject, email, displayName.isEmpty() ? null : displayName, ts);
    // The user.updated payload is the mandatory backstop for ALL flag state: Clerk has no
    // ban/lock webhook events. Absent field = the event says nothing about that flag.
    directory.applyLifecycle(
        subject,
        null,
        flag(data, "banned"),
        flag(data, "locked"),
        flag(data, "deprovisioned"),
        ts);
  }

  private @Nullable Boolean flag(JsonNode data, String field) {
    JsonNode node = data.get(field);
    return node == null || node.isNull() ? null : node.asBoolean(false);
  }

  private String requiredSubject(JsonNode data) {
    String id = data.path("id").asString("");
    if (id.isBlank()) {
      throw new PoisonEventException("user event payload lacks data.id");
    }
    return id;
  }

  /** Event time: the payload's updated_at (ms), falling back to the envelope timestamp. */
  private Instant eventTs(JsonNode data, JsonNode event) {
    long updatedAt = data.path("updated_at").asLong(0);
    if (updatedAt > 0) {
      return Instant.ofEpochMilli(updatedAt);
    }
    long envelope = event.path("timestamp").asLong(0);
    if (envelope > 0) {
      return Instant.ofEpochMilli(envelope);
    }
    return Instant.now();
  }
}
