package com.example.agend_ai.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedConvertersTest {
    private final EncryptedStringConverter strings = new EncryptedStringConverter();

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        String plain = "Встреча с Егором: обсудить приложение";
        String first = strings.convertToDatabaseColumn(plain);
        String second = strings.convertToDatabaseColumn(plain);
        assertTrue(first.startsWith("enc:v1:"));
        assertNotEquals(first, second, "Each encryption must use a fresh nonce");
        assertFalse(first.contains(plain));
        assertEquals(plain, strings.convertToEntityAttribute(first));
        assertEquals(plain, strings.convertToEntityAttribute(second));
    }

    @Test
    void rejectsTamperedCiphertextAndPlaintext() {
        String encrypted = strings.convertToDatabaseColumn("secret");
        char last = encrypted.charAt(encrypted.length() - 2);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 2)
                + replacement + encrypted.charAt(encrypted.length() - 1);
        assertThrows(IllegalStateException.class, () -> strings.convertToEntityAttribute(tampered));
        assertThrows(IllegalStateException.class, () -> strings.convertToEntityAttribute("secret"));
    }

    @Test
    void jsonRoundTripAndNullValues() throws Exception {
        var converter = new EncryptedJsonConverter();
        var json = new ObjectMapper().readTree("{\"description\":\"Обсудить приложение\"}");
        String encrypted = converter.convertToDatabaseColumn(json);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertEquals(json, converter.convertToEntityAttribute(encrypted));
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(strings.convertToDatabaseColumn(null));
        assertNull(strings.convertToEntityAttribute(null));
    }
}
