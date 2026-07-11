package com.pixframe.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Ports pixframe/api/api_utils.py's hash_password()/verify_username_password().
 *
 * Stored format is unchanged: "sha512$<salt>$<hexdigest>". Java's
 * MessageDigest("SHA-512") over UTF-8 bytes of (salt + password) produces
 * byte-identical output to Python's hashlib.sha512, so passwords already
 * hashed by the Flask backend (the seed data) keep working unchanged.
 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    /** Hash a new password, generating a fresh random salt. */
    public static String hashPassword(String password) {
        String salt = UUID.randomUUID().toString().replace("-", "");
        return hashPassword(password, salt);
    }

    /** Hash a password against a known salt (used for verification). */
    public static String hashPassword(String password, String salt) {
        String hexHash = sha512Hex(salt + password);
        return "sha512$" + salt + "$" + hexHash;
    }

    /** Verify a plaintext password against a stored "sha512$salt$hash" string. */
    public static boolean verify(String password, String storedPasswordString) {
        String[] parts = storedPasswordString.split("\\$");
        if (parts.length != 3) {
            return false;
        }
        String salt = parts[1];
        String storedHash = parts[2];
        String computedHash = sha512Hex(salt + password);
        return storedHash.equals(computedHash);
    }

    private static String sha512Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }
}
