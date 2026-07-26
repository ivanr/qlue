package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the harness itself. Everything else in the suite trusts these, so they are worth stating
 * explicitly rather than assuming.
 */
public class CanoeTestSupportTest {

    @Test
    public void rendersAndEncodesBodyText() {
        CanoeTestSupport.RenderResult result =
                CanoeTestSupport.render("<p>$data</p>", "<img src=x>");

        assertFalse(result.isError());
        assertEquals("<p>&lt;img src&#61;x&gt;</p>", result.output());
        assertEquals("hello", CanoeTestSupport.render("<p>$data</p>", "hello").decodedText("p"));
    }

    /**
     * Reproduces F3: {@code xlink:href} is not one of the names Canoe classifies as a URL — {@code
     * isTagNameChar()} accepts {@code ':'}, so it scans as one attribute name and simply does not
     * match {@code href} — so the payload arrives html-encoded and the parser decodes it back before
     * handing it to the URL parser.
     *
     * <p>Note the two assertions. The raw output does not contain the payload, which is exactly why
     * a string-level assertion would have called this safe. The decoded attribute does.
     *
     * <p>The template used to be {@code <form onsubmit="v('$data')">}, reproducing F1 by the
     * identical mechanism. R4 suppresses every {@code on*} value, so that template no longer emits
     * anything for the harness to decode; the sink moved rather than the test, because what is being
     * demonstrated is {@code decodedAttr}'s reason for existing and not any particular finding.
     */
    @Test
    public void decodedAttrExposesWhatAStringAssertionWouldMiss() {
        CanoeTestSupport.RenderResult result = CanoeTestSupport.render(
                "<svg><a xlink:href=\"$data\"><text>go</text></a></svg>",
                "javascript:alert(1)");

        assertFalse(result.output().contains("javascript:alert(1)"),
                "Canoe emits the payload entity-encoded");
        assertEquals("javascript:alert(1)",
                result.decodedAttr("a", "xlink:href"),
                "the HTML parser decodes it straight back into an executable URL");
    }

    @Test
    public void reportsEncodingErrorsRatherThanThrowing() {
        // A '/' immediately after a tag name is rejected: <br/> does not parse, <br /> does.
        CanoeTestSupport.WriteResult result = CanoeTestSupport.write("<br/>");

        assertTrue(result.isError());
        assertTrue(result.errorMessage().startsWith(Canoe.ERROR_PREFIX), result.errorMessage());
        assertFalse(CanoeTestSupport.write("<br />").isError());
    }

    @Test
    public void probesContextWithoutVelocity() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<p>"));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\""));
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""));
    }

    @Test
    public void contextAfterRefusesInputItCannotParse() {
        assertThrows(AssertionError.class, () -> CanoeTestSupport.contextAfter("<br/>"));
    }

    @Test
    public void namesContextsReadably() {
        assertEquals("CTX_HTML", CanoeTestSupport.contextName(Canoe.CTX_HTML));
        assertEquals("CTX_SUPPRESS", CanoeTestSupport.contextName(Canoe.CTX_SUPPRESS));
        assertEquals("CTX_UNKNOWN(99)", CanoeTestSupport.contextName(99));
    }

    @Test
    public void assertCannotOpenTagFiresOnARawAngleBracket() {
        CanoeTestSupport.assertCannotOpenTag(Canoe.encode("<img>", Canoe.CTX_HTML));
        assertThrows(AssertionError.class, () -> CanoeTestSupport.assertCannotOpenTag("<img>"));
    }

    @Test
    public void bindsTheEncodingToolSoBypassTestsCanRun() {
        assertEquals("<p><img src=x></p>",
                CanoeTestSupport.render("<p>$_x.asis($data)</p>", "<img src=x>").output());
    }
}
