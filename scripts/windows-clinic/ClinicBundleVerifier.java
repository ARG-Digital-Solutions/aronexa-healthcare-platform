import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * COMM-V1-T10: proves packaging of REAL Spring backend and React build artifacts.
 *
 * This launcher does NOT start the backend, bind a port, open a clinic database,
 * enroll users or provide patient care. All real features remain gated.
 */
public final class ClinicBundleVerifier {
    private ClinicBundleVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("This proof does not accept database or clinic arguments");
        }
        Path appDir = jarDirectory();
        Path backend = appDir.resolve("aronexa-backend.jar");
        Path frontend = appDir.resolve("frontend");
        Path index = frontend.resolve("index.html");
        Path assets = frontend.resolve("assets");

        if (!Files.isRegularFile(backend, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Incomplete clinic application bundle; fail closed");
        }
        if (Files.size(backend) < 1_000_000 || Files.size(index) == 0) {
            throw new IOException("Invalid backend/frontend build artifact");
        }
        try (ZipFile springJar = new ZipFile(backend.toFile())) {
            if (springJar.getEntry("BOOT-INF/classes/com/argdigital/aronexa/AronexaApplication.class") == null
                    || springJar.stream().noneMatch(e -> e.getName().startsWith("BOOT-INF/lib/"))) {
                throw new IOException("Backend jar is not an executable Spring Boot bundle");
            }
        }
        String indexText = Files.readString(index);
        if (!indexText.contains("AroNexa") || !indexText.contains("/assets/")) {
            throw new IOException("Frontend is not the compiled AroNexa Vite bundle");
        }
        try (var listing = Files.list(assets)) {
            if (listing.noneMatch(path -> path.getFileName().toString().endsWith(".js"))) {
                throw new IOException("Compiled frontend assets missing");
            }
        }
        System.out.println("AroNexa Clinic EDITION BUILD PROOF: bundled Spring Boot JAR PASS");
        System.out.println("AroNexa Clinic EDITION BUILD PROOF: bundled compiled React UI PASS");
        System.out.println("AroNexa Clinic EDITION BUILD PROOF: bundled Java launcher PASS");
        System.out.println("NO clinical data, authentication, installed database, server, API or customer release started.");
    }

    private static Path jarDirectory() throws URISyntaxException, IOException {
        Path jar = Path.of(ClinicBundleVerifier.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Clinic bundle verifier must run from its packaged launcher jar");
        }
        return jar.toAbsolutePath().getParent();
    }
}
