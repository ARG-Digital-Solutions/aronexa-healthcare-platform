package com.argdigital.aronexa.offlineproof;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Synthetic-only, self-contained main class for a Windows jpackage APP IMAGE.
 * This class deliberately has no Spring Boot entrypoint or SQL dependencies.
 * It refuses external filesystem/database arguments and prints no secrets.
 *
 * JDK test keys are ephemeral and created in RAM for a cryptographic roundtrip,
 * NOT a legitimate product issuer or activation mechanism.
 */
public final class OfflineProofDemo {
    private OfflineProofDemo() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException(
                    "Synthetic proof accepts no clinic paths, patient files or arguments");
        }
        System.out.println("AroNexa Windows Offline PROOF — not clinic software");

        KeyPair issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = OfflineLicenseProof.canonicalPayload(
                "FICTIONAL-CLINIC-01", LocalDate.now().plusDays(30));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(issuer.getPrivate());
        signer.update(payload);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        var decision = OfflineLicenseProof.verify(
                token, "FICTIONAL-CLINIC-01", issuer.getPublic(), Clock.systemDefaultZone());
        if (decision.status() != OfflineLicenseProof.Status.ACTIVE) {
            throw new IllegalStateException("Ephemeral signature proof failed");
        }
        System.out.println("Ed25519 signed synthetic licence: PASS (ephemeral issuer only)");

        Path folder = Files.createTempDirectory("aronexa-synthetic-offline-");
        Path source = folder.resolve("fictional.txt");
        Path archive = folder.resolve("fictional.encrypted");
        Path restored = folder.resolve("fictional-restored.txt");
        char[] password = ("synthetic-proof-" + UUID.randomUUID()).toCharArray();
        try {
            Files.writeString(source, SyntheticArchiveProof.FIXTURE_MARKER
                    + "FICTIONAL DATA ONLY - NO PATIENT INFORMATION\n",
                    StandardCharsets.UTF_8);
            SyntheticArchiveProof.encryptFixture(source, archive, password);
            SyntheticArchiveProof.restoreFixture(archive, restored, password);
            if (!Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(restored))) {
                throw new IllegalStateException("Synthetic recovery did not match original");
            }
            System.out.println("AES-GCM encrypted synthetic fixture restore: PASS");
            System.out.println("No PostgreSQL database, installer, PHI, activation or cloud connection used.");
        } finally {
            Arrays.fill(password, '\0');
            Files.deleteIfExists(restored);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(source);
            Files.deleteIfExists(folder);
        }
    }
}
