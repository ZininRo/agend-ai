package com.example.agend_ai.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String,String> {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "enc:v1:";
    private static final SecretKeySpec KEY = loadKey();

    private static SecretKeySpec loadKey() {
        String value = System.getenv("DATA_ENCRYPTION_KEY");
        if (value == null || value.isBlank()) throw new IllegalStateException("DATA_ENCRYPTION_KEY is required (32 random bytes, base64)");
        byte[] raw;
        try { raw = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("DATA_ENCRYPTION_KEY must be base64", ex); }
        if (raw.length != 32) throw new IllegalStateException("DATA_ENCRYPTION_KEY must decode to 32 bytes");
        return new SecretKeySpec(raw, "AES");
    }
    @Override public String convertToDatabaseColumn(String plain) {
        if (plain == null) return null;
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce,0,packed,0,nonce.length);
            System.arraycopy(ciphertext,0,packed,nonce.length,ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) { throw new IllegalStateException("Encryption failed",ex); }
    }
    @Override public String convertToEntityAttribute(String encrypted) {
        if (encrypted == null) return null;
        if (!encrypted.startsWith(PREFIX)) throw new IllegalStateException("Unencrypted or unsupported database value");
        try {
            byte[] packed = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            if (packed.length < 29) throw new IllegalArgumentException("Invalid encrypted value");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(128, packed, 0, 12));
            return new String(cipher.doFinal(packed,12,packed.length-12),StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Cannot decrypt database value; check key",ex); }
    }
}
