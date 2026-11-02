package com.example.agend_ai.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal .env reader for the local evaluation CLI. Process environment always wins. */
final class DotEnv {
  private DotEnv() {}

  static Map<String, String> load(Path path) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    if (path == null || !Files.isRegularFile(path)) return values;
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      if (trimmed.startsWith("export ")) trimmed = trimmed.substring(7).strip();
      int equals = trimmed.indexOf('=');
      if (equals <= 0) continue;
      String key = trimmed.substring(0, equals).strip();
      String value = trimmed.substring(equals + 1).strip();
      if ((value.startsWith("\"") && value.endsWith("\""))
          || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(key, value);
    }
    return values;
  }

  static String get(Map<String, String> fileValues, String key, String fallback) {
    String env = System.getenv(key);
    if (env != null && !env.isBlank()) return env;
    String property = System.getProperty(key);
    if (property != null && !property.isBlank()) return property;
    String file = fileValues.get(key);
    return file == null || file.isBlank() ? fallback : file;
  }
}
