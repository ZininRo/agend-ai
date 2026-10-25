package com.example.agend_ai.service.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;

/**
 * Приводит «почти правильный» ответ модели к виду, который ожидает {@link IntentValidator}:
 * достаёт JSON-объект из блока ```json … ``` или из текста вокруг, убирает пустые слоты
 * (null, "", "null", "none"), обрезает пробелы вокруг строк, нормализует регистр intent и
 * переводит confidence из процентов (от 10 до 100, например 95) в долю (0.95). Числа и логические
 * значения в слотах НЕ превращаются в строки молча: такой ответ отвергается.
 *
 * <p>Это только «уборка»: строгую проверку схемы, типов и длин по-прежнему делает IntentValidator,
 * а лишние поля и неизвестные значения по-прежнему отклоняются.
 */
public final class ModelOutputCleaner {
  private ModelOutputCleaner() {}

  /** Если это не JSON-объект, возвращает исходный текст: ошибку выдаст IntentValidator. */
  public static String clean(String raw, ObjectMapper mapper) {
    if (raw == null) return null;
    String text = raw.strip();
    int start = text.indexOf('{'), end = text.lastIndexOf('}');
    if (start < 0 || end <= start) return raw;
    try {
      JsonNode root =
          mapper
              .readerFor(JsonNode.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(text.substring(start, end + 1));
      if (!(root instanceof ObjectNode object)) return raw;

      JsonNode intent = object.get("intent");
      if (intent != null && intent.isTextual())
        object.put("intent", intent.asText().strip().toLowerCase(Locale.ROOT));

      JsonNode confidence = object.get("confidence");
      if (confidence != null && confidence.isNumber()) {
        double c = confidence.asDouble();
        if (c >= 10 && c <= 100) object.put("confidence", c / 100);
      }

      if (object.get("slots") instanceof ObjectNode slots) {
        List<String> remove = new ArrayList<>();
        Map<String, String> stringify = new LinkedHashMap<>();
        slots
            .fields()
            .forEachRemaining(
                e -> {
                  JsonNode v = e.getValue();
                  if (v.isNull()) remove.add(e.getKey());
                  else if (v.isTextual()) {
                    String t = v.asText().strip();
                    if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("none"))
                      remove.add(e.getKey());
                    else if (!t.equals(v.asText())) stringify.put(e.getKey(), t);
                  }
                });
        remove.forEach(slots::remove);
        stringify.forEach((k, v) -> slots.set(k, TextNode.valueOf(v)));
      }
      return mapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      return raw;
    }
  }
}
