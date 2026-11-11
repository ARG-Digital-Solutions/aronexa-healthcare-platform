import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * COMM-V1-T12 — STATIC, FOUNDATION-ONLY Clinic UI preview, no clinical APIs.
 *
 * No server-side user data, bearer-token handling, DB, Spring Boot process,
 * patient record, prescription, financial posting or external connectivity.
 *
 * Runtime only serves one public index.html and filenames in /assets from
 * the staged compiled Vite build, bound exclusively to 127.0.0.1.
 * This is NOT a production application authentication boundary.
 */
public final class ClinicLoopbackPreview {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int MAX_ASSET_BYTES = 10 * 1024 * 1024;
    private static final String CSP = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'none'; form-action 'none'; base-uri 'none'; "
            + "frame-ancestors 'none'";

    private ClinicLoopbackPreview() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("--selftest".equals(args[0]) || "--serve".equals(args[0]))) {
            throw new IllegalArgumentException(
                    "Use --selftest or --serve only. Never pass clinic DB paths or credentials.");
        }
        // Check the actual executable Spring Boot artifact and compiled React assets
        // are bundled; this does NOT run the clinical backend.
        ClinicBundleVerifier.main(new String[0]);
        Path appDir = ClinicBundleVerifier.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI() == null ? null
                : Path.of(ClinicBundleVerifier.class.getProtectionDomain()
                  .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
        if (appDir == null) throw new IOException("Missing application image path");
        Path frontend = appDir.resolve("frontend");
        if (!Files.isRegularFile(frontend.resolve("index.html"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(frontend.resolve("assets"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing compiled frontend assets");
        }
        InetAddress address = InetAddress.getByName(LOOPBACK);
        if (!address.isLoopbackAddress()) throw new SecurityException("Refusing non-loopback bind");
        HttpServer server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        if (!server.getAddress().getAddress().isLoopbackAddress()) {
            server.stop(0);
            throw new SecurityException("Refusing non-loopback listener");
        }
        server.createContext("/", exchange -> handle(exchange, frontend));
        server.start();
        try {
            int port = server.getAddress().getPort();
            if ("--selftest".equals(args[0])) {
                selftest(port, frontend);
                System.out.println("WINDOWS LOCAL UI PREVIEW: GET/HEAD + deny API/traversal/POST PASS");
                System.out.println("BOUND ONLY TO 127.0.0.1; no network/API/clinical backend started.");
                return;
            }
            System.out.println("AroNexa FOUNDATION UI (not clinical software) on http://127.0.0.1:"
                    + port + "/");
            System.out.println("No database, patient records, clinical APIs or login are available.");
            System.out.println("Press Ctrl+C to stop; this proof opens no browser automatically.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            new CountDownLatch(1).await();
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, Path frontend) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
            exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
            String method = exchange.getRequestMethod();
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.contains("\\") || path.contains("\u0000")
                    || path.contains("//") || path.contains("..")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Path target;
            String type;
            if ("/".equals(path) || "/index.html".equals(path)) {
                target = frontend.resolve("index.html");
                type = "text/html; charset=utf-8";
            } else if (path.startsWith("/assets/")) {
                String filename = path.substring("/assets/".length());
                if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
                        || filename.startsWith(".") || filename.contains("..")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                target = frontend.resolve("assets").resolve(filename);
                if (filename.endsWith(".js")) type = "text/javascript; charset=utf-8";
                else if (filename.endsWith(".css")) type = "text/css; charset=utf-8";
                else if (filename.endsWith(".svg")) type = "image/svg+xml";
                else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
            } else {
                // Critical: no SPA fallback to any /api, auth, clinical or
                // filesystem paths. 404 instead of exposing data or code.
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) > MAX_ASSET_BYTES) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", type);
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void selftest(int port, Path frontend) throws Exception {
        assertResponse(port, "GET", "/", 200, "text/html", true);
        assertResponse(port, "HEAD", "/index.html", 200, "text/html", true);
        try (var children = Files.list(frontend.resolve("assets"))) {
            String javascript = children.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".js")).findFirst()
                    .orElseThrow(() -> new IOException("Compiled JavaScript missing"));
            assertResponse(port, "GET", "/assets/" + javascript, 200, "text/javascript", true);
        }
        for (String denied : new String[] {
                "/api/v1/auth/login", "/api/v1/patients", "/api/v1/encounters",
                "/.env", "/backend.jar", "/assets/../../backend.jar",
                "/assets/%2e%2e%2fbackend.jar", "/assets/fake.js", "/unknown"
        }) {
            assertResponse(port, "GET", denied, 404, null, false);
        }
        assertResponse(port, "POST", "/", 405, null, false);
        assertResponse(port, "POST", "/api/v1/auth/login", 405, null, false);
    }

    private static void assertResponse(int port, String method, String uri, int expected,
                                       String expectedType, boolean checkHeaders) throws Exception {
        URL url = new URL("http://" + LOOPBACK + ":" + port + uri);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod(method);
            int code = connection.getResponseCode();
            if (code != expected) {
                throw new IllegalStateException(method + " " + uri + " expected " + expected
                        + " but received " + code);
            }
            if (expectedType != null && (connection.getHeaderField("Content-Type") == null
                    || !connection.getHeaderField("Content-Type").startsWith(expectedType))) {
                throw new IllegalStateException("Content type denied for " + uri);
            }
            if (checkHeaders && (!"no-store".equals(connection.getHeaderField("Cache-Control"))
                    || !"nosniff".equals(connection.getHeaderField("X-Content-Type-Options"))
                    || connection.getHeaderField("Content-Security-Policy") == null
                    || !connection.getHeaderField("Content-Security-Policy")
                        .contains("connect-src 'none'"))) {
                throw new IllegalStateException("Static proof missing safe response headers");
            }
        } finally {
            connection.disconnect();
        }
    }
}
