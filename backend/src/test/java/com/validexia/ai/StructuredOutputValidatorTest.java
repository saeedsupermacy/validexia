package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredOutputValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final StructuredOutputValidator validator = new StructuredOutputValidator();

  private OutputSchema schema() {
    return new OutputSchema(
        List.of(
            OutputSchema.Field.requiredEnum("verdict", List.of("PASS", "FAIL")),
            OutputSchema.Field.requiredStringArray("quotes"),
            OutputSchema.Field.optional("score", OutputSchema.Type.NUMBER)));
  }

  private com.fasterxml.jackson.databind.JsonNode json(String text) throws Exception {
    return mapper.readTree(text);
  }

  @Test
  void aConformingOutputHasNoViolations() throws Exception {
    assertTrue(
        validator
            .validate(
                json("{\"verdict\":\"PASS\",\"quotes\":[\"q1\",\"q2\"],\"score\":4}"), schema())
            .isEmpty());
  }

  @Test
  void extraFieldsAreTolerated() throws Exception {
    assertTrue(
        validator
            .validate(json("{\"verdict\":\"FAIL\",\"quotes\":[],\"somethingElse\":true}"), schema())
            .isEmpty());
  }

  @Test
  void aMissingRequiredFieldIsAViolation() throws Exception {
    List<String> violations = validator.validate(json("{\"quotes\":[]}"), schema());
    assertEquals(List.of("missing required field: verdict"), violations);
  }

  @Test
  void aNullRequiredFieldIsAViolation() throws Exception {
    List<String> violations =
        validator.validate(json("{\"verdict\":null,\"quotes\":[]}"), schema());
    assertEquals(List.of("missing required field: verdict"), violations);
  }

  @Test
  void anOutOfEnumValueIsAViolation() throws Exception {
    List<String> violations =
        validator.validate(json("{\"verdict\":\"MAYBE\",\"quotes\":[]}"), schema());
    assertEquals(1, violations.size());
    assertTrue(violations.get(0).contains("must be one of"));
  }

  @Test
  void aWrongTypedFieldIsAViolation() throws Exception {
    List<String> violations =
        validator.validate(
            json("{\"verdict\":\"PASS\",\"quotes\":[],\"score\":\"high\"}"), schema());
    assertEquals(List.of("field score must be a number"), violations);
  }

  @Test
  void aNonStringArrayItemIsAViolation() throws Exception {
    List<String> violations =
        validator.validate(json("{\"verdict\":\"PASS\",\"quotes\":[\"ok\",7]}"), schema());
    assertEquals(List.of("field quotes item 1 must be a string"), violations);
  }

  @Test
  void aNonObjectOutputIsAViolation() throws Exception {
    assertEquals(
        List.of("structured output is not a JSON object"),
        validator.validate(json("[1,2,3]"), schema()));
  }

  @Test
  void requireValidThrowsWithEveryViolationListed() throws Exception {
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class, () -> validator.requireValid(json("{}"), schema()));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertTrue(thrown.getMessage().contains("verdict"));
    assertTrue(thrown.getMessage().contains("quotes"));
  }
}
