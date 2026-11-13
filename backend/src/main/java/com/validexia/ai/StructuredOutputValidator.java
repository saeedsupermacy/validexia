package com.validexia.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Enforces an {@link OutputSchema} against a model's structured answer, locally. Providers usually
 * honour a tool input schema, but "usually" is not a contract: extraction results feed findings and
 * records, so a missing citation field or a wrong-typed value must fail here with a precise
 * violation list instead of flowing downstream. Extra fields the provider added are tolerated
 * (providers evolve); missing, null, mistyped, or out-of-enum expected fields are not.
 */
@Component
public class StructuredOutputValidator {

  /** Returns the violations found; an empty list means the output conforms to the schema. */
  public List<String> validate(JsonNode output, OutputSchema schema) {
    List<String> violations = new ArrayList<>();
    if (output == null || !output.isObject()) {
      violations.add("structured output is not a JSON object");
      return violations;
    }
    for (OutputSchema.Field field : schema.fields()) {
      JsonNode value = output.get(field.name());
      if (value == null || value.isNull()) {
        if (field.required()) {
          violations.add("missing required field: " + field.name());
        }
        continue;
      }
      switch (field.type()) {
        case STRING -> {
          if (!value.isTextual()) {
            violations.add("field " + field.name() + " must be a string");
          } else if (!field.enumValues().isEmpty()
              && !field.enumValues().contains(value.asText())) {
            violations.add(
                "field "
                    + field.name()
                    + " must be one of "
                    + field.enumValues()
                    + " but was \""
                    + value.asText()
                    + "\"");
          }
        }
        case NUMBER -> {
          if (!value.isNumber()) {
            violations.add("field " + field.name() + " must be a number");
          }
        }
        case BOOLEAN -> {
          if (!value.isBoolean()) {
            violations.add("field " + field.name() + " must be a boolean");
          }
        }
        case OBJECT -> {
          if (!value.isObject()) {
            violations.add("field " + field.name() + " must be an object");
          }
        }
        case ARRAY -> {
          if (!value.isArray()) {
            violations.add("field " + field.name() + " must be an array");
          } else if (field.itemsType() == OutputSchema.Type.STRING) {
            for (int i = 0; i < value.size(); i++) {
              if (!value.get(i).isTextual()) {
                violations.add("field " + field.name() + " item " + i + " must be a string");
                break;
              }
            }
          }
        }
      }
    }
    return violations;
  }

  /** Validates, or throws {@link ModelProviderException} with every violation listed. */
  public void requireValid(JsonNode output, OutputSchema schema) {
    List<String> violations = validate(output, schema);
    if (!violations.isEmpty()) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "structured output failed validation: " + String.join("; ", violations));
    }
  }
}
