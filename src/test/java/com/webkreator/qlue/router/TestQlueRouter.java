package com.webkreator.qlue.router;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;
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

public class TestQlueRouter {

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

    public class TestApplication extends QlueApplication {}

    @Test
    public void testRoot() throws Exception {
        when(request.getRequestURI()).thenReturn("/");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);

        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.index);
    }

    @Test
    public void testSubdir() throws Exception {
        when(request.getRequestURI()).thenReturn("/subdir/");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.subdir.index);
    }

    @Test
    public void testPageOneMatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/pageOne");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.pageOne);
    }

    @Test
    public void testPageOneCaseMismatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/PAGEONE");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPageOneSuffixMismatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/pageOne.html");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPageTwoMatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/pageTwo.html");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.pageTwo);
    }

    @Test
    public void testPageTwoSuffixMismatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/pageTwo");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPageThreeMatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/page_three");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.page_three);
    }

    @Test
    public void testPageThreeMismatch() throws Exception {
        when(request.getRequestURI()).thenReturn("/page-three");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPageMatchConvertedDash() throws Exception {
        when(request.getRequestURI()).thenReturn("/page-three");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        routeManager.setConcertDashesToUnderscores(true);
        Object o = routeManager.route(context);
        Assert.assertTrue(o instanceof com.webkreator.qlue.router.testPages.page_three);
    }

    @Test
    public void testPageFourMismatch1() throws Exception {
        when(request.getRequestURI()).thenReturn("/$pageFour");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPageFourMismatch2() throws Exception {
        when(request.getRequestURI()).thenReturn("/$pageFour.html");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPrivateSubdirMismatch1() throws Exception {
        when(request.getRequestURI()).thenReturn("/$subdir/");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }

    @Test
    public void testPrivateSubdirMismatch2() throws Exception {
        when(request.getRequestURI()).thenReturn("/$subdir/index");

        TransactionContext context = new TransactionContext(
                app,
                servletConfig,
                servletContext,
                request,
                response);

        Object o = routeManager.route(context);
        Assert.assertNull(o);
    }
}
