package com.quashbugs.magnus.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class DataEncryptionService {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    private final SecretKeySpec secretKey;

    public DataEncryptionService(@Value("${spring.secure.encryption.key}") String encodedKey) {
        if (encodedKey == null || encodedKey.isEmpty()) {
            throw new IllegalStateException("Encryption key is not set");
        }
        byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
        this.secretKey = new SecretKeySpec(decodedKey, "AES");
    }

    public String encrypt(String data) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

        byte[] encryptedData = cipher.doFinal(data.getBytes());
        byte[] message = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, message, 0, iv.length);
        System.arraycopy(encryptedData, 0, message, iv.length, encryptedData.length);

        return Base64.getEncoder().encodeToString(message);
    }
}