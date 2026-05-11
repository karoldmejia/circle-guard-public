package com.circleguard.identity.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IdentityEncryptionConverterTest {

    private IdentityEncryptionConverter converter;
    private final String secret = "test-secret-32-chars-long-123456";
    private final String salt = "deadbeef";

    @BeforeEach
    void setUp() {
        converter = new IdentityEncryptionConverter(secret, salt);
    }

    @Test
    void shouldEncryptAndDecryptSuccessfully() {
        String original = "user@example.com";
        byte[] encrypted = converter.convertToDatabaseColumn(original);
        
        assertNotNull(encrypted);
        assertNotEquals(original, new String(encrypted));
        
        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void shouldHandleNulls() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }
}
