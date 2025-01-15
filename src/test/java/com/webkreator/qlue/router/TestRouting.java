package com.webkreator.qlue.router;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.ClasspathView;
import com.webkreator.qlue.view.RedirectView;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import static org.mockito.Mockito.when;

public class TestRouting {

    @Mock
    ServletConfig servletConfig;

    @Mock
    ServletContext servletContext;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    HttpSession session;

    QlueApplication app;

    QlueRouteManager routeManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(request.getSession()).thenReturn(session);

        app = new TestApplication();
        routeManager = new QlueRouteManager(app);
        routeManager.add(RouteFactory.create(routeManager, "POST /api/update com.webkreator.qlue.router.testPages.$api.Update"));
        routeManager.add(RouteFactory.create(routeManager, "/noRedirSubdir/?{} package:com.webkreator.qlue.router.testPages.noRedirSubdir"));
        routeManager.add(RouteFactory.create(routeManager, "/redirSubdir/{} package:com.webkreator.qlue.router.testPages.redirSubdir"));
        routeManager.add(RouteFactory.create(routeManager, "/{} package:com.webkreator.qlue.router.testPages"));
        routeManager.tuneRoutesForMethodNotFound();
    }

    public class TestApplication extends QlueApplication {

        @Override
        public String getPriorityTemplatePath() {
            return "./src/test/java";
        }
    }

    public Object createContextAndRoute(String path) throws Exception {
        return createContextAndRoute(path, null);
    }

    public Object createContextAndRoute(String path, String queryString) throws Exception {
        when(request.getRequestURI()).thenReturn(path);
        when(request.getQueryString()).thenReturn(queryString);

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        return routeManager.route(context);
    }

    @Test
    public void testRoot() throws Exception {
        Object o = createContextAndRoute("/");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.index);
    }

    @Test
    public void testIndexRedirection1() throws Exception {
        Object o = createContextAndRoute("/index");
        Assert.assertTrue(o instanceof RedirectView);
        RedirectView rv = (RedirectView)o;
        Assert.assertEquals("/", rv.getUri());
    }

    @Test
    public void testIndexRedirection2() throws Exception {
        Object o = createContextAndRoute("/subdir/index");
        Assert.assertTrue(o instanceof RedirectView);
        RedirectView rv = (RedirectView)o;
        Assert.assertEquals("/subdir/", rv.getUri());
    }

    @Test
    public void testIndexRedirection3() throws Exception {
        routeManager.setSuffix(".html");
        Object o = createContextAndRoute("/index.html");
        Assert.assertTrue(o instanceof RedirectView);
        RedirectView rv = (RedirectView)o;
        Assert.assertEquals("/", rv.getUri());
    }

    @Test
    public void testSubdir() throws Exception {
        Object o = createContextAndRoute("/subdir/");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.subdir.index);
    }

    @Test
    public void testSubdirRedirection() throws Exception {
        Object o = createContextAndRoute("/subdir");
        Assert.assertTrue(o instanceof RedirectView);
        Assert.assertEquals(((RedirectView)o).getUri(), "/subdir/");
    }

    @Test
    public void testRedirectionQueryStringPreserved1() throws Exception {
        Object o = createContextAndRoute("/subdir", "x=y");
        Assert.assertTrue(o instanceof RedirectView);
        Assert.assertEquals("/subdir/?x=y", ((RedirectView)o).getUri());
    }

    @Test
    public void testRedirectionQueryStringPreserved2() throws Exception {
        Object o = createContextAndRoute("/subdir/index", "x=y");
        Assert.assertTrue(o instanceof RedirectView);
        Assert.assertEquals("/subdir/?x=y", ((RedirectView)o).getUri());
    }

    @Test
    public void testRedirectionQueryStringPreserved3() throws Exception {
        Object o = createContextAndRoute("/subdir", "query");
        Assert.assertTrue(o instanceof RedirectView);
        Assert.assertEquals("/subdir/?query", ((RedirectView)o).getUri());
    }

    @Test
    public void testPageOneMatch() throws Exception {
        Object o = createContextAndRoute("/pageOne");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.pageOne);
    }

    @Test
    public void testPageOneCaseMismatch() throws Exception {
        Object o = createContextAndRoute("/PAGEONE");
        Assert.assertNull(o);
    }

    @Test
    public void testPageOneSuffixMismatch() throws Exception {
        Object o = createContextAndRoute("/pageOne.html");
        Assert.assertNull(o);
    }

    @Test
    public void testPageOneMatchSuffix() throws Exception {
        routeManager.setSuffix(".html");
        Object o = createContextAndRoute("/pageOne.html");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.pageOne);
    }

    @Test
    public void testPageTwoMatch() throws Exception {
        Object o = createContextAndRoute("/pageTwo.html");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.pageTwo);
    }

    @Test
    public void testPageTwoSuffixMismatch() throws Exception {
        Object o = createContextAndRoute("/pageTwo");
        Assert.assertNull(o);
    }

    @Test
    public void testPageThreeMatch() throws Exception {
        Object o = createContextAndRoute("/page_three");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.page_three);
    }

    @Test
    public void testPageThreeMismatch() throws Exception {
        Object o = createContextAndRoute("/page-three");
        Assert.assertNull(o);
    }

    @Test
    public void testPageMatchConvertedDash() throws Exception {
        routeManager.setConcertDashesToUnderscores(true);
        Object o = createContextAndRoute("/page-three");
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.page_three);
    }

    @Test
    public void testPageFourMismatch1() throws Exception {
        Object o = createContextAndRoute("/$pageFour");
        Assert.assertNull(o);
    }

    @Test
    public void testPageFourMismatch2() throws Exception {
        Object o = createContextAndRoute("/$pageFour.html");
        Assert.assertNull(o);
    }

    @Test
    public void testPrivateSubdirMismatch1() throws Exception {
        Object o = createContextAndRoute("/$subdir/");
        Assert.assertNull(o);
    }

    @Test
    public void testPrivateSubdirMismatch2() throws Exception {
        Object o = createContextAndRoute("$subdir/index");
        Assert.assertNull(o);
    }

    @Test
    public void testTemplateDirect() throws Exception {
        Object o = createContextAndRoute("/direct");
        Assert.assertTrue(o instanceof ClasspathView);
        Assert.assertEquals("com/webkreator/qlue/router/testPages/direct.vmx", ((ClasspathView)o).getViewName());
    }

    @Test
    public void testRedirSubdir() throws Exception {
        Object o = createContextAndRoute("/redirSubdir");
        Assert.assertTrue(o instanceof RedirectView);
        Assert.assertEquals("/redirSubdir/", ((RedirectView)o).getUri());
    }

    @Test
    public void testNoRedirSubdir() throws Exception {
        Object o = createContextAndRoute("/noRedirSubdir");
        Assert.assertNotEquals(null, o);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.noRedirSubdir.index);
    }

    @Test
    public void testRequestMethodNotFound1() throws Exception {
        Object o = createContextAndRoute("/api/update");
        Assert.assertNotEquals(null, o);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.StatusCodeRouter);
    }
}
