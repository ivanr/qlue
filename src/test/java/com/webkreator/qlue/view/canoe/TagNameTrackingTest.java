package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>R8: the element name survives into attribute parsing.</strong>
 *
 * <p>Canoe used to discard the tag name the moment an attribute started, because the shared buffer
 * {@code buf} is reused for the attribute name. That blindness is F6's structural cause — {@code src}
 * on {@code <script>} and {@code src} on {@code <img>} were indistinguishable by the time
 * {@link Canoe#setTagAttributeContext()} ran — and it is why {@code content} could not be recognised
 * as a URL on {@code <meta http-equiv="refresh">} (R7's note, R10's task). R8 adds a field,
 * {@code tagName}, that holds the element name for the duration of the tag.
 *
 * <p><strong>R8 changes no behaviour.</strong> Nothing reads the field for a security decision yet;
 * these tests are about the field's own correctness, because R9 and R10 will make security decisions
 * on exactly this value. What they pin:
 *
 * <ul>
 *   <li><strong>Available at decision time.</strong> The name is set when the tag name completes and
 *       is still there while every attribute of the tag — first, middle or last — is being named,
 *       valued or awaited. R9 asks "is the current element script/iframe/object/embed/link/base"
 *       from inside {@code setTagAttributeContext()} and {@code currentContext()}, both of which run
 *       during attribute parsing.
 *   <li><strong>Normalised.</strong> Lower case however the template spelled it, matching the
 *       convention the attribute-name scan already follows, so R9 and R10 compare against lower-case
 *       literals with no case-insensitivity of their own to get wrong.
 *   <li><strong>Never stale.</strong> Null before the first tag, null in body text after {@code '>'},
 *       null inside script/style bodies, comments and DOCTYPEs, and replaced — not merely appended
 *       to — when the next tag starts. A stale name is how "is this a script element" would be
 *       answered with the previous element's name, which would be a new residue bug of exactly the
 *       family R3 closed.
 *   <li><strong>End tags carry the name without the slash</strong>, with {@code closingTag} saying
 *       which kind of tag it was — including the {@code </script>} and {@code </style>} end tags,
 *       which reach the TAG state through SCRIPT_END/CSS_END without ever passing TAG_NAME.
 * </ul>
 */
public class TagNameTrackingTest {

    // ------------------------------------------------------------------
    // Available at attribute-parsing time, across the states a reference
    // can be inserted from.
    // ------------------------------------------------------------------

    /**
     * One row per (template prefix, expected name, expected state): the tag name is present and
     * correct at the exact parser positions R9 and R10 will consult it from.
     */
    static Stream<Arguments> attributePositions() {
        return Stream.of(
                // The R9 shapes: a URL attribute on a resource-loading element, quoted and not.
                Arguments.of("<script src=\"", "script", Canoe.TAG_ATTR_VALUE),
                Arguments.of("<script src=\"https://cdn.example/app.js", "script",
                        Canoe.TAG_ATTR_VALUE),
                Arguments.of("<div id=x", "div", Canoe.TAG_ATTR_VALUE),
                // The R10 shape: the sibling attribute's value is being parsed and the element
                // name is still available.
                Arguments.of("<meta http-equiv=\"refresh\" content=\"", "meta",
                        Canoe.TAG_ATTR_VALUE),
                // Mid-attribute-name, between attributes, and awaiting a value.
                Arguments.of("<iframe sr", "iframe", Canoe.TAG_ATTR_NAME),
                Arguments.of("<iframe src=\"a\" ", "iframe", Canoe.TAG),
                Arguments.of("<iframe src=\"a\" name ", "iframe", Canoe.TAG_ATTR_NAME_AFTER),
                Arguments.of("<iframe src=\"a\" name=", "iframe", Canoe.TAG_ATTR_VALUE_BEFORE),
                // A middle and a late attribute of a many-attribute tag.
                Arguments.of("<script src=\"x\" async type=\"module\" defer", "script",
                        Canoe.TAG_ATTR_NAME),
                // Immediately after the name completes, before any attribute.
                Arguments.of("<base ", "base", Canoe.TAG),
                // The self-closing form: still inside the tag until the '>'.
                Arguments.of("<br /", "br", Canoe.TAG_EMPTY_ENDING));
    }

    @ParameterizedTest(name = "{0} -> {1} in state {2}")
    @MethodSource("attributePositions")
    public void theElementNameIsAvailableThroughoutAttributeParsing(
            String prefix, String expectedName, int expectedState) throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed(prefix);
        assertEquals(CanoeStateProbe.stateName(expectedState),
                CanoeStateProbe.stateName(probe.state()));
        assertEquals(expectedName, probe.tagName(),
                () -> "R8: after \"" + prefix + "\" the tracked element name must be "
                        + expectedName);
        assertFalse(probe.closingTag(),
                "none of these prefixes is an end tag, and R9 must be able to tell");
    }

    /** Case is folded exactly as the attribute-name scan folds it. */
    @Test
    public void theNameIsLowerCasedHoweverTheTemplateSpelledIt() throws IOException {
        assertEquals("iframe", new CanoeStateProbe().feed("<IFRAME SRC=\"").tagName());
        assertEquals("script", new CanoeStateProbe().feed("<ScRiPt src=\"").tagName());
    }

    // ------------------------------------------------------------------
    // Never stale: cleared at every boundary a tag has.
    // ------------------------------------------------------------------

    /**
     * The name is null everywhere the parser is not inside a tag: before the first one, in body
     * text, inside element bodies, and while the next tag's own name is still being read.
     */
    @Test
    public void theNameIsClearedTheMomentTheTagEnds() throws IOException {
        assertNull(new CanoeStateProbe().tagName(), "before any input");
        assertNull(new CanoeStateProbe().feed("text only").tagName(), "no tag yet");
        assertNull(new CanoeStateProbe().feed("<div id=x>").tagName(),
                "the '>' of an unquoted-value tag ends it");
        assertNull(new CanoeStateProbe().feed("<div id=\"x\">body text").tagName(),
                "body text must not see the element it is inside");
        assertNull(new CanoeStateProbe().feed("<br />").tagName(),
                "the self-closing '>' ends the tag too");
        assertNull(new CanoeStateProbe().feed("<div>").tagName(),
                "a tag with no attributes at all");

        // Inside the two raw-text element bodies the tag is over: the field must not report
        // "script" for a reference in script data, which is CTX_JS by state and not by name.
        CanoeStateProbe script = new CanoeStateProbe().feed("<script src=\"x\">var a;");
        assertEquals(Canoe.SCRIPT, script.state());
        assertNull(script.tagName(), "the script body is not the script tag");
        CanoeStateProbe css = new CanoeStateProbe().feed("<style>p{}");
        assertEquals(Canoe.CSS, css.state());
        assertNull(css.tagName(), "the style body is not the style tag");

        // While the next tag's name is still being read, the previous tag's name is gone.
        assertNull(new CanoeStateProbe().feed("<div id=x><spa").tagName(),
                "a half-read name is no name, not the previous tag's");
    }

    /** Sequential and nested markup: each tag replaces the name, none inherits one. */
    @Test
    public void aLaterTagReplacesTheNameAndNothingLeaksBetweenTags() throws IOException {
        assertEquals("b", new CanoeStateProbe().feed("<a href=\"/x\">text<b id=\"").tagName(),
                "the inner tag's name, not the outer's");
        assertEquals("div",
                new CanoeStateProbe().feed("<script src=\"x\"></script><div id=").tagName(),
                "a script element upstream must not arm a later element's name");
        assertEquals("img",
                new CanoeStateProbe().feed("<iframe src=\"a\"></iframe><img src=\"").tagName(),
                "iframe then img: the R9 distinction these two names will carry");
    }

    /** Comments and DOCTYPEs are not elements and never set the field. */
    @Test
    public void commentsAndDoctypesNeverCarryAName() throws IOException {
        assertNull(new CanoeStateProbe().feed("<!doctype html").tagName(), "inside the DOCTYPE");
        assertNull(new CanoeStateProbe().feed("<!doctype html><p id=x>text<!-- note ").tagName(),
                "inside a comment");
        assertNull(new CanoeStateProbe().feed("<p id=x>a<!-- note -->b").tagName(),
                "after a comment closes");
    }

    // ------------------------------------------------------------------
    // End tags, including the two that never pass through TAG_NAME.
    // ------------------------------------------------------------------

    /** An end tag's name is tracked without its slash, and closingTag says which it was. */
    @Test
    public void anEndTagCarriesItsNameWithoutTheSlash() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<div id=x>text</div ");
        assertEquals("div", probe.tagName());
        assertTrue(probe.closingTag());
        assertNull(new CanoeStateProbe().feed("<div id=x>text</div>after").tagName(),
                "the end tag's '>' clears it like any other");
    }

    /**
     * {@code </script>} and {@code </style>} are matched by SCRIPT_END/CSS_END, which enter the TAG
     * state without ever passing TAG_NAME. The fields are set there too, so the invariant "inside a
     * tag whose name has been read implies tagName holds it" has no exception R9 could fall into:
     * the tail of {@code </script foo=...>} must not look like an opening {@code <script>} tag, and
     * must not look like no tag at all.
     */
    @Test
    public void theScriptAndStyleEndTagsAreNamedDespiteSkippingTagName() throws IOException {
        CanoeStateProbe script = new CanoeStateProbe().feed("<script>var a;</script");
        assertEquals(Canoe.TAG, script.state());
        assertEquals("script", script.tagName());
        assertTrue(script.closingTag());

        CanoeStateProbe css = new CanoeStateProbe().feed("<style>p{}</style");
        assertEquals(Canoe.TAG, css.state());
        assertEquals("style", css.tagName());
        assertTrue(css.closingTag());

        // And the '>' clears both, back to ordinary HTML.
        CanoeStateProbe closed = new CanoeStateProbe().feed("<script>var a;</script>text");
        assertEquals(Canoe.HTML, closed.state());
        assertNull(closed.tagName());
    }

    // ------------------------------------------------------------------
    // No behaviour change: the field is set, and nothing consumes it yet.
    // ------------------------------------------------------------------

    /**
     * The pair R9 exists to separate, rendered today: {@code <script src>} and {@code <img src>}
     * reach the same attribute context and the same encoder while the tracked names differ. This is
     * the same current behaviour {@code UrlSinkTest.everyElementGetsTheSameEncoderForTheSame}
     * {@code AttributeName} pins across nine elements; it is restated here against the new field so
     * that R8's own file says what R8 deliberately did not change. R9 inverts the context half.
     */
    @Test
    public void theNameIsTrackedButNothingConsumesItYet() throws IOException {
        CanoeStateProbe script = new CanoeStateProbe().feed("<script src=\"");
        CanoeStateProbe img = new CanoeStateProbe().feed("<img src=\"");
        assertEquals("script", script.tagName());
        assertEquals("img", img.tagName());
        assertEquals(script.attributeContext(), img.attributeContext(),
                "R8 must not change classification; the name-aware split is R9's");
        assertEquals(script.currentContext(), img.currentContext(),
                "R8 must not change the output context either");
        assertEquals(Canoe.CTX_URI, script.currentContext(),
                "both are still the one URL context F6 lives in until R9");
    }
}
