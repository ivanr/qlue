package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeEncodingException;
import com.webkreator.qlue.view.velocity.ProductionRenderProbe;
import org.apache.velocity.exception.VelocityException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CanoeEncodingException}: the type R21 introduced, and the cause-chain search that replaced
 * F13's message test.
 *
 * <p><strong>What F13 was.</strong> {@code VelocityViewFactory.render()} recognised an encoding error
 * by {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on the exception it caught. It never
 * caught Canoe's: Velocity wraps an exception thrown from the writer in one of its own. The review
 * measured two wrapper messages, {@code "IO Error rendering template '...'"} from
 * {@code Template.merge()} and {@code "IO Error in writer: ..."} from {@code evaluate()}, and both
 * fail the test.
 *
 * <p><strong>There are more than two, and that is the argument for matching on the type.</strong>
 * {@link #velocityWrapsCanoesExceptionInMessagesThatShareNoCommonPrefix} measures four, of which two —
 * a rejection inside a {@code #parse}d fragment and one inside a macro body — carry a message that
 * contains neither {@code "IO Error"} nor Canoe's prefix nor Canoe's text at all. A repaired
 * <em>message</em> test would have had to know every wrapper Velocity might choose, including ones a
 * future version adds. A type in the cause chain is what no wrapper can hide, and both shapes are
 * ordinary production ones: layouts are assembled with {@code #parse}, and Qlue configures macro
 * libraries by default.
 */
public class CanoeEncodingExceptionTest {

    // ------------------------------------------------------------------
    // The structured fields
    // ------------------------------------------------------------------

    /**
     * The coordinates {@code raiseError()} computes are fields now, not only a substring of the
     * message.
     *
     * <p>They were always computed and always spent on string concatenation, so a caller that wanted
     * them had to parse the message back apart — which is what {@code CanoeCorpusTest} does with a
     * regular expression to this day. R20 will report them, and this is what it reads.
     */
    @Test
    public void theExceptionCarriesTheReasonAndTheCoordinatesAsFields() throws IOException {
        CanoeEncodingException error = assertThrows(CanoeEncodingException.class,
                () -> new Canoe(new StringWriter()).write("<p>\n</p>\n<p>\n<br/>"));

        assertEquals("Invalid character after tag name", error.getReason(),
                "the reason on its own, with no prefix and no coordinates: this is what identifies"
                        + " WHICH rejection fired, and getMessage() cannot be used for that because"
                        + " it varies with the position");
        assertEquals(4, error.getLine());
        assertEquals(4, error.getPosition());

        assertEquals(Canoe.ERROR_PREFIX + "Invalid character after tag name (line: 4, pos: 4)",
                error.getMessage(),
                "and the message is byte for byte what the bare IOException carried before R21;"
                        + " ERROR_PREFIX is kept for exactly that reason");
    }

    /**
     * It is still an {@link IOException}, which is not decoration: {@link Canoe} is a
     * {@link java.io.Writer}, and {@code Writer.write} may only throw {@code IOException}. A checked
     * exception of any other type would have meant wrapping it at every call site — or, worse,
     * making it unchecked, which is how a rejection stops being something a caller must consider.
     */
    @Test
    public void itIsStillAnIOExceptionBecauseTheWriterContractAllowsNothingElse() {
        CanoeEncodingException error = assertThrows(CanoeEncodingException.class,
                () -> new Canoe(new StringWriter()).write("<br/>"));

        assertInstanceOf(IOException.class, error);
    }

    // ------------------------------------------------------------------
    // The cause-chain search
    // ------------------------------------------------------------------

    /** The direct path: what Canoe threw is what {@code findIn} answers. */
    @Test
    public void findInAnswersTheExceptionItselfAtDepthZero() {
        CanoeEncodingException error = new CanoeEncodingException("Invalid tag", 1, 3);

        assertSame(error, CanoeEncodingException.findIn(error));
    }

    /** One wrapper, which is what every real Velocity path produces. */
    @Test
    public void findInLooksThroughAWrapper() {
        CanoeEncodingException error = new CanoeEncodingException("Invalid tag", 1, 3);

        assertSame(error, CanoeEncodingException.findIn(
                new VelocityException("IO Error rendering template 'x.vm'", error)));
        assertSame(error, CanoeEncodingException.findIn(
                new IOException("wrapped again", new VelocityException("IO Error", error))));
    }

    /**
     * The bound, from both sides. The walk stops after 32 links, so a chain deeper than that is
     * answered null rather than searched forever.
     */
    @Test
    public void findInIsBoundedAtThirtyTwoLinks() {
        CanoeEncodingException error = new CanoeEncodingException("Invalid tag", 1, 3);

        assertSame(error, CanoeEncodingException.findIn(wrap(error, 31)),
                "the deepest position the walk reaches");
        assertNull(CanoeEncodingException.findIn(wrap(error, 32)),
                "and one link further is out of reach - a bound the walk keeps rather than a"
                        + " nesting depth anyone should produce");
    }

    /**
     * A cause cycle terminates. {@code Throwable.getCause()} returns null for a self-cycle, but a
     * two-cycle is constructible and would spin a naive walk forever.
     */
    @Test
    public void findInTerminatesOnACauseCycle() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);

        assertNull(CanoeEncodingException.findIn(a));
    }

    /** Null in, null out: a caller should not have to guard the guard. */
    @Test
    public void findInAnswersNullForNull() {
        assertNull(CanoeEncodingException.findIn(null));
    }

    /**
     * The strictness that a message test could not have, and the reason it matters here more than
     * elsewhere: the corpus is a catalogue of hostile strings, and Velocity quotes them back in its
     * own parse and method-invocation errors. An exception that merely <em>says</em> {@code "Encoding
     * Error: "} is not one, and must not be mistaken for one — a bogus rejection verdict is a ledger
     * entry nobody can reproduce.
     */
    @Test
    public void anExceptionThatOnlyQuotesTheMessageIsNotAnEncodingError() {
        VelocityException lookalike = new VelocityException(
                Canoe.ERROR_PREFIX + "Invalid tag (line: 1, pos: 3)");

        assertTrue(lookalike.getMessage().startsWith(Canoe.ERROR_PREFIX),
                "the old predicate would have said yes");
        assertNull(CanoeEncodingException.findIn(lookalike),
                "and the type says no");
    }

    // ------------------------------------------------------------------
    // The wrappers Velocity actually produces
    // ------------------------------------------------------------------

    /**
     * Four real renders, four wrapper messages, no common prefix. Measured rather than asserted from
     * the review's table, which lists the first two.
     *
     * <p>The template is {@code <br/>} in every case — the review's own first rejection row — so the
     * only thing that varies is where Velocity was when the writer threw. Two of the four messages
     * do not contain {@code "IO Error"}; the same two do not contain Canoe's message either, so
     * neither {@code startsWith} nor {@code contains} on the top-level message could have found
     * them. Every one of the four is found by type.
     */
    @Test
    public void velocityWrapsCanoesExceptionInMessagesThatShareNoCommonPrefix() {
        Map<String, Throwable> wrappers = new LinkedHashMap<>();

        // The production path, through VelocityViewFactory.render() before R21 unwrapped it. Driven
        // here through the harness's evaluate() twin plus the probe, because render() no longer hands
        // the wrapper out - which is the fix, and is why this row is measured on the raw engine.
        wrappers.put("evaluate", CanoeTestSupport.render("<p>a</p><br/>").thrown());

        CanoeTestSupport.publishFragment("canoe-encoding-exception-fragment.vm", "<br/>");
        wrappers.put("#parse", CanoeTestSupport
                .render("<p>a</p>#parse(\"canoe-encoding-exception-fragment.vm\")").thrown());
        wrappers.put("macro", CanoeTestSupport
                .render("#macro(m)<br/>#end<p>a</p>#m()").thrown());

        wrappers.put("reference", CanoeTestSupport.render("<p>$data</p><br/>", "x").thrown());

        for (Map.Entry<String, Throwable> entry : wrappers.entrySet()) {
            Throwable top = entry.getValue();
            assertNotNull(top, entry.getKey() + ": the premise is that the render failed");
            assertFalse(top instanceof CanoeEncodingException,
                    entry.getKey() + ": Velocity wraps, always - that is F13's whole mechanism");
            assertFalse(top.getMessage().startsWith(Canoe.ERROR_PREFIX),
                    entry.getKey() + ": ...so the old predicate was false here: " + top.getMessage());

            CanoeEncodingException found = CanoeEncodingException.findIn(top);
            assertNotNull(found, entry.getKey() + ": and the type is found regardless: "
                    + top.getMessage());
            assertEquals("Invalid character after tag name", found.getReason(), entry.getKey());
        }

        // The two the review's table did not have. Their messages name the directive rather than the
        // failure, so they carry no trace of Canoe at all.
        assertFalse(wrappers.get("#parse").getMessage().contains("IO Error"),
                "a rejection inside a #parse'd fragment: " + wrappers.get("#parse").getMessage());
        assertFalse(wrappers.get("#parse").getMessage().contains("Encoding Error"),
                "...and it does not even quote Canoe's message, so contains() would fail too");
        assertFalse(wrappers.get("macro").getMessage().contains("IO Error"),
                "a rejection inside a macro body: " + wrappers.get("macro").getMessage());
        assertFalse(wrappers.get("macro").getMessage().contains("Encoding Error"),
                "...likewise");
    }

    /**
     * The same two shapes on the production path, end to end: a rejection inside a {@code #parse}d
     * fragment and one inside a macro body both reach the caller of {@code render()} as the typed
     * exception, with the coordinates of the character <em>in the fragment</em>.
     *
     * <p>This is the row F13's table could not have: before R21 these did not merely miss the
     * recovery branch, they missed every plausible repair of it.
     *
     * <p><strong>And the caveat R20 needs.</strong> The coordinates are positions in the
     * <em>rendered output</em>, not in any template file: Canoe counts the characters it is given,
     * and it is given one stream for the whole response. The {@code /} is the fourth character of the
     * fragment and the twelfth of the page, and the exception says twelve. That is the right answer to
     * the question Canoe can answer, and it is not the question a developer asks ("where in my
     * template?"), which is worth knowing before anything reports these to a human.
     */
    @Test
    public void aRejectionInsideAParsedFragmentReachesTheCallerTyped() {
        ProductionRenderProbe.publishFragment("canoe-encoding-exception-probe-fragment.vm", "<br/>");

        ProductionRenderProbe.Outcome fromParse = ProductionRenderProbe.render(
                "<p>a</p>#parse(\"canoe-encoding-exception-probe-fragment.vm\")");
        assertInstanceOf(CanoeEncodingException.class, fromParse.escaped(),
                () -> "R21: " + fromParse);
        assertEquals("Invalid character after tag name", fromParse.encodingError().getReason());
        assertEquals(12, fromParse.encodingError().getPosition(),
                "the position is in the OUTPUT stream: 8 characters of <p>a</p> and then the fourth"
                        + " character of the fragment. Not the position in the fragment, and not the"
                        + " position in any file");

        ProductionRenderProbe.Outcome fromMacro =
                ProductionRenderProbe.render("#macro(m)<br/>#end<p>a</p>#m()");
        assertInstanceOf(CanoeEncodingException.class, fromMacro.escaped(),
                () -> "R21: " + fromMacro);
        assertEquals("Invalid character after tag name", fromMacro.encodingError().getReason());
    }

    private static Throwable wrap(Throwable inner, int depth) {
        Throwable current = inner;
        for (int i = 0; i < depth; i++) {
            current = new VelocityException("wrapper " + i, current);
        }
        return current;
    }
}
