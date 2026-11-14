import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * WIN-10 isolated encrypted-backup primitive (Java 21, pure JDK).
 *
 * Internal synthetic-only PostgreSQL dump rehearsal. NOT a production
 * clinical backup application, issuer, KMS, scheduler or disaster-recovery
 * protocol. Passphrases are read from the interactive console, never from
 * command arguments, environment variables or persistent files.
 *
 * AES-256-GCM with 128-bit tag; per-backup random 128-bit salt and 96-bit
 * nonce; 450,000 PBKDF2-HMAC-SHA256 iterations; authenticated fixed header.
 * Decryption writes to a private temporary sibling and publishes output
 * only after verifying the AEAD tag. No overwrites or symlink inputs.
 */
public final class ClinicBackupCrypto {
    private static final byte[] MAGIC = "ARONEXA1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int ITERATIONS = 450_000;
    private static final int SALT_SIZE = 16;
    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_SIZE = MAGIC.length + 1 + 4 + SALT_SIZE + NONCE_SIZE;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClinicBackupCrypto() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "selftest".equals(args[0])) {
                selftest();
                System.out.println("WIN10 AES256-GCM WRONG-PASSWORD AND TAMPER SELFTEST PASSED");
                return;
            }
            if (args.length < 3 || args.length > 4) {
                throw new IllegalArgumentException("Use encrypt INPUT OUTPUT, decrypt INPUT OUTPUT, "
                        + "roundtrip INPUT ENCRYPTED_OUTPUT RESTORED_OUTPUT, or selftest.");
            }
            String mode = args[0];
            if (!"encrypt".equals(mode) && !"decrypt".equals(mode)
                    && !"roundtrip".equals(mode)) {
                throw new IllegalArgumentException("Unsupported backup operation");
            }
            if (("roundtrip".equals(mode) && args.length != 4)
                    || (!"roundtrip".equals(mode) && args.length != 3)) {
                throw new IllegalArgumentException("Wrong number of file paths");
            }
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException("An attached interactive console is required; "
                        + "no environment or command-line passphrases are accepted.");
            }
            char[] passphrase = console.readPassword("Synthetic-backup passphrase (16+ chars): ");
            if (passphrase == null || passphrase.length < 16
                    || passphrase.length > 1024) {
                throw new IllegalArgumentException("Passphrase length must be 16..1024 characters");
            }
            try {
                Path input = Path.of(args[1]);
                Path output = Path.of(args[2]);
                if ("encrypt".equals(mode)) {
                    encrypt(input, output, passphrase);
                    System.out.println("WIN10_BACKUP_ENCRYPTED");
                } else if ("decrypt".equals(mode)) {
                    decrypt(input, output, passphrase);
                    System.out.println("WIN10_BACKUP_DECRYPTED_AND_AUTHENTICATED");
                } else {
                    Path restored = Path.of(args[3]);
                    encrypt(input, output, passphrase);
                    decrypt(output, restored, passphrase);
                    if (!MessageDigest.isEqual(digest(input), digest(restored))) {
                        throw new IOException("Roundtrip digest mismatch");
                    }
                    System.out.println("WIN10_BACKUP_ENCRYPTED_RESTORED_SHA256_MATCH");
                }
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        } catch (Exception error) {
            // Never include passphrases, decrypted content or SQL query data.
            System.err.println("WIN10_BACKUP_OPERATION_FAILED (check source, input, "
                    + "integrity, passphrase and output permissions)");
            System.exit(1);
        }
    }

    static void encrypt(Path input, Path output, char[] passphrase)
            throws Exception {
        Path source = validInput(input);
        Path target = validOutput(source, output);
        byte[] salt = new byte[SALT_SIZE], nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER_SIZE)
                .put(MAGIC).put((byte) VERSION).putInt(ITERATIONS)
                .put(salt).put(nonce).array();
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce, header);
        Path temporary = Files.createTempFile(target.getParent(), ".aronexa-backup-", ".partial");
        try {
            try (InputStream plain = Files.newInputStream(source);
                 OutputStream raw = Files.newOutputStream(temporary,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                raw.write(header);
                try (CipherOutputStream encrypted = new CipherOutputStream(raw, cipher)) {
                    plain.transferTo(encrypted);
                }
            }
            publish(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    static void decrypt(Path input, Path output, char[] passphrase)
            throws Exception {
        Path source = validInput(input);
        if (Files.size(source) < HEADER_SIZE + TAG_BITS / 8) {
            throw new IOException("Invalid backup size");
        }
        Path target = validOutput(source, output);
        Path temporary = Files.createTempFile(target.getParent(), ".aronexa-restore-", ".partial");
        try {
            try (InputStream encrypted = Files.newInputStream(source)) {
                byte[] header = encrypted.readNBytes(HEADER_SIZE);
                if (header.length != HEADER_SIZE) throw new IOException("Truncated header");
                ByteBuffer data = ByteBuffer.wrap(header);
                byte[] magic = new byte[MAGIC.length];
                data.get(magic);
                if (!MessageDigest.isEqual(magic, MAGIC)
                        || Byte.toUnsignedInt(data.get()) != VERSION
                        || data.getInt() != ITERATIONS) {
                    throw new IOException("Unrecognized backup format");
                }
                byte[] salt = new byte[SALT_SIZE], nonce = new byte[NONCE_SIZE];
                data.get(salt);
                data.get(nonce);
                try {
                    Cipher cipher = cipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce, header);
                    try (CipherInputStream clear = new CipherInputStream(encrypted, cipher);
                         OutputStream restored = Files.newOutputStream(temporary,
                                 StandardOpenOption.TRUNCATE_EXISTING)) {
                        clear.transferTo(restored);
                    }
                } finally {
                    Arrays.fill(salt, (byte) 0);
                    Arrays.fill(nonce, (byte) 0);
                }
            }
            // Authentication succeeded before plaintext is made visible at target.
            publish(temporary, target);
        } finally {
            // A failed AEAD tag can leave partial plaintext ONLY in this
            // private temporary file, which is always deleted on failure.
            Files.deleteIfExists(temporary);
        }
    }

    private static Cipher cipher(int mode, char[] passphrase, byte[] salt,
                                 byte[] nonce, byte[] header) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            Cipher result = Cipher.getInstance("AES/GCM/NoPadding");
            result.init(mode, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            result.updateAAD(header);
            return result;
        } finally {
            spec.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static Path validInput(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) == 0) {
            throw new IOException("Backup input is empty, missing or a symlink");
        }
        return path;
    }

    private static Path validOutput(Path source, Path output) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (target.equals(source) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || !Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target.getParent())) {
            throw new IOException("Backup output must be new in an existing ordinary folder");
        }
        return target;
    }

    private static void publish(Path temporary, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite existing backup output");
        }
        Files.move(temporary, target);
    }

    private static byte[] digest(Path file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) sha.update(buffer, 0, n);
            Arrays.fill(buffer, (byte) 0);
        }
        return sha.digest();
    }

    private static void selftest() throws Exception {
        Path dir = Files.createTempDirectory("aronexa-win10-crypto-");
        char[] password = "synthetic-crypto-proof-not-clinic-123".toCharArray();
        char[] bad = "definitely-not-the-valid-secret".toCharArray();
        try {
            Path data = dir.resolve("fictional.dump");
            byte[] generated = new byte[32_768];
            RANDOM.nextBytes(generated);
            Files.write(data, generated, StandardOpenOption.CREATE_NEW);
            Arrays.fill(generated, (byte) 0);
            Path sealed = dir.resolve("fictional.axenc");
            encrypt(data, sealed, password);
            Path restored = dir.resolve("restored.dump");
            decrypt(sealed, restored, password);
            if (!MessageDigest.isEqual(digest(data), digest(restored))) {
                throw new AssertionError("AES-GCM roundtrip differs");
            }
            if (Files.exists(dir.resolve("bad-password.dump"))) {
                throw new AssertionError("Unexpected test target");
            }
            boolean denied = false;
            try {
                decrypt(sealed, dir.resolve("bad-password.dump"), bad);
            } catch (IOException | GeneralSecurityException failure) {
                denied = true;
            }
            if (!denied || Files.exists(dir.resolve("bad-password.dump"))) {
                throw new AssertionError("Wrong password disclosed plaintext");
            }
            Path tampered = dir.resolve("tampered.axenc");
            Files.copy(sealed, tampered, StandardCopyOption.COPY_ATTRIBUTES);
            try (var file = new java.io.RandomAccessFile(tampered.toFile(), "rw")) {
                long last = file.length() - 1;
                file.seek(last);
                int value = file.read();
                file.seek(last);
                file.write(value ^ 1);
            }
            denied = false;
            try {
                decrypt(tampered, dir.resolve("tampered.dump"), password);
            } catch (IOException | GeneralSecurityException failure) {
                denied = true;
            }
            if (!denied || Files.exists(dir.resolve("tampered.dump"))) {
                throw new AssertionError("Tamper was not rejected");
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(bad, '\0');
            try (var paths = Files.list(dir)) {
                for (Path file : paths.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }
}
