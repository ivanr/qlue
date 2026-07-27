package com.webkreator.qlue.view.canoe.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webkreator.qlue.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The origin the browser tier serves rendered cases from (T25).
 *
 * <p>A loopback {@link HttpServer} on an ephemeral port, started per test class and torn down after.
 * {@code file://} is deliberately not used: meta refresh, form submission, {@code <base href>} and
 * same-origin semantics all behave differently there, and every one of those is a vector the browser
 * tier exists to check. A {@code file://} page has an opaque origin in every current engine, so
 * "the page's own origin" — the thing {@code VerdictEvaluator} judges a URL against — would not
 * exist at all.
 *
 * <p>Rendered cases are served from {@code /case/{id}} with
 * {@link View#CONTENT_TYPE_TEXT_HTML_UTF8}, referenced from production rather than copied, so the
 * browser parses the bytes under the same content type and charset a real Qlue response carries. A
 * different charset would change how the parser treats the payloads carrying astral code points and
 * C0 controls.
 *
 * <h2>Same-origin sentinels</h2>
 *
 * <p>Beyond the case itself the server carries a small fixed set of resources, because corpus
 * templates reference them ({@code /logo.png} under a hijacked {@code <base href>},
 * {@code /app.js} beside a CSP nonce, {@code /save} as a form action, {@code /i.png} beside a
 * {@code srcset}) and because two of them are oracles in their own right:
 *
 * <ul>
 *   <li>{@code /beacon} and {@code /x.js} are the <em>same-origin</em> twins of the attacker-origin
 *       sentinels. A test that wants to prove a request happened at all, as opposed to happening
 *       off-origin, aims at these.
 *   <li>{@code /target} is a navigation destination, so a meta refresh or a form submission has
 *       somewhere same-origin to land.
 *   <li>{@code /user-content} is the sandbox oracle. It serves a document whose only script calls
 *       the page's script-execution sentinel. Framed with {@code sandbox=""} or with any value the
 *       parser does not recognise, that script cannot run; framed with {@code allow-scripts} it
 *       does. That makes {@code policy.sandbox} (F20) observable as an <em>effect</em> rather than
 *       as a string comparison on the attribute value, which is what &sect;2.3 asks for.
 * </ul>
 *
 * <p>Everything else 404s, deliberately: {@code <img src=x onerror=...>} inside a {@code srcdoc}
 * resolves {@code x} against this origin, and the payload only fires if that fetch fails. A server
 * that answered 200 to everything would silence the whole {@code SRCDOC_MARKUP} family.
 *
 * <h2>The request log</h2>
 *
 * <p>Every request is logged, including the 404s, and the log is what tests assert against. It is
 * the server-side half of the browser tier's evidence; the client-side half is
 * {@link BrowserVerdict}. They answer different questions — the log says what actually reached this
 * origin, the verdict says what the page tried to do — and a request to an attacker origin appears
 * only in the second, because {@code attacker.invalid} does not resolve and is aborted before it
 * leaves the browser.
 */
public final class SentinelServer implements AutoCloseable {

    /** Path prefix under which rendered cases are published. */
    public static final String CASE_PREFIX = "/case/";

    /** Same-origin beacon: a 1x1 GIF, so a CSS {@code url()} or an {@code <img>} can hit it. */
    public static final String BEACON_PATH = "/beacon";

    /** Same-origin script sentinel. */
    public static final String SCRIPT_PATH = "/x.js";

    /** Same-origin navigation destination. */
    public static final String TARGET_PATH = "/target";

    /** Framed by {@code policy.sandbox}; its script runs only if the sandbox was defeated. */
    public static final String USER_CONTENT_PATH = "/user-content";

    private static final byte[] ONE_PIXEL_GIF = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    // A real 1x1 PNG, so <img src="/logo.png"> decodes rather than firing onerror. An onerror that
    // fires for the wrong reason is a false signal in a suite whose whole subject is which handlers
    // run.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private final HttpServer server;
    private final ExecutorService executor;
    private final String origin;
    private final Map<String, String> cases = new ConcurrentHashMap<>();
    private final Map<String, String> casePolicies = new ConcurrentHashMap<>();
    private final List<LoggedRequest> log = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    private SentinelServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Binds 127.0.0.1 on an ephemeral port and starts serving.
     *
     * <p>The port is ephemeral because a fixed one turns two concurrent Gradle runs, or one leaked
     * server from a previous run, into a confusing bind failure a long way from its cause.
     */
    public static SentinelServer start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, daemonThreads());
            SentinelServer sentinel = new SentinelServer(server, executor);
            server.createContext("/", sentinel::dispatch);
            server.setExecutor(executor);
            server.start();
            return sentinel;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the sentinel HTTP server", e);
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "canoe-sentinel-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** {@code http://127.0.0.1:<port>} — the origin every same-origin judgement is made against. */
    public String origin() {
        return origin;
    }

    /** An absolute URL for a path on this origin. */
    public String url(String path) {
        return origin + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Publishes rendered HTML under a fresh {@code /case/} URL and returns it.
     *
     * <p>The id is made unique by a counter rather than taken from the case alone, so that the same
     * corpus case loaded twice — in two engines, or before and after an interaction — cannot be
     * served from the browser's cache and cannot have its two request logs confused.
     */
    public String publish(String id, String html) {
        return publish(id, html, null);
    }

    /**
     * Publishes rendered HTML under a {@code Content-Security-Policy} response header (R28).
     *
     * <p>Only one test needs this and it needs it for a reason worth stating, because a browser tier
     * that adds headers to the document under test is editing the thing it is measuring. F20's
     * {@code nonce} row is the one finding in the review with no browser evidence either before or
     * after the fix, and it cannot have any without a policy: a {@code nonce} attribute does nothing
     * at all unless the response carries a CSP naming one. The header served here names the
     * <em>author's</em> nonce — a value the attacker never sees and the corpus never renders — so
     * the policy is the page author's, as it would be in production, and not a policy written around
     * the attacker's payload. Assuming the conclusion would be a header naming the attacker's nonce;
     * this is the opposite.
     *
     * <p>Deliberately not reachable from the corpus tier. {@link BrowserCorpusTest} publishes with
     * the no-header form, so every one of the 67 corpus rows is still served exactly as Canoe
     * rendered it.
     */
    public String publish(String id, String html, String contentSecurityPolicy) {
        String slug = sanitise(id) + "-" + sequence.incrementAndGet();
        cases.put(slug, html);
        if (contentSecurityPolicy != null) {
            casePolicies.put(slug, contentSecurityPolicy);
        }
        return url(CASE_PREFIX + slug);
    }

    /** Every request this origin has received, oldest first. */
    public List<LoggedRequest> log() {
        return List.copyOf(log);
    }

    /** Requests for one path, which is the shape most assertions want. */
    public List<LoggedRequest> requestsFor(String path) {
        return log.stream().filter(r -> r.path().equals(path)).collect(Collectors.toList());
    }

    public void clearLog() {
        log.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        drain(exchange.getRequestBody());
        log.add(new LoggedRequest(exchange.getRequestMethod(), path, query));

        try {
            if (path.startsWith(CASE_PREFIX)) {
                String slug = path.substring(CASE_PREFIX.length());
                String body = cases.get(slug);
                if (body == null) {
                    respond(exchange, 404, "text/plain; charset=UTF-8",
                            "no such case".getBytes(StandardCharsets.UTF_8));
                } else {
                    String policy = casePolicies.get(slug);
                    if (policy != null) {
                        exchange.getResponseHeaders().set("Content-Security-Policy", policy);
                    }
                    respond(exchange, 200, View.CONTENT_TYPE_TEXT_HTML_UTF8,
                            body.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            switch (path) {
                case BEACON_PATH:
                    respond(exchange, 200, "image/gif", ONE_PIXEL_GIF);
                    return;
                case SCRIPT_PATH:
                case "/app.js":
                    respond(exchange, 200, "application/javascript; charset=UTF-8",
                            ("window.__canoeSameOriginScript = " + quoteJs(path) + ";")
                                    .getBytes(StandardCharsets.UTF_8));
                    return;
                case "/logo.png":
                case "/i.png":
                    respond(exchange, 200, "image/png", ONE_PIXEL_PNG);
                    return;
                case TARGET_PATH:
                case "/save":
                    respond(exchange, 200, View.CONTENT_TYPE_TEXT_HTML_UTF8, html(
                            "<title>canoe-" + path.substring(1) + "</title>"
                                    + "<p id=\"canoe-landing\">" + path + "</p>"));
                    return;
                case USER_CONTENT_PATH:
                    respond(exchange, 200, View.CONTENT_TYPE_TEXT_HTML_UTF8, html(
                            "<title>canoe-user-content</title>"
                                    // The sandbox oracle. This runs only when the framing document
                                    // granted allow-scripts; with sandbox="" or any unrecognised
                                    // token it cannot, which is what makes the difference between
                                    // policy.sandbox's two payloads an observable effect.
                                    + "<script>try{window.__canoePwned"
                                    + "&&window.__canoePwned('sandbox-scripts-enabled');}"
                                    + "catch(e){}</script>"
                                    + "<p id=\"canoe-user-content\">user content</p>"));
                    return;
                default:
                    respond(exchange, 404, "text/plain; charset=UTF-8",
                            "not found".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            exchange.close();
        }
    }

    private static byte[] html(String body) {
        return ("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" + body
                + "</head><body></body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // No caching anywhere: a cached 404 or a cached case body would make the second engine's
        // run silently different from the first, and cross-engine divergence is data this tier
        // reports rather than noise it should manufacture.
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        while (in.read(buffer) >= 0) {
            // A form submission arrives with a body; leaving it unread wedges the connection.
        }
    }

    private static String sanitise(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        return sb.toString();
    }

    private static String quoteJs(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** One line of the request log. */
    public static final class LoggedRequest {

        private final String method;
        private final String path;
        private final String query;

        LoggedRequest(String method, String path, String query) {
            this.method = method;
            this.path = path;
            this.query = query;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public String query() {
            return query;
        }

        @Override
        public String toString() {
            return method + " " + path + (query == null ? "" : "?" + query);
        }
    }
}
