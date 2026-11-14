package com.validexia.ai;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The explicit contract for a structured model output: the fields the caller requires, their types,
 * and any enumerated values. The schema is sent to the provider (as the input schema of a forced
 * tool call) AND enforced locally by {@link StructuredOutputValidator} on the response — the
 * provider's own schema adherence is never trusted on its own, because a malformed or drifting
 * output must fail loudly here rather than propagate into findings or records.
 *
 * <p>Deliberately a small subset of JSON Schema (flat objects, primitive types, string arrays,
 * string enums): enough for extraction and analysis results, simple enough to validate exactly.
 */
public record OutputSchema(List<Field> fields) {

  public OutputSchema {
    fields = List.copyOf(fields);
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("an output schema needs at least one field");
    }
  }

  public enum Type {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT
  }

  /**
   * One expected field. {@code enumValues} constrains STRING fields; {@code itemsType} constrains
   * ARRAY elements (only STRING items are supported, for citation/quote lists).
   */
  public record Field(
      String name, Type type, boolean required, List<String> enumValues, @Nullable Type itemsType) {

    public Field {
      enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
      if (!enumValues.isEmpty() && type != Type.STRING) {
        throw new IllegalArgumentException("enum values only apply to STRING fields: " + name);
      }
      if (itemsType != null && (type != Type.ARRAY || itemsType != Type.STRING)) {
        throw new IllegalArgumentException("only ARRAY of STRING items is supported: " + name);
      }
    }

    public static Field required(String name, Type type) {
      return new Field(name, type, true, List.of(), null);
    }

    public static Field optional(String name, Type type) {
      return new Field(name, type, false, List.of(), null);
    }

    public static Field requiredEnum(String name, List<String> values) {
      return new Field(name, Type.STRING, true, values, null);
    }

    public static Field requiredStringArray(String name) {
      return new Field(name, Type.ARRAY, true, List.of(), Type.STRING);
    }
  }

  /** Renders this schema as a JSON Schema object for a provider's tool input schema. */
  public ObjectNode toJsonSchema(ObjectMapper mapper) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "object");
    ObjectNode properties = root.putObject("properties");
    ArrayNode required = mapper.createArrayNode();
    for (Field field : fields) {
      ObjectNode property = properties.putObject(field.name());
      switch (field.type()) {
        case STRING -> property.put("type", "string");
        case NUMBER -> property.put("type", "number");
        case BOOLEAN -> property.put("type", "boolean");
        case OBJECT -> property.put("type", "object");
        case ARRAY -> {
          property.put("type", "array");
          if (field.itemsType() == Type.STRING) {
            property.putObject("items").put("type", "string");
          }
        }
      }
      if (!field.enumValues().isEmpty()) {
        ArrayNode values = property.putArray("enum");
        field.enumValues().forEach(values::add);
      }
      if (field.required()) {
        required.add(field.name());
      }
    }
    root.set("required", required);
    return root;
  }
}
