import java.io.IOException;
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
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * COMM-V1-T11: JDK21 test-only encryption of a small disposable pg_dump.
 * NOT a reusable patient backup tool; never part of the clinic runtime.
 * Decrypts completely before writing output so invalid GCM tags never
 * expose partly authenticated data.
 */
public final class DisposablePgArchiveCrypto {
    private static final byte[] MAGIC = "ARONEXA-PG-PROOF-1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PG_MAGIC = "PGDMP".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int SALT = 16, NONCE = 12, TAG_BITS = 128, ITERATIONS = 250_000;
    private static final int HEADER = MAGIC.length + SALT + NONCE;
    private DisposablePgArchiveCrypto() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !("encrypt".equals(args[0]) || "decrypt".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: encrypt|decrypt SOURCE NEW_DESTINATION");
        }
        if (!"DISPOSABLE-CONTAINER-ONLY".equals(System.getenv("ARONEXA_RECOVERY_PROOF_MODE"))) {
            throw new SecurityException("This source must only run in the disposable proof");
        }
        String secret = System.getenv("ARONEXA_RECOVERY_PROOF_PASSWORD");
        if (secret == null || secret.length() < 24 || secret.length() > 256) {
            throw new GeneralSecurityException("Missing synthetic recovery key");
        }
        char[] password = secret.toCharArray();
        Path source = Path.of(args[1]).toAbsolutePath();
        Path dest = Path.of(args[2]).toAbsolutePath();
        byte[] input = null, output = null;
        try {
            if (source.equals(dest) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dest) || Files.exists(dest, LinkOption.NOFOLLOW_LINKS)
                || dest.getParent() == null || !Files.isDirectory(dest.getParent())) {
                throw new IOException("Refusing unsafe disposable file paths");
            }
            if (Files.size(source) == 0 || Files.size(source) > MAX_BYTES + HEADER + 16L) {
                throw new IOException("Disposable archive size limit exceeded");
            }
            input = Files.readAllBytes(source);
            if (input.length > MAX_BYTES + HEADER + 16) throw new IOException("Archive size invalid");
            output = "encrypt".equals(args[0]) ? encrypt(input,password) : decrypt(input,password);
            Files.write(dest, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("Disposable authenticated archive " + args[0] + ": PASS");
        } finally {
            Arrays.fill(password, '\0');
            if (input != null) Arrays.fill(input, (byte)0);
            if (output != null) Arrays.fill(output, (byte)0);
        }
    }

    private static void pgMagic(byte[] data) throws GeneralSecurityException {
        if (data.length < PG_MAGIC.length || data.length > MAX_BYTES) {
            throw new GeneralSecurityException("Not a small custom pg_dump archive");
        }
        for (int i=0;i<PG_MAGIC.length;i++) {
            if (data[i] != PG_MAGIC[i]) throw new GeneralSecurityException("Archive magic mismatch");
        }
    }

    private static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        pgMagic(plain);
        byte[] salt = new byte[SALT], nonce = new byte[NONCE];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER).put(MAGIC).put(salt).put(nonce).array();
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce);
        cipher.updateAAD(header);
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(HEADER + encrypted.length).put(header).put(encrypted).array();
    }

    private static byte[] decrypt(byte[] archive, char[] password) throws GeneralSecurityException {
        if (archive.length < HEADER + 17 || archive.length > MAX_BYTES + HEADER + 16) {
            throw new GeneralSecurityException("Malformed archive envelope");
        }
        byte[] header = Arrays.copyOfRange(archive,0,HEADER);
        byte[] actualMagic = Arrays.copyOfRange(header,0,MAGIC.length);
        if (!Arrays.equals(actualMagic, MAGIC)) throw new GeneralSecurityException("Unknown archive");
        byte[] salt = Arrays.copyOfRange(header,MAGIC.length,MAGIC.length+SALT);
        byte[] nonce = Arrays.copyOfRange(header,MAGIC.length+SALT,HEADER);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE,password,salt,nonce);
        cipher.updateAAD(header);
        byte[] plain = cipher.doFinal(archive,HEADER,archive.length-HEADER);
        try { pgMagic(plain); return plain; }
        catch (GeneralSecurityException e) { Arrays.fill(plain,(byte)0); throw e; }
    }

    private static Cipher cipher(int mode,char[] password,byte[] salt,byte[] nonce)
            throws GeneralSecurityException {
        PBEKeySpec keySpec = new PBEKeySpec(password,salt,ITERATIONS,256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(TAG_BITS,nonce));
            return cipher;
        } finally {
            keySpec.clearPassword();
            if(key!=null) Arrays.fill(key,(byte)0);
        }
    }
}
