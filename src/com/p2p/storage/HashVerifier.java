package com.p2p.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** SHA-256 hashing and verification (anti-poisoning). */
public final class HashVerifier {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Pattern HASH_RE = Pattern.compile("^[0-9a-f]{64}$");

    private HashVerifier() {}

    public static boolean isValidHash(String s) {
        return s != null && HASH_RE.matcher(s).matches();
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Hash a ByteBuffer without changing its position/limit. */
    public static String sha256(ByteBuffer buf) {
        MessageDigest d = newDigest();
        d.update(buf.duplicate());
        return toHex(d.digest());
    }

    public static String sha256(byte[] data) {
        return sha256(ByteBuffer.wrap(data));
    }

    /** Hash a whole file using FileChannel + direct ByteBuffer (one piece in memory at a time). */
    public static String sha256File(Path file) throws IOException {
        MessageDigest d = newDigest();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
            while (ch.read(buf) != -1) {
                buf.flip();
                d.update(buf);
                buf.clear();
            }
        }
        return toHex(d.digest());
    }

    /** Constant-time comparison against the expected hex hash. */
    public static boolean matches(byte[] data, String expectedHex) {
        if (data == null || expectedHex == null) return false;
        String exp = expectedHex.toLowerCase();
        if (!isValidHash(exp)) return false;
        return MessageDigest.isEqual(
                sha256(data).getBytes(StandardCharsets.US_ASCII),
                exp.getBytes(StandardCharsets.US_ASCII));
    }
}
