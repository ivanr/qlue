package com.webkreator.qlue;

import com.webkreator.qlue.router.testPages.tomcatSmoke;
import jakarta.servlet.ServletContext;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qlue inside the container it is built for.
 *
 * <p>Every other test in this suite mocks {@code HttpServletRequest} and {@code HttpServletResponse},
 * which means the suite is blind to the one thing the Jakarta EE 11 migration could get wrong: a
 * mock does not care which package its interface came from, and it does not link against a real
 * container. A suite of mocks would have passed just as cheerfully before the migration as after.
 * This test boots Tomcat 11, deploys {@link QlueServlet} into it and asks for a page over a real
 * socket, so "works on Tomcat 11" is something the build asserts rather than something a version
 * number in {@code build.gradle} implies.
 *
 * <p>It stays inside the hermetic contract the {@code test} task declares: the connector binds
 * port 0 on loopback, so there is no fixed port to collide on, nothing is downloaded, and nothing
 * leaves the machine.
 */
public class TomcatIntegrationTest {

    /**
     * The web application root. It has to be a real directory on disk rather than a classpath
     * resource, because {@link QlueApplication#determineConfigPath()} locates the configuration
     * through {@code ServletContext.getRealPath("/WEB-INF/")}, which only answers for a directory
     * docBase. It is built here rather than committed because {@code qlue.properties} is in
     * {@code .gitignore} — a committed fixture by that name would be invisible to git.
     */
    @TempDir
    static Path docBase;

    static Tomcat tomcat;

    static Context context;

    static int port;

    @BeforeAll
    static void startTomcat() throws Exception {
        Path webInf = Files.createDirectories(docBase.resolve("WEB-INF"));

        // The file's existence is the point, not its contents. QlueApplication.loadProperties()
        // sets propertiesAvailable = false when it is missing, and QlueServlet.service() then
        // short-circuits every request to 503 "Application not configured" without ever reaching
        // the router — which would look like a passing container and a broken library.
        Files.writeString(webInf.resolve(QlueApplication.PROPERTIES_FILENAME),
                "# Intentionally minimal: this test asserts routing and rendering, not configuration.\n");

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createDirectories(docBase.resolve("tomcat-base")).toString());

        // Port 0 lets the OS pick a free one; the actual number is readable only after start().
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");

        context = tomcat.addContext("", docBase.toString());
        Tomcat.addServlet(context, "qlue", new QlueServlet())
                .addInitParameter("QLUE_PAGES_ROOT_PACKAGE", tomcatSmoke.class.getPackageName());
        context.addServletMappingDecoded("/*", "qlue");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopTomcat() throws Exception {
        if (tomcat != null) {
            // Not merely tidiness: QlueApplication.qluePostInit() starts a cron4j scheduler and,
            // when configured, an email-sender thread. Both outlive the test class otherwise.
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    public void servesAPageThroughTomcat() throws Exception {
        HttpResponse<String> response = get("/tomcatSmoke");

        assertEquals(200, response.statusCode(),
                "expected the page to render; 503 here means Qlue never saw the request because"
                        + " WEB-INF/qlue.properties was not found, which is a broken fixture rather"
                        + " than a broken library. Body: " + response.body());
        assertEquals(tomcatSmoke.BODY, response.body(),
                "the body must come from the page's own view, so that the assertion is about Qlue"
                        + " having routed and rendered rather than about Tomcat having answered");
    }

    /**
     * The container's own view of the API level, read back from the servlet context rather than
     * assumed. This is what pins the test to Tomcat 11: Servlet 6.1 is the version it implements,
     * and a build that silently resolved an older Tomcat would fail here rather than pass quietly.
     */
    @Test
    public void runsAgainstServletSixPointOne() throws Exception {
        HttpResponse<String> response = get("/_qlue/devMode");

        // devMode is a built-in Qlue page, and it is reachable, which proves the container routed
        // into Qlue's own package as well as the application's. Its status depends on the
        // development-mode configuration, so the assertion is that it was handled by Qlue at all.
        assertTrue(response.statusCode() < 500,
                "the built-in _qlue routes must be reachable inside the container, got "
                        + response.statusCode());

        ServletContext servletContext = context.getServletContext();
        assertEquals(6, servletContext.getMajorVersion(), "Tomcat 11 implements Servlet 6.x");
        assertEquals(1, servletContext.getMinorVersion(), "Tomcat 11 implements Servlet 6.1");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
