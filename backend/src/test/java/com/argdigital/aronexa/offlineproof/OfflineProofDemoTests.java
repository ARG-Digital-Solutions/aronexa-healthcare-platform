package com.argdigital.aronexa.offlineproof;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLI is synthetic and refuses all external paths/arguments. */
class OfflineProofDemoTests {
    @Test void standaloneMainCompletesCryptographicSelfCheck() throws Exception {
        var original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            OfflineProofDemo.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        String report = output.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("PROOF", "signed synthetic licence: PASS",
                "encrypted synthetic fixture restore: PASS");
        assertThat(report).doesNotContain("PRIVATE KEY", "patient name=");
    }

    @Test void refusesAllExternalSourcePaths() {
        assertThatThrownBy(() -> OfflineProofDemo.main(new String[]{"C:\\patient-data"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no clinic paths");
    }
}
