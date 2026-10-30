package com.example.agend_ai.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EncryptedJsonConverter implements AttributeConverter<JsonNode,String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EncryptedStringConverter cipher = new EncryptedStringConverter();
    @Override public String convertToDatabaseColumn(JsonNode value) {
        return value == null ? null : cipher.convertToDatabaseColumn(value.toString());
    }
    @Override public JsonNode convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return MAPPER.readTree(cipher.convertToEntityAttribute(value)); }
        catch (Exception ex) { throw new IllegalStateException("Invalid decrypted JSON", ex); }
    }
}
