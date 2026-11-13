package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The request/response records carry caller content (prompts, model answers derived from vendor
 * documents). Their {@code toString()} must render metadata only, so rendering a record into a log
 * line, an exception dump or a debugger view can never leak that content.
 */
class ModelRecordToStringTest {

  @Test
  void aRequestRendersMetadataButNeverPromptContent() {
    ModelRequest request =
        ModelRequest.structured(
            "evidence.extraction",
            "SYSTEM_CONTENT_MARKER",
            "USER_CONTENT_MARKER",
            new OutputSchema(
                List.of(OutputSchema.Field.required("verdict", OutputSchema.Type.STRING))));

    String rendered = request.toString();

    assertTrue(rendered.contains("evidence.extraction"));
    assertFalse(rendered.contains("SYSTEM_CONTENT_MARKER"));
    assertFalse(rendered.contains("USER_CONTENT_MARKER"));
  }

  @Test
  void aResponseRendersMetadataButNeverAnswerContent() {
    ModelResponse response =
        new ModelResponse("anthropic", "test-model", "RESPONSE_CONTENT_MARKER", null, 10, 5, 42);

    String rendered = response.toString();

    assertTrue(rendered.contains("anthropic"));
    assertFalse(rendered.contains("RESPONSE_CONTENT_MARKER"));
  }
}
