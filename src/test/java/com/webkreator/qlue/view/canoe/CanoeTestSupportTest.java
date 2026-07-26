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
     * The one fact the whole suite rests on: {@code html()}'s output is <em>reversible</em>, so an
     * assertion about Canoe's bytes and an assertion about what a parser hands the next consumer are
     * different assertions.
     *
     * <p>The raw output does not contain the payload, which is exactly why a string-level assertion
     * would have called it neutralised. The decoded attribute contains it character for character.
     * On {@code title} that is harmless and correct — a title is text, and getting the value back is
     * what the developer asked for — and it is the same mechanism that made F1, F2, F3 and F20
     * exploitable, because those sinks handed the decoded value to a second parser or to a browser
     * algorithm.
     *
     * <p>The sink has moved twice, which is worth recording rather than hiding. It was
     * {@code <form onsubmit="v('$data')">}, reproducing F1; R4 suppressed every {@code on*} value,
     * so it moved to {@code xlink:href}, reproducing F3; R6 routed {@code xlink:href} to
     * {@code url()}, so it moved again. Each move is Phase A closing the sink the demonstration was
     * using, and the demonstration itself — {@code decodedAttr}'s reason for existing — is what has
     * to survive them. That there is no longer a <em>dangerous</em> sink to demonstrate it on is the
     * point of Phase A.
     */
    @Test
    public void decodedAttrExposesWhatAStringAssertionWouldMiss() {
        CanoeTestSupport.RenderResult result =
                CanoeTestSupport.render("<a title=\"$data\">go</a>", "javascript:alert(1)");

        assertFalse(result.output().contains("javascript:alert(1)"),
                "Canoe emits the payload with its colon and parentheses as character references");
        assertEquals("javascript:alert(1)", result.decodedAttr("a", "title"),
                "...and the HTML parser decodes every one of them back before anything downstream"
                        + " sees the value. Harmless in a title; the identical mechanism is what"
                        + " made every attribute finding in the review exploitable, and it is why"
                        + " the ledger judges sinks on the decoded value rather than on the bytes.");
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
