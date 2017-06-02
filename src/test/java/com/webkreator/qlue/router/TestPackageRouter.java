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

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static org.mockito.Mockito.when;

public class TestPackageRouter {

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
        routeManager.add(RouteFactory.create(routeManager, "/{} package:com.webkreator.qlue.router.testPages"));
    }

    public class TestApplication extends QlueApplication {

        @Override
        public String getPriorityTemplatePath() {
            return "./src/test/java";
        }
    }

    public Object createContextAndRoute(String path) throws Exception {
        when(request.getRequestURI()).thenReturn(path);

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
}
