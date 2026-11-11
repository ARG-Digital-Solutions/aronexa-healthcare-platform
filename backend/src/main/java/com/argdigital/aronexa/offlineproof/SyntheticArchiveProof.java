package com.argdigital.aronexa.offlineproof;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * COMM-V1-T09: authenticated, encrypted SYNTHETIC-FILE roundtrip proof ONLY.
 * NOT a PostgreSQL backup utility, PHI vault or commercial recovery procedure.
 *
 * Only tiny inputs prefixed by a fixed fictional marker can be processed;
 * real database dumps and medical documents MUST be rejected.
 */
public final class SyntheticArchiveProof {
    private SyntheticArchiveProof() {}

    public static final String FIXTURE_MARKER = "ARONEXA_SYNTHETIC_FIXTURE\n";
    private static final byte[] MARKER_BYTES = FIXTURE_MARKER.getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAGIC = "ANX-DEMO1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FIXTURE = 1_048_576;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KDF_ITERATIONS = 220_000;
    private static final int HEADER_LENGTH = MAGIC.length + SALT_LENGTH + NONCE_LENGTH;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void encryptFixture(Path source, Path encryptedOutput, char[] passphrase)
            throws IOException, GeneralSecurityException {
        checkPassphrase(passphrase);
        byte[] plain = boundedRead(source, MAX_FIXTURE);
        try {
            requireFixtureMarker(plain);
            byte[] salt = new byte[SALT_LENGTH], nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);
            byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                    .put(MAGIC).put(salt).put(nonce).array();

            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce);
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plain);
            try {
                byte[] archive = ByteBuffer.allocate(HEADER_LENGTH + encrypted.length)
                        .put(header).put(encrypted).array();
                try {
                    createNewFile(encryptedOutput, archive);
                } finally {
                    Arrays.fill(archive, (byte) 0);
                }
            } finally {
                Arrays.fill(encrypted, (byte) 0);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public static void restoreFixture(Path encryptedSource, Path newOutput, char[] passphrase)
            throws IOException, GeneralSecurityException {
        checkPassphrase(passphrase);
        byte[] archive = boundedRead(encryptedSource,
                MAX_FIXTURE + HEADER_LENGTH + GCM_TAG_BITS / 8);
        byte[] plain = null;
        try {
            if (archive.length <= HEADER_LENGTH + GCM_TAG_BITS / 8) {
                throw new GeneralSecurityException("Invalid synthetic archive");
            }
            byte[] header = Arrays.copyOfRange(archive, 0, HEADER_LENGTH);
            if (!Arrays.equals(Arrays.copyOfRange(header, 0, MAGIC.length), MAGIC)) {
                throw new GeneralSecurityException("Unknown synthetic archive format");
            }
            byte[] salt = Arrays.copyOfRange(header, MAGIC.length, MAGIC.length + SALT_LENGTH);
            byte[] nonce = Arrays.copyOfRange(header, MAGIC.length + SALT_LENGTH, HEADER_LENGTH);
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce);
            cipher.updateAAD(header);
            // GCM doFinal authenticates before releasing bytes to the output.
            plain = cipher.doFinal(archive, HEADER_LENGTH, archive.length - HEADER_LENGTH);
            requireFixtureMarker(plain);
            createNewFile(newOutput, plain);
        } finally {
            Arrays.fill(archive, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] boundedRead(Path path, int maximum) throws IOException {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > maximum) {
            throw new IOException("File is not a bounded, regular synthetic proof input");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maximum) {
            Arrays.fill(bytes, (byte) 0);
            throw new IOException("Synthetic proof file exceeds maximum size");
        }
        return bytes;
    }

    private static void requireFixtureMarker(byte[] bytes) throws GeneralSecurityException {
        if (bytes.length < MARKER_BYTES.length) {
            throw new GeneralSecurityException("Not a synthetic fixture");
        }
        for (int i = 0; i < MARKER_BYTES.length; i++) {
            if (bytes[i] != MARKER_BYTES[i]) {
                throw new GeneralSecurityException("Not a synthetic fixture");
            }
        }
    }

    private static void checkPassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < 12 || passphrase.length > 256) {
            throw new IllegalArgumentException("Synthetic archive passphrase is missing or invalid");
        }
    }

    private static Cipher newCipher(int mode, char[] password, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, 256);
        byte[] keyBytes = null;
        try {
            SecretKeyFactory kdf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey key = kdf.generateSecret(spec);
            keyBytes = key.getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher;
        } finally {
            spec.clearPassword();
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * Fail if output already exists. On failed partial write, clean ONLY
     * the file created by this invocation. This is not a crash-safe PG restore.
     */
    private static void createNewFile(Path path, byte[] bytes) throws IOException {
        if (path == null || path.getParent() == null || !Files.isDirectory(path.getParent())) {
            throw new IOException("Synthetic output requires an existing destination directory");
        }
        boolean created = false;
        try (OutputStream output = Files.newOutputStream(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            output.write(bytes);
            output.flush();
        } catch (IOException error) {
            if (created) {
                try { Files.deleteIfExists(path); }
                catch (IOException cleanupFailure) { error.addSuppressed(cleanupFailure); }
            }
            throw error;
        }
    }
}
