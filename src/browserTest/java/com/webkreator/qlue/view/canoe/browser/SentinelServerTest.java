package com.webkreator.qlue.view.canoe.browser;

import com.webkreator.qlue.view.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentinel origin's own tests (T25).
 *
 * <p>Two halves, and the split is the point. The first half needs no browser at all: it asserts the
 * content type, the 404 policy and the request log over plain HTTP, so a defect in the server is
 * reported as a server failure rather than as a mysterious miss in a corpus case forty tests later.
 * The second half is T25's stated acceptance criterion — a page loads in a real browser and its
 * subresource request appears in the log.
 */
public class SentinelServerTest extends BrowserTestBase {

    static List<BrowserEngine> engines() {
        return engineArgumentsOrSkipMarker();
    }

    @Test
    public void aPublishedCaseIsServedAsTheContentTypeProductionSets() throws Exception {
        String url = server.publish("content-type", "<p id=\"probe\">canoe</p>");
        Response response = get(url);

        assertEquals(200, response.status);
        // Referenced from production rather than copied: if View's constant ever changes, the
        // browser tier must parse under the new one, not under a stale copy of the old one.
        assertEquals(View.CONTENT_TYPE_TEXT_HTML_UTF8, response.contentType);
        assertEquals("<p id=\"probe\">canoe</p>", response.body);
    }

    @Test
    public void theOriginIsLoopbackOnAnEphemeralPort() {
        assertTrue(server.origin().startsWith("http://127.0.0.1:"),
                "expected a loopback origin, got " + server.origin());
        int port = Integer.parseInt(server.origin().substring("http://127.0.0.1:".length()));
        assertTrue(port > 0 && port <= 65535, "unexpected port " + port);
    }

    @Test
    public void unknownPathsAre404SoThatOnErrorPayloadsCanFire() throws Exception {
        // <img src=x onerror=...> inside a srcdoc resolves x against this origin and only fires if
        // the fetch fails. A server that answered 200 to everything would silence the whole
        // SRCDOC_MARKUP family without any test noticing.
        assertEquals(404, get(server.url("/no-such-thing")).status);
        assertEquals(404, get(server.url("/case/never-published")).status);
    }

    @Test
    public void theSentinelResourcesAllExist() throws Exception {
        for (String path : List.of(SentinelServer.BEACON_PATH, SentinelServer.SCRIPT_PATH,
                SentinelServer.TARGET_PATH, SentinelServer.USER_CONTENT_PATH,
                "/app.js", "/logo.png", "/i.png", "/save")) {
            assertEquals(200, get(server.url(path)).status, path + " should be served");
        }
    }

    @Test
    public void theRequestLogRecordsMethodPathAndQuery() throws Exception {
        server.clearLog();
        get(server.url("/beacon?who=canoe"));

        List<String> paths = server.log().stream()
                .map(SentinelServer.LoggedRequest::toString)
                .collect(Collectors.toList());
        assertTrue(paths.contains("GET /beacon?who=canoe"), "log was " + paths);
        assertEquals(1, server.requestsFor("/beacon").size());
    }

    @Test
    public void publishingTheSameCaseTwiceYieldsTwoUrls() {
        // Two engines load the same case; sharing a URL would let the second read the first's
        // cached response and would make the two request logs indistinguishable.
        assertFalse(server.publish("dup", "<p>a</p>").equals(server.publish("dup", "<p>a</p>")));
    }

    /**
     * T25's acceptance criterion: a hand-written page loads in a real browser and the subresource
     * request it makes shows up in the log.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aPageLoadsAndItsBeaconRequestIsLogged(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "server.beacon",
                "<p id=\"probe\">canoe</p><img src=\"/beacon\" alt=\"\">",
                passiveLoad());

        assertTrue(verdict.serverRequests().stream().anyMatch(r -> r.equals("GET /beacon")),
                "the browser should have fetched /beacon; the server saw "
                        + verdict.serverRequests());
        assertFalse(verdict.exploited(),
                "a benign page must not trip a detector:\n" + verdict.describe());
    }

    // ------------------------------------------------------------------

    private static Response get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        try {
            int status = connection.getResponseCode();
            String contentType = connection.getHeaderField("Content-Type");
            String body = read(status < 400 ? connection.getInputStream() : connection.getErrorStream());
            return new Response(status, contentType, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final String body;

        Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
