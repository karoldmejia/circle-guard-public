package com.circleguard.identity.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Converter
@Component
public class IdentityEncryptionConverter implements AttributeConverter<String, byte[]> {

    private final TextEncryptor encryptor;

    public IdentityEncryptionConverter(@Value("${vault.secret}") String secret, 
                                     @Value("${vault.salt}") String salt) {
        this.encryptor = Encryptors.text(secret, salt);
    }

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return encryptor.encrypt(attribute).getBytes();
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        return encryptor.decrypt(new String(dbData));
    }
}
