package com.webkreator.qlue;

import com.webkreator.qlue.sessionlessTestPages.hello;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 1 (sessionless machine requests): a request marked with
 * {@link QlueConstants#QLUE_SESSIONLESS_REQUEST} must never create or touch the servlet
 * {@code HttpSession} — no {@code request.getSession()} in any form, and hence no
 * {@code JSESSIONID} cookie ever reaches the client.
 *
 * <p>The unit cases below mock the servlet request and drive {@link TransactionContext} directly,
 * which is the only way to assert the negative ("this method was never called") precisely. The
 * integration cases boot a real embedded Tomcat, the way {@link TomcatIntegrationTest} does,
 * because "no Set-Cookie header" is a container-level guarantee that a mocked {@code HttpSession}
 * cannot falsify: a mock would happily return {@code null} forever regardless of what the
 * framework asked it to do, whereas a real container only omits the header if nothing ever called
 * {@code request.getSession(true)}.
 */
public class SessionlessTest {

    // -----------------------------------------------------------------------------------------
    // Unit tests: mocked request, direct TransactionContext construction.
    // -----------------------------------------------------------------------------------------

    ServletConfig servletConfig;

    ServletContext servletContext;

    HttpServletRequest request;

    HttpServletResponse response;

    QlueApplication app;

    @BeforeEach
    public void setUp() {
        servletConfig = mock(ServletConfig.class);
        servletContext = mock(ServletContext.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

        // Same package as QlueApplication, so its protected no-arg constructor is visible here;
        // no need for a TestApplication subclass just to get a concrete instance.
        app = new QlueApplication();

        when(request.getRequestURI()).thenReturn("/hello");
        when(request.getAttribute(QlueConstants.QLUE_SESSIONLESS_REQUEST)).thenReturn(Boolean.TRUE);
    }

    @Test
    public void sessionlessGetQlueSessionNeverTouchesHttpSession() throws Exception {
        TransactionContext context = new TransactionContext(
                app, servletConfig, servletContext, request, response);

        QlueSession first = context.getQlueSession();
        QlueSession second = context.getQlueSession();

        assertNotNull(first, "a sessionless request still needs a QlueSession to work against");
        assertSame(first, second,
                "getQlueSession() must return the same request-scoped instance on every call");

        // The constructor itself calls getQlueSession() (via startSession() and setUserId()), so
        // these verifications cover the whole lifecycle, not just the two explicit calls above.
        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getSession();
    }

    @Test
    public void sessionlessGetQluePageManagerIsStableAndNeverTouchesHttpSession() throws Exception {
        TransactionContext context = new TransactionContext(
                app, servletConfig, servletContext, request, response);

        QluePageManager first = context.getQluePageManager();
        QluePageManager second = context.getQluePageManager();

        assertNotNull(first);
        assertSame(first, second,
                "getQluePageManager() must return the same request-scoped instance on every call, "
                        + "so a stray _pid on a machine request cannot go looking for a real HTTP session");

        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getSession();
    }

    @Test
    public void sessionlessIsHttpSessionAvailableIgnoresReplayedLiveSession() throws Exception {
        // Even if a stale-but-still-valid JSESSIONID is replayed on this sessionless request,
        // request.getSession(false) must never be consulted -- a machine request is blind to it.
        HttpSession liveHttpSession = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(liveHttpSession);

        TransactionContext context = new TransactionContext(
                app, servletConfig, servletContext, request, response);

        assertFalse(context.isHttpSessionAvailable(),
                "a sessionless request must report no HTTP session, even if one could be found");
    }

    @Test
    public void sessionlessInvalidateHttpSessionNeverTouchesHttpSession() throws Exception {
        HttpSession liveHttpSession = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(liveHttpSession);

        TransactionContext context = new TransactionContext(
                app, servletConfig, servletContext, request, response);

        context.invalidateHttpSession();

        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getSession();
        verify(liveHttpSession, never()).invalidate();
    }

    // -----------------------------------------------------------------------------------------
    // Integration tests: real embedded Tomcat, real HTTP, real Set-Cookie headers.
    // -----------------------------------------------------------------------------------------

    @TempDir
    static Path docBase;

    static Tomcat tomcat;

    static Context context;

    static int port;

    /**
     * Marks every request under {@code /machine/} as sessionless, the way a production filter
     * would mark machine-to-machine API traffic. Registered programmatically (FilterDef/FilterMap
     * on the Tomcat Context), the same way QlueServlet itself is registered in
     * {@link TomcatIntegrationTest}, since there is no web.xml here to declare it in.
     */
    public static class MachineSessionlessFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            request.setAttribute(QlueConstants.QLUE_SESSIONLESS_REQUEST, Boolean.TRUE);
            chain.doFilter(request, response);
        }
    }

    @BeforeAll
    static void startTomcat() throws Exception {
        Path webInf = Files.createDirectories(docBase.resolve("WEB-INF"));

        // Existence is the point, not content -- see TomcatIntegrationTest for why this file has
        // to be a real file on disk rather than a classpath resource.
        Files.writeString(webInf.resolve(QlueApplication.PROPERTIES_FILENAME),
                "# Intentionally minimal: this test asserts session/cookie behaviour, not configuration.\n");

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createDirectories(docBase.resolve("tomcat-base")).toString());

        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");

        context = tomcat.addContext("", docBase.toString());
        Tomcat.addServlet(context, "qlue", new QlueServlet())
                .addInitParameter("QLUE_PAGES_ROOT_PACKAGE", hello.class.getPackageName());
        context.addServletMappingDecoded("/*", "qlue");

        FilterDef filterDef = new FilterDef();
        filterDef.setFilterName("machineSessionless");
        filterDef.setFilter(new MachineSessionlessFilter());
        context.addFilterDef(filterDef);

        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName("machineSessionless");
        filterMap.addURLPatternDecoded("/machine/*");
        context.addFilterMap(filterMap);

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopTomcat() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    public void plainRequestStillGetsSessionCookie() throws Exception {
        HttpResponse<String> response = get("/hello");

        assertEquals(200, response.statusCode(), "body: " + response.body());
        assertTrue(response.headers().firstValue("Set-Cookie").map(v -> v.contains("JSESSIONID")).orElse(false),
                "a plain request outside /machine/ must still receive a JSESSIONID cookie");
    }

    @Test
    public void machineRequestNeverGetsSessionCookie() throws Exception {
        HttpResponse<String> response = get("/machine/hello");

        assertEquals(200, response.statusCode(), "body: " + response.body());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty(),
                "a sessionless machine request must never receive a Set-Cookie header, got: "
                        + response.headers().firstValue("Set-Cookie").orElse(null));
    }

    @Test
    public void replayedSessionCookieOnMachinePathIsIgnored() throws Exception {
        // Capture a real JSESSIONID from a plain (non-machine) request first.
        HttpResponse<String> plain = get("/hello");
        String setCookie = plain.headers().firstValue("Set-Cookie")
                .orElseThrow(() -> new AssertionError("expected /hello to set a session cookie"));
        String jsessionIdCookie = setCookie.split(";", 2)[0];

        HttpResponse<String> withoutCookie = get("/machine/hello");
        HttpResponse<String> withReplayedCookie = getWithCookie("/machine/hello", jsessionIdCookie);

        assertTrue(withoutCookie.headers().firstValue("Set-Cookie").isEmpty(),
                "sanity check: the no-cookie baseline must itself not receive a Set-Cookie");
        assertEquals(withoutCookie.statusCode(), withReplayedCookie.statusCode());
        assertEquals(withoutCookie.body(), withReplayedCookie.body(),
                "replaying a captured JSESSIONID on a machine path must not change the response");
        assertTrue(withReplayedCookie.headers().firstValue("Set-Cookie").isEmpty(),
                "replaying a session cookie on a machine path must not provoke a new Set-Cookie either");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET());
    }

    private HttpResponse<String> getWithCookie(String path, String cookieHeaderValue)
            throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", cookieHeaderValue)
                .GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder requestBuilder)
            throws IOException, InterruptedException {
        // No CookieHandler configured: the client never auto-attaches or auto-stores cookies, so
        // every request's Cookie header (or lack of one) is exactly what the test asked for.
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            return client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
