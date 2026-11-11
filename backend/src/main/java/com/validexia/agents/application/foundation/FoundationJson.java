package com.validexia.agents.application.foundation;

/** Minimal JSON string quoting for the foundation package (inputs are small and controlled). */
final class FoundationJson {

  private FoundationJson() {}

  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    return out.append('"').toString();
  }
}
