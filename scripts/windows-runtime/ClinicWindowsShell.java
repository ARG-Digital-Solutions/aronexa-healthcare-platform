import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * WIN-11 native Windows app-image ENTRYPOINT: secure packaging and localhost
 * UI shell only. It intentionally NEVER launches Spring Boot, migrations,
 * patient search, prescriptions, billing, or a database. An actual clinical
 * runtime must be separately security-reviewed and explicitly enabled.
 */
public final class ClinicWindowsShell {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int PORT = 8766;
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final String CSP = "default-src 'none'; script-src 'self'; "
        + "style-src 'self'; img-src 'self' data:; font-src 'self'; "
        + "connect-src 'none'; form-action 'none'; base-uri 'none'; "
        + "frame-ancestors 'none'";

    private ClinicWindowsShell() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!"--selftest".equals(args[0])
                && !"--serve".equals(args[0]))) {
            throw new IllegalArgumentException("Use --selftest or --serve; no database arguments accepted");
        }
        Path root = Path.of(ClinicWindowsShell.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath()
                .normalize().getParent();
        verifyBundle(root);
        InetAddress address = InetAddress.getByName(LOOPBACK);
        if (!address.isLoopbackAddress()) {
            throw new SecurityException("Refusing non-loopback binding");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(address,
                "--selftest".equals(args[0]) ? 0 : PORT), 0);
        if (!server.getAddress().getAddress().isLoopbackAddress()) {
            server.stop(0);
            throw new SecurityException("Refusing non-loopback listener");
        }
        server.createContext("/", exchange -> handle(exchange, root));
        server.start();
        try {
            if ("--selftest".equals(args[0])) {
                selftest(server.getAddress().getPort());
                System.out.println("WIN11_APPIMAGE_LOOPBACK_AND_API_DENIAL_SELFTEST_PASS");
                return;
            }
            System.out.println("WIN11_WINDOWS_SHELL_RUNNING http://127.0.0.1:" + PORT + "/");
            System.out.println("WIN11_CLINICAL_APIS_DISABLED_NO_PATIENT_DATA");
            System.out.println("No database or clinical server is started; press Ctrl+C to exit.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            new CountDownLatch(1).await();
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, Path root) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", CSP);
            headers.set("Cross-Origin-Resource-Policy", "same-origin");
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                headers.set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (path == null || path.indexOf('\\') >= 0
                    || path.contains("%") || path.contains("..")
                    || path.contains("//") || path.indexOf('\0') >= 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (path.equals("/api") || path.startsWith("/api/")) {
                headers.set("Content-Type", "application/json; charset=utf-8");
                send(exchange, method, 503,
                    "{\"status\":\"CLINICAL_API_NOT_AUTHORIZED\",\"ready\":false}"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/healthz")) {
                headers.set("Content-Type", "application/json; charset=utf-8");
                send(exchange, method, 200,
                    "{\"mode\":\"WINDOWS_STATIC_SHELL_ONLY\",\"clinicalReady\":false}"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file;
            String contentType;
            if (path.equals("/") || path.equals("/index.html")) {
                file = root.resolve("index.html");
                contentType = "text/html; charset=utf-8";
            } else if (path.equals("/connected-reception-preview.html")) {
                file = root.resolve("connected-reception-preview.html");
                contentType = "text/html; charset=utf-8";
            } else if (path.startsWith("/assets/")) {
                String name = path.substring("/assets/".length());
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        || name.startsWith(".") || name.contains("..")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                file = root.resolve("assets").resolve(name);
                if (name.endsWith(".js")) contentType = "text/javascript; charset=utf-8";
                else if (name.endsWith(".css")) contentType = "text/css; charset=utf-8";
                else if (name.endsWith(".svg")) contentType = "image/svg+xml";
                else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) == 0 || Files.size(file) > MAX_BYTES) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            headers.set("Content-Type", contentType);
            send(exchange, method, 200, Files.readAllBytes(file));
        }
    }

    private static void send(HttpExchange exchange, String method,
                             int status, byte[] bytes) throws IOException {
        try {
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void verifyBundle(Path root) throws Exception {
        Path manifest = root.resolve("WIN11-SHA256.tsv");
        if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing Windows shell integrity manifest");
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() < 4 || lines.size() > 500) {
            throw new IOException("Unexpected shell inventory size");
        }
        List<String> seen = new ArrayList<>();
        for (String line : lines) {
            String[] entry = line.split("\t", -1);
            if (entry.length != 2 || !entry[0].matches("[0-9A-F]{64}")
                    || !entry[1].matches("[A-Za-z0-9/._-]{1,160}")
                    || entry[1].startsWith("/") || entry[1].contains("..")
                    || seen.contains(entry[1])) {
                throw new IOException("Invalid bundle manifest entry");
            }
            seen.add(entry[1]);
            Path file = root.resolve(entry[1]).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing or linked app-image input");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                Arrays.fill(buffer, (byte) 0);
            }
            byte[] hash = digest.digest();
            if (!MessageDigest.isEqual(
                    java.util.HexFormat.of().formatHex(hash).toUpperCase()
                        .getBytes(StandardCharsets.US_ASCII),
                    entry[0].getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("App-image input hash mismatch");
            }
            Arrays.fill(hash, (byte) 0);
        }
        if (!seen.contains("WIN11_SOURCE_SHA.txt")
                || !Files.readString(root.resolve("WIN11_SOURCE_SHA.txt"))
                    .trim().matches("[0-9a-f]{40}")
                || !seen.contains("aronexa-backend.jar")
                || !seen.contains("index.html")
                || !seen.contains("connected-reception-preview.html")
                || seen.stream().noneMatch(x -> x.startsWith("assets/")
                        && x.endsWith(".js"))) {
            throw new IOException("Incomplete app-image inventory");
        }
    }

    private static void selftest(int port) throws Exception {
        try (var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build()) {
            testRequest(client, port, "GET", "/", 200);
            testRequest(client, port, "GET", "/connected-reception-preview.html", 200);
            testRequest(client, port, "GET", "/healthz", 200);
            testRequest(client, port, "GET", "/api/v1/auth/login", 503);
            testRequest(client, port, "POST", "/api/v1/auth/login", 405);
            testRequest(client, port, "GET", "/.env", 404);
            testRequest(client, port, "GET", "/aronexa-backend.jar", 404);
        }
    }

    private static void testRequest(java.net.http.HttpClient client,
                                    int port, String method,
                                    String path, int expected) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://" + LOOPBACK + ":" + port + path))
            .timeout(java.time.Duration.ofSeconds(5))
            .method(method, method.equals("POST")
                ? java.net.http.HttpRequest.BodyPublishers.ofString("{}")
                : java.net.http.HttpRequest.BodyPublishers.noBody())
            .build();
        var response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected
                || !response.headers().firstValue("Cache-Control")
                    .orElse("").equals("no-store")
                || response.headers().firstValue("X-Content-Type-Options")
                    .isEmpty()) {
            throw new IOException("Local shell API/path isolation selftest failed");
        }
    }
}
