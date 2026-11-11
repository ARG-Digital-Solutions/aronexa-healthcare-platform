package com.argdigital.aronexa.offlineproof;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tiny fictional files only; never a PostgreSQL or patient backup. */
class SyntheticArchiveProofTests {
    @TempDir Path temp;
    private static final String PASSWORD = "synthetic-proof-password-long";

    private Path input() throws Exception {
        Path source = temp.resolve("fictional-source.txt");
        Files.writeString(source, SyntheticArchiveProof.FIXTURE_MARKER
                + "FICTIONAL VISIT - NO PATIENT DATA\n", StandardCharsets.UTF_8);
        return source;
    }
    private static char[] secret() { return PASSWORD.toCharArray(); }

    @Test void encryptedFixtureRestoresByteForByte() throws Exception {
        Path source = input(), encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(source, encrypted, secret());
        assertThat(new String(Files.readAllBytes(encrypted), StandardCharsets.ISO_8859_1))
                .doesNotContain("FICTIONAL VISIT");
        SyntheticArchiveProof.restoreFixture(encrypted, restored, secret());
        assertThat(Files.readAllBytes(restored)).isEqualTo(Files.readAllBytes(source));
    }

    @Test void wrongPasswordRejectsRestoreWithoutAnyOutput() throws Exception {
        Path encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(
                encrypted, restored, "wrong-passphrase-for-demo".toCharArray()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(restored).doesNotExist();
    }

    @Test void ciphertextTamperingFailsAuthentication() throws Exception {
        Path encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        byte[] data = Files.readAllBytes(encrypted);
        data[data.length - 2] ^= 0x01;
        Files.write(encrypted, data);
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(encrypted, restored, secret()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(restored).doesNotExist();
    }

    @Test void saltTamperingFailsAuthentication() throws Exception {
        Path encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        byte[] data = Files.readAllBytes(encrypted);
        data[11] ^= 0x01;
        Files.write(encrypted, data);
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(encrypted, restored, secret()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(restored).doesNotExist();
    }

    @Test void truncatedEncryptedFixtureRejectedWithoutOutput() throws Exception {
        Path encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        byte[] data = Files.readAllBytes(encrypted);
        Files.write(encrypted, Arrays.copyOf(data, data.length - 8));
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(encrypted, restored, secret()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(restored).doesNotExist();
    }

    @Test void unsupportedMagicIsRejected() throws Exception {
        Path encrypted = temp.resolve("proof.enc");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        byte[] data = Files.readAllBytes(encrypted);
        data[0] ^= 0x01;
        Files.write(encrypted, data);
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(
                encrypted, temp.resolve("recovered.txt"), secret()))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test void rejectsRealOrUnmarkedContentAtEncryptionBoundary() throws Exception {
        Path unmarked = temp.resolve("other.txt");
        Files.writeString(unmarked, "Not a synthetic fixture, do not encrypt PHI");
        assertThatThrownBy(() -> SyntheticArchiveProof.encryptFixture(
                unmarked, temp.resolve("proof.enc"), secret()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(temp.resolve("proof.enc")).doesNotExist();
    }

    @Test void rejectsOversizedFilesInsteadOfProcessingRealArchives() throws Exception {
        Path huge = temp.resolve("oversize.txt");
        byte[] oversized = new byte[1_048_577];
        byte[] marker = SyntheticArchiveProof.FIXTURE_MARKER.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, oversized, 0, marker.length);
        Files.write(huge, oversized);
        assertThatThrownBy(() -> SyntheticArchiveProof.encryptFixture(
                huge, temp.resolve("proof.enc"), secret()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void encryptionDoesNotOverwriteExistingDestination() throws Exception {
        Path output = temp.resolve("proof.enc");
        Files.writeString(output, "KEEP-EXISTING");
        assertThatThrownBy(() -> SyntheticArchiveProof.encryptFixture(input(), output, secret()))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(output)).isEqualTo("KEEP-EXISTING");
    }

    @Test void restorationDoesNotOverwriteExistingOutput() throws Exception {
        Path encrypted = temp.resolve("proof.enc"), restored = temp.resolve("restored.txt");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        Files.writeString(restored, "KEEP-EXISTING");
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(encrypted, restored, secret()))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(restored)).isEqualTo("KEEP-EXISTING");
    }

    @Test void encryptionRequiresLongPassphrase() throws Exception {
        assertThatThrownBy(() -> SyntheticArchiveProof.encryptFixture(
                input(), temp.resolve("proof.enc"), "short".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void restoreRequiresAnExistingParentDirectory() throws Exception {
        Path encrypted = temp.resolve("proof.enc");
        SyntheticArchiveProof.encryptFixture(input(), encrypted, secret());
        assertThatThrownBy(() -> SyntheticArchiveProof.restoreFixture(
                encrypted, temp.resolve("not-created/restored.txt"), secret()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void encryptedOutputIsRandomizedWithUniqueSaltAndNonce() throws Exception {
        Path source = input(), first = temp.resolve("first.enc"), second = temp.resolve("second.enc");
        SyntheticArchiveProof.encryptFixture(source, first, secret());
        SyntheticArchiveProof.encryptFixture(source, second, secret());
        assertThat(Files.readAllBytes(first)).isNotEqualTo(Files.readAllBytes(second));
    }
}
