package com.example.agend_ai.service.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Validates the exact subset of JSON Schema used by intent-schema.json, on the server too. */
@Component
public class IntentValidator {
  private final ObjectMapper mapper;
  private final JsonNode schema;

  public IntentValidator(ObjectMapper mapper) throws IOException {
    this.mapper =
        mapper
            .copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    try (var in = new ClassPathResource("nlu/intent-schema.json").getInputStream()) {
      schema = mapper.readTree(in);
    }
  }

  public JsonNode schema() {
    return schema.deepCopy();
  }

  public JsonNode parse(String raw) {
    if (raw == null || raw.length() > 16000) throw new IllegalArgumentException("NLU schema");
    try {
      JsonNode value = mapper.readTree(raw);
      validate(value, schema);
      return value;
    } catch (IOException e) {
      throw new IllegalArgumentException("NLU JSON");
    }
  }

  private void validate(JsonNode v, JsonNode s) {
    if (v == null) throw new IllegalArgumentException("NLU missing value");
    JsonNode types = s.get("type");
    boolean valid = types.isArray() ? false : matches(v, types.asText());
    if (types.isArray()) for (JsonNode t : types) valid |= matches(v, t.asText());
    if (!valid) throw new IllegalArgumentException("NLU type");
    if (s.has("enum")) {
      boolean found = false;
      for (JsonNode e : s.get("enum")) found |= e.equals(v);
      if (!found) throw new IllegalArgumentException("NLU enum");
    }
    if (v.isTextual()
        && (v.asText().strip().isEmpty()
            || v.asText().length() > s.path("maxLength").asInt(Integer.MAX_VALUE)))
      throw new IllegalArgumentException("NLU length");
    if (v.isNumber()
        && (!Double.isFinite(v.asDouble())
            || v.asDouble() < s.path("minimum").asDouble(-Double.MAX_VALUE)
            || v.asDouble() > s.path("maximum").asDouble(Double.MAX_VALUE)))
      throw new IllegalArgumentException("NLU number");
    if (v.isObject()) {
      for (JsonNode required : s.path("required"))
        if (!v.has(required.asText())) throw new IllegalArgumentException("NLU required");
      v.fields()
          .forEachRemaining(
              e -> {
                JsonNode property = s.path("properties").get(e.getKey());
                if (property == null) throw new IllegalArgumentException("NLU additional property");
                validate(e.getValue(), property);
              });
    }
  }

  private boolean matches(JsonNode n, String t) {
    return switch (t) {
      case "object" -> n.isObject();
      case "string" -> n.isTextual();
      case "number" -> n.isNumber();
      case "null" -> n.isNull();
      default -> false;
    };
  }
}
