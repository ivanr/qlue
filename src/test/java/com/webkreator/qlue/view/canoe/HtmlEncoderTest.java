package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaping primitives, swept exhaustively rather than sampled.
 *
 * <p>{@code html()} and {@code htmlWhite()} are allowlists, not denylists, which is what makes body
 * output safe under every one of the review's findings. An allowlist can be tested completely: sweep
 * every code point and assert that exactly the intended set survives. That is what
 * {@link #htmlPassesThroughOnlyAlphanumerics} and its siblings do, and it is worth far more than a
 * hand-picked table of interesting characters, because the interesting characters are the ones
 * nobody thought of.
 *
 * <p>The single most valuable assertion in the suite is
 * {@link #noEncoderCanEverEmitAMarkupDelimiter}. Canoe's entire safety argument — including why F10
 * is not exploitable, and why attacker data can never steer the state machine — rests on encoded
 * output being unable to contain a raw {@code <} or a raw quote. If that ever stops being true,
 * several findings currently rated latent become live at once.
 */
public class HtmlEncoderTest {

    /** Every BMP code point, plus a sample of each astral plane. */
    private static int[] allCodePoints() {
        List<Integer> points = new ArrayList<>(0x11000);
        for (int c = 0; c <= 0xFFFF; c++) {
            points.add(c);
        }
        for (int plane = 1; plane <= 16; plane++) {
            int base = plane << 16;
            points.add(base);
            points.add(base + 1);
            points.add(base + 0x1234);
            points.add(base + 0xFFFF);
        }
        int[] result = new int[points.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = points.get(i);
        }
        return result;
    }

    /**
     * A one-character string from a code unit. Keeps this source file pure ASCII, so it cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset - which matters here more
     * than anywhere else in the suite, because half these assertions are about specific code points.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    private static boolean isAlphanumeric(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    // ------------------------------------------------------------------
    // The property everything else rests on
    // ------------------------------------------------------------------

    /**
     * No encoder may emit a character that can open a tag or terminate an attribute value, for any
     * input whatsoever.
     *
     * <p>{@code <} is the obvious one. The quotes matter just as much: Canoe's {@code TAG_ATTR_VALUE}
     * state ends a value on a matching quote, so an encoder that let one through would let attacker
     * data close an attribute and start a new one — and would let it move the state machine, which
     * the review's corollary says is impossible.
     *
     * <p>{@code js()} and {@code css()} are included even though {@code Canoe.encode()} does not
     * currently call them: the commented-out code at {@code Canoe.java:1074-1081} contemplates
     * turning JS and CSS suppression into real escaping, and this is the property that change would
     * have to preserve.
     */
    @Test
    public void noEncoderCanEverEmitAMarkupDelimiter() {
        List<String> violations = new ArrayList<>();

        for (int c : allCodePoints()) {
            String input = new String(Character.toChars(c));
            checkNoDelimiters("html", HtmlEncoder.html(input), c, violations);
            checkNoDelimiters("htmlWhite", HtmlEncoder.htmlWhite(input), c, violations);
            checkNoDelimiters("url", HtmlEncoder.url(input), c, violations);
            checkNoDelimiters("js", HtmlEncoder.js(input), c, violations);
            checkNoDelimiters("css", HtmlEncoder.css(input), c, violations);
        }

        assertTrue(violations.isEmpty(),
                () -> "Encoders emitted markup delimiters for " + violations.size()
                        + " inputs; the first few: " + violations.subList(0, Math.min(10,
                        violations.size())));
    }

    private static void checkNoDelimiters(String encoder, String encoded, int codePoint,
                                          List<String> violations) {
        // The backtick is here because IE treated it as an attribute-value delimiter. That engine is
        // gone, but the character costs nothing to check and the day an encoder starts emitting one
        // is the day to think about it again rather than to discover it.
        for (char delimiter : new char[]{'<', '>', '"', '`'}) {
            if (encoded.indexOf(delimiter) >= 0) {
                violations.add(encoder + "(U+" + Integer.toHexString(codePoint).toUpperCase()
                        + ") = " + CanoeTestSupport.quote(encoded) + " contains '" + delimiter + "'");
            }
        }

        // js() and css() wrap their output in single quotes by design, so only the interior counts.
        // The wrapper quotes are asserted rather than assumed: stripping the first and last character
        // unconditionally would hide a boundary quote if js() ever stopped wrapping, and this method
        // would then be checking the wrong substring while still passing.
        String interior = encoded;
        if (encoder.equals("js") || encoder.equals("css")) {
            if (encoded.length() < 2 || encoded.charAt(0) != '\''
                    || encoded.charAt(encoded.length() - 1) != '\'') {
                violations.add(encoder + "(U+" + Integer.toHexString(codePoint).toUpperCase()
                        + ") = " + CanoeTestSupport.quote(encoded) + " is not wrapped in the single"
                        + " quotes this check assumes, so its interior cannot be identified");
                return;
            }
            interior = encoded.substring(1, encoded.length() - 1);
        }
        if (interior.indexOf('\'') >= 0) {
            violations.add(encoder + "(U+" + Integer.toHexString(codePoint).toUpperCase()
                    + ") = " + CanoeTestSupport.quote(encoded) + " contains a single quote");
        }
    }

    /**
     * {@code htmlWhite()} is the body-context encoder, and the review's "what is not affected"
     * section turns on it never emitting a {@code <}. Stated separately so that a failure names the
     * consequence rather than the mechanism.
     */
    @Test
    public void bodyOutputCanNeverOpenATag() {
        for (int c : allCodePoints()) {
            String encoded = HtmlEncoder.htmlWhite(new String(Character.toChars(c)));
            assertTrue(encoded.indexOf('<') < 0,
                    () -> "htmlWhite emitted a raw '<' for U+" + Integer.toHexString(c));
        }
    }

    // ------------------------------------------------------------------
    // The allowlists, swept exhaustively
    // ------------------------------------------------------------------

    /** {@code html()} lets exactly the ASCII alphanumerics through untouched. */
    @Test
    public void htmlPassesThroughOnlyAlphanumerics() {
        for (int c : allCodePoints()) {
            String input = new String(Character.toChars(c));
            String encoded = HtmlEncoder.html(input);
            boolean passedThrough = encoded.equals(input);

            assertEquals(isAlphanumeric(c), passedThrough,
                    () -> "html(U+" + Integer.toHexString(c).toUpperCase() + ") = "
                            + CanoeTestSupport.quote(encoded));
        }
    }

    /**
     * {@code htmlWhite()} differs from {@code html()} in exactly one respect: space, tab, CR and LF
     * pass through raw. Asserted as a difference rather than as a second allowlist, so the two
     * cannot drift apart unnoticed.
     */
    @Test
    public void htmlWhiteDiffersFromHtmlOnlyInWhitespace() {
        for (int c : allCodePoints()) {
            String input = new String(Character.toChars(c));
            String html = HtmlEncoder.html(input);
            String htmlWhite = HtmlEncoder.htmlWhite(input);

            boolean isPreservedWhitespace = c == ' ' || c == '\t' || c == '\r' || c == '\n';
            if (isPreservedWhitespace) {
                assertEquals(input, htmlWhite,
                        () -> "htmlWhite must preserve U+" + Integer.toHexString(c));
                assertTrue(!html.equals(input),
                        () -> "html must not preserve U+" + Integer.toHexString(c));
            } else {
                assertEquals(html, htmlWhite,
                        () -> "html and htmlWhite must agree on U+" + Integer.toHexString(c));
            }
        }
    }

    /**
     * The explicitly named conversions. These are the characters the encoder gives a specific form
     * rather than the generic numeric reference, so they are worth pinning by name: a change here
     * would be invisible to the sweeps above, which only ask whether a character was encoded at all.
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> namedReferences() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("<", "&lt;"),
                org.junit.jupiter.params.provider.Arguments.of(">", "&gt;"),
                org.junit.jupiter.params.provider.Arguments.of("&", "&amp;"),
                org.junit.jupiter.params.provider.Arguments.of("\"", "&quot;"),
                org.junit.jupiter.params.provider.Arguments.of("'", "&#39;"),
                org.junit.jupiter.params.provider.Arguments.of("/", "&#47;"),
                org.junit.jupiter.params.provider.Arguments.of("=", "&#61;"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("namedReferences")
    public void usesNamedOrNumericReferencesForTheDangerousCharacters(String input, String expected) {
        assertEquals(expected, HtmlEncoder.html(input));
        assertEquals(expected, HtmlEncoder.htmlWhite(input));
        assertEquals(expected, HtmlEncoder.htmlWhiteLineBreaks(input));
    }

    /**
     * Everything else becomes a decimal numeric reference, always terminated. There is no loose-entity
     * parsing to exploit because the form is always {@code &} + decimal + {@code ;}.
     */
    @Test
    public void everythingElseBecomesATerminatedNumericReference() {
        assertEquals("&#40;", HtmlEncoder.html("("));
        assertEquals("&#41;", HtmlEncoder.html(")"));
        assertEquals("&#59;", HtmlEncoder.html(";"));
        assertEquals("&#58;", HtmlEncoder.html(":"));
        assertEquals("&#32;", HtmlEncoder.html(" "));
        assertEquals("&#233;", HtmlEncoder.html(ch(0xe9)));

        for (int c : allCodePoints()) {
            if (isAlphanumeric(c) || c < 32) {
                continue;
            }
            String encoded = HtmlEncoder.html(new String(Character.toChars(c)));
            if (encoded.startsWith("&#")) {
                assertTrue(encoded.endsWith(";"),
                        () -> "unterminated reference for U+" + Integer.toHexString(c) + ": "
                                + encoded);
            }
        }
    }

    /**
     * C0 controls other than the preserved whitespace are rendered as the four <em>literal</em>
     * characters {@code \xNN} — visible text, not a character reference. That is a deliberate
     * "make it visible" choice, and it has a consequence the review did not note: the backslash it
     * introduces is not a valid URL scheme character, which is the accident that neutralises the
     * tab- and newline-split {@code javascript:} payloads in an attribute {@code html()} encodes.
     */
    @Test
    public void controlCharactersBecomeLiteralBackslashXText() {
        assertEquals("\\x00", HtmlEncoder.html(ch(0x00)));
        assertEquals("\\x01", HtmlEncoder.html(ch(0x01)));
        assertEquals("\\x1F", HtmlEncoder.html(ch(0x1f)));
        assertEquals("\\x0B", HtmlEncoder.html(ch(0x0b)));

        // htmlWhite preserves tab, CR and LF but treats the rest the same way.
        assertEquals("\t", HtmlEncoder.htmlWhite("\t"));
        assertEquals("\\x00", HtmlEncoder.htmlWhite(ch(0x00)));
        assertEquals("\\x0B", HtmlEncoder.htmlWhite(ch(0x0b)));

        // DEL is not below 0x20, so it takes the numeric-reference branch instead.
        assertEquals("&#127;", HtmlEncoder.html(ch(0x7f)));
    }

    // ------------------------------------------------------------------
    // F16: js() and css(), which nothing calls and nothing tested
    // ------------------------------------------------------------------

    /**
     * {@code js()} is an allowlist too, and lets exactly the ASCII alphanumerics through naked.
     *
     * <p>Two surfaces, and only one of them is latent. Neither encoder is reachable from
     * {@code Canoe.encode()} — {@code CTX_JS} and {@code CTX_CSS} both map to the empty string — so
     * the <em>injection</em> risk is latent, and becomes live only if the commented-out code at
     * {@code Canoe.java:1074-1081} is uncommented. The <em>corruption</em> is live today:
     * {@code HtmlEncoder implements QlueVelocityTool} with {@code getName()} returning {@code _x},
     * and {@code CanoeReferenceInsertionHandler} passes any {@code $_x.} reference through
     * unencoded, so {@code $_x.js(...)} and {@code $_x.css(...)} are callable from any template in
     * the application right now and every wrong value below reaches real output.
     *
     * <p>They are tested here because {@link #noEncoderCanEverEmitAMarkupDelimiter} already sweeps
     * them and that sweep passes — but it only asks whether the output contains a delimiter, not
     * whether it means what the input said. Both of these encoders fail the second question, which
     * is F16.
     */
    @Test
    public void jsPassesThroughOnlyAlphanumerics() {
        for (int c : allCodePoints()) {
            String input = new String(Character.toChars(c));
            String interior = interiorOf(HtmlEncoder.js(input));
            boolean passedThrough = interior.equals(input);

            assertEquals(isAlphanumeric(c), passedThrough,
                    () -> "js(U+" + Integer.toHexString(c).toUpperCase() + ") = "
                            + CanoeTestSupport.quote(HtmlEncoder.js(input)));
        }
    }

    /** {@code css()} allows the same set, and nothing else. */
    @Test
    public void cssPassesThroughOnlyAlphanumerics() {
        for (int c : allCodePoints()) {
            String input = new String(Character.toChars(c));
            String interior = interiorOf(HtmlEncoder.css(input));
            boolean passedThrough = interior.equals(input);

            assertEquals(isAlphanumeric(c), passedThrough,
                    () -> "css(U+" + Integer.toHexString(c).toUpperCase() + ") = "
                            + CanoeTestSupport.quote(HtmlEncoder.css(input)));
        }
    }

    /**
     * Every {@code js()} escape is one of the two fixed-width forms {@code \xNN} and {@code \\uNNNN},
     * so there is no truncated-escape parsing to exploit — the same property
     * {@code everyEscapeIsExactlyTwoUppercaseHexDigits} asserts for {@code url()}.
     *
     * <p>Fixed width is exactly why {@code js()} is not an injection despite F16: a wrong escape is
     * still a well-formed escape, so it can produce the wrong <em>character</em> but it can never
     * terminate the string literal.
     */
    @Test
    public void everyJsEscapeIsAFixedWidthHexForm() {
        for (int c : allCodePoints()) {
            if (isAlphanumeric(c)) {
                continue;
            }
            final String interior = interiorOf(HtmlEncoder.js(new String(Character.toChars(c))));
            int expectedLength = c <= 127 ? 4 : 6;
            String expectedPrefix = c <= 127 ? "\\x" : "\\u";

            assertEquals(expectedLength, interior.length(),
                    () -> "js escape for U+" + Integer.toHexString(c).toUpperCase()
                            + " is not fixed width: " + CanoeTestSupport.quote(interior));
            assertTrue(interior.startsWith(expectedPrefix),
                    () -> "js escape for U+" + Integer.toHexString(c).toUpperCase()
                            + " uses the wrong form: " + CanoeTestSupport.quote(interior));
            assertTrue(interior.substring(2).matches("[0-9A-F]+"),
                    () -> "js escape for U+" + Integer.toHexString(c).toUpperCase()
                            + " is not uppercase hex: " + CanoeTestSupport.quote(interior));
        }
    }

    /**
     * <strong>F16, first half.</strong> {@code js()} builds its {@code \\u} escape from
     * {@code hex(c >> 8)} and {@code hex(c)}, and {@code hex()} emits only the low byte of what it is
     * given. That is four hex digits from the low <em>sixteen</em> bits of the code point, so every
     * astral code point is silently truncated to a different character.
     *
     * <p>Not an injection: the escape is still well formed and the resulting character is still
     * inside the string literal. But the value is wrong, and two of the collisions are the kind of
     * wrong that would matter to anyone reasoning about the output — U+10027 becomes an apostrophe
     * and U+1005C becomes a backslash, both of which are syntactically significant in JavaScript.
     * They are harmless <em>here</em> only because they arrive as {@code \\u0027} and {@code \\u005C}
     * rather than as raw characters, which is a much narrower escape than it looks.
     */
    @Test
    public void jsTruncatesAstralCodePointsToTheirLowSixteenBits() {
        // The grinning face becomes a private-use character.
        assertEquals("'\\uF600'", HtmlEncoder.js(new String(Character.toChars(0x1F600))),
                "F16: U+1F600 emitted as U+F600 - hex(c >> 8) keeps only the low byte");

        // And two collisions land on characters JavaScript cares about.
        assertEquals("'\\u0027'", HtmlEncoder.js(new String(Character.toChars(0x10027))),
                "F16: U+10027 silently becomes an apostrophe");
        assertEquals("'\\u005C'", HtmlEncoder.js(new String(Character.toChars(0x1005C))),
                "F16: U+1005C silently becomes a backslash");
        assertEquals("'\\u0000'", HtmlEncoder.js(new String(Character.toChars(0x10000))),
                "F16: U+10000 silently becomes a NUL");

        // The BMP is encoded correctly, which is why this survived: only astral input is wrong.
        assertEquals("'\\u2028'", HtmlEncoder.js(ch(0x2028)));
        assertEquals("'\\u00FF'", HtmlEncoder.js(ch(0xff)));
        assertEquals("'\\x7F'", HtmlEncoder.js(ch(0x7f)));

        // Stated as a property: every astral code point is emitted as the four hex digits of its low
        // sixteen bits, which is to say the high bits are simply discarded. Compared against the
        // escape rather than against js() of the BMP code point, because a truncation landing below
        // U+0080 takes the two-digit form there and the four-digit one here: different spelling of
        // the same character, so the two outputs differ even though the code points collide.
        for (int c : allCodePoints()) {
            if (c <= 0xFFFF) {
                continue;
            }
            String expected = "'\\u" + upperHex4(c & 0xFFFF) + "'";
            assertEquals(expected, HtmlEncoder.js(new String(Character.toChars(c))),
                    () -> "F16: U+" + Integer.toHexString(c).toUpperCase()
                            + " must lose everything above its low sixteen bits");
        }
    }

    private static String upperHex4(int value) {
        String hex = Integer.toHexString(value).toUpperCase();
        return "0000".substring(hex.length()) + hex;
    }

    /**
     * <strong>F16, second half, and the worse one.</strong> {@code css()} escapes a character as a
     * backslash and two hex digits with no terminator. CSS hex escapes are variable length — up to
     * six digits, ended by a space or by the first non-hex character — so the escape swallows any hex
     * digit that follows it.
     *
     * <p>{@code css("'a")} produces {@code '\27a'}, which a CSS parser reads as the single character
     * U+027A rather than as an apostrophe followed by {@code a}. This is the classic unterminated-
     * hex-escape bug. It is not an injection as written — the swallowed character makes the value
     * wrong, not the delimiter reachable — but it is one relaxation away from being one, and it is
     * why {@code css()} must not be wired up as it stands.
     *
     * <p>The fix is to emit six digits, or to append a terminating space.
     */
    @Test
    public void cssHexEscapesAreUnterminatedAndSwallowTheNextCharacter() {
        assertEquals("'\\27a'", HtmlEncoder.css("'a"),
                "F16: CSS reads \\27a as U+027A, not as an apostrophe followed by 'a'");
        assertEquals("'\\3Ca'", HtmlEncoder.css("<a"),
                "F16: CSS reads \\3Ca as U+03CA, not as '<' followed by 'a'");

        // A following non-hex letter is safe, which is why casual testing does not find this.
        assertEquals("'\\27z'", HtmlEncoder.css("'z"));
        // ...and so is a following escape, because the backslash ends the previous one.
        assertEquals("'\\27\\27'", HtmlEncoder.css("''"));

        // Above Latin-1 css() gives up entirely and emits a literal '?'. url() used to do the same
        // (F15d); R12 UTF-8 percent-encodes instead, so css() is the only encoder left that does it.
        assertEquals("'?'", HtmlEncoder.css(ch(0x100)));
        assertEquals("'?'", HtmlEncoder.css(new String(Character.toChars(0x1F600))));
        assertEquals("'\\FF'", HtmlEncoder.css(ch(0xff)), "Latin-1 is escaped one byte at a time");
    }

    /**
     * Both encoders wrap the whole value in one pair of quotes and escape each code point
     * independently, so a multi-character input is the concatenation of the single-character
     * interiors. Worth stating because every other test in this section works one character at a
     * time, and a per-character bug that only appeared in strings would slip through all of them.
     */
    @Test
    public void jsAndCssEscapeMultiCharacterInputCodePointWise() {
        String input = "a<b" + ch(0xe9) + "'" + new String(Character.toChars(0x1F600));

        StringBuilder expectedJs = new StringBuilder("'");
        StringBuilder expectedCss = new StringBuilder("'");
        input.codePoints().forEach(c -> {
            String one = new String(Character.toChars(c));
            expectedJs.append(interiorOf(HtmlEncoder.js(one)));
            expectedCss.append(interiorOf(HtmlEncoder.css(one)));
        });

        assertEquals(expectedJs.append('\'').toString(), HtmlEncoder.js(input));
        assertEquals(expectedCss.append('\'').toString(), HtmlEncoder.css(input));

        // Concretely, and with the wrapper quotes visible.
        assertEquals("'a\\x3Cb\\u00E9\\x27\\uF600'", HtmlEncoder.js(input));
        assertEquals("'a\\3Cb\\E9\\27?'", HtmlEncoder.css(input));
    }

    /**
     * Strips the wrapper quotes {@code js()} and {@code css()} add, asserting they are there first so
     * that a change to the wrapping is a named failure rather than a silently misaligned substring.
     */
    private static String interiorOf(String encoded) {
        assertTrue(encoded.length() >= 2 && encoded.charAt(0) == '\''
                        && encoded.charAt(encoded.length() - 1) == '\'',
                () -> "expected single-quote wrapping, got " + CanoeTestSupport.quote(encoded));
        return encoded.substring(1, encoded.length() - 1);
    }

    // ------------------------------------------------------------------
    // Unicode edges
    // ------------------------------------------------------------------

    /** Astral code points emit one reference each, not one per surrogate. */
    @Test
    public void astralCodePointsEmitASingleReference() {
        String grinningFace = new String(Character.toChars(0x1F600));
        String cjkExtensionB = new String(Character.toChars(0x20000));

        assertEquals("&#128512;", HtmlEncoder.html(grinningFace));
        assertEquals("&#128512;", HtmlEncoder.htmlWhite(grinningFace));
        assertEquals("&#131072;", HtmlEncoder.html(cjkExtensionB));
    }

    /**
     * A lone surrogate emits its own numeric reference, which browsers replace with U+FFFD. Mangled
     * rather than injectable, which is the property that matters.
     */
    @Test
    public void loneSurrogatesAreMangledNotInjectable() {
        // Unpaired surrogates are emitted as their own code unit values, which are not valid
        // characters; browsers substitute U+FFFD. Mangled, not injectable.
        assertEquals("&#55296;", HtmlEncoder.html(ch(0xd800)));
        assertEquals("&#56320;", HtmlEncoder.html(ch(0xdc00)));

        // A well-formed pair is a single code point, and is encoded as one reference.
        assertEquals("&#65536;", HtmlEncoder.html(ch(0xd800) + ch(0xdc00)));
    }

    /** U+2028 and U+2029 terminate a line in JavaScript source, so they must not pass through. */
    @Test
    public void lineSeparatorsAreEncoded() {
        assertEquals("&#8232;", HtmlEncoder.html(ch(0x2028)));
        assertEquals("&#8233;", HtmlEncoder.html(ch(0x2029)));
        assertEquals("&#8232;", HtmlEncoder.htmlWhite(ch(0x2028)),
                "htmlWhite preserves only space, tab, CR and LF - not Unicode line separators");
    }

    // ------------------------------------------------------------------
    // htmlWhiteLineBreaks
    // ------------------------------------------------------------------

    /**
     * The one encoder that deliberately emits markup. It converts LF to a literal {@code <br>}, so
     * it is the single exception to the no-raw-{@code <} property — which is exactly why it must
     * never be reachable from {@code Canoe.encode()}.
     */
    @Test
    public void htmlWhiteLineBreaksEmitsMarkupAndIsNotReachableFromCanoe() {
        assertEquals("a<br>b", HtmlEncoder.htmlWhiteLineBreaks("a\nb"));
        assertEquals("ab", HtmlEncoder.htmlWhiteLineBreaks("a\rb"), "CR is dropped");
        assertEquals("a\tb", HtmlEncoder.htmlWhiteLineBreaks("a\tb"), "tab is preserved");

        // Canoe.encode() maps its six contexts to htmlWhite, html, url and the empty string only.
        for (int context : new int[]{0, 1, 2, 3, 4, 5}) {
            String encoded = CanoeTestSupport.encodeFor("a\nb", context);
            assertTrue(encoded.indexOf('<') < 0,
                    "Canoe.encode() must never route through htmlWhiteLineBreaks: context "
                            + context + " produced " + CanoeTestSupport.quote(encoded));
        }
    }

    /**
     * {@code htmlWhiteLineBreaks()} is an allowlist everywhere except at LF, and the allowlist is
     * swept the same way {@code htmlWhite()}'s is.
     *
     * <p>Added by T30 because the test above was the only one this encoder had, and its three inputs
     * were a letter, a tab, a CR and an LF — so the whole default arm, including the C0 branch, had
     * never been evaluated. The encoder that is allowed to emit markup is the last one that should
     * be tested only on the characters somebody expected.
     *
     * <p>The property asserted is {@code htmlWhiteLineBreaks(s)} equals {@code htmlWhite(s)} for
     * every code point except CR and LF, which is the strongest form available: it borrows the
     * exhaustive sweep {@code htmlWhite()} already has instead of restating an allowlist that could
     * drift from it.
     */
    @Test
    public void htmlWhiteLineBreaksAgreesWithHtmlWhiteEverywhereButTheLineBreaks() {
        for (int c : allCodePoints()) {
            if (c == '\r' || c == '\n') {
                continue;
            }
            String input = new String(Character.toChars(c));
            assertEquals(HtmlEncoder.htmlWhite(input), HtmlEncoder.htmlWhiteLineBreaks(input),
                    () -> "htmlWhiteLineBreaks diverges from htmlWhite at U+"
                            + String.format("%04X", c));
        }

        // ...and at the two it does differ on, stated as literals rather than left to the loop.
        assertEquals("<br>", HtmlEncoder.htmlWhiteLineBreaks("\n"));
        assertEquals("", HtmlEncoder.htmlWhiteLineBreaks("\r"), "CR is dropped, not encoded");
        assertEquals("\n", HtmlEncoder.htmlWhite("\n"), "htmlWhite passes it through raw instead");
        assertEquals("\r", HtmlEncoder.htmlWhite("\r"), "and keeps the CR");
    }

    /**
     * The {@code (String, StringBuilder)} overloads append nothing for a null input.
     *
     * <p>{@link #nullInputReturnsNullThroughout} covers the {@code String}-returning forms, which
     * return null. The appending forms cannot return anything, so their contract is that the builder
     * is left untouched — and until T30 measured it, not one of these four null guards had ever been
     * evaluated as true. They are public API: {@code $_x} exposes this class to templates.
     *
     * <p>The appending {@code css(String, StringBuilder)} overload is absent here: it is private, and
     * its null guard is <em>unreachable</em>, because the public {@code css(String)} returns null
     * before calling it and nothing else calls it. R12 removed {@code url()}'s private appending
     * overload entirely — the rewrite parses and re-emits per component rather than recursing through
     * a {@code (String, StringBuilder)} worker — so there is no {@code url} null guard left to reach.
     */
    @Test
    public void theAppendingOverloadsAppendNothingForNull() {
        StringBuilder sb = new StringBuilder("prefix");

        HtmlEncoder.html((String) null, sb);
        HtmlEncoder.htmlWhite((String) null, sb);
        HtmlEncoder.htmlWhiteLineBreaks((String) null, sb);
        HtmlEncoder.js((String) null, sb);

        assertEquals("prefix", sb.toString(),
                "a null input must leave the builder exactly as it was; note that js() would"
                        + " otherwise have appended its opening quote");
    }

    // ------------------------------------------------------------------
    // Contracts
    // ------------------------------------------------------------------

    @Test
    public void nullInputReturnsNullThroughout() {
        assertNull(HtmlEncoder.html(null));
        assertNull(HtmlEncoder.htmlWhite(null));
        assertNull(HtmlEncoder.htmlWhiteLineBreaks(null));
        assertNull(HtmlEncoder.js(null));
        assertNull(HtmlEncoder.css(null));
        assertNull(HtmlEncoder.url(null));
        assertNull(HtmlEncoder.asis(null));
    }

    @Test
    public void emptyInputReturnsEmptyOrTheBareQuotes() {
        assertEquals("", HtmlEncoder.html(""));
        assertEquals("", HtmlEncoder.htmlWhite(""));
        assertEquals("", HtmlEncoder.url(""));
        assertEquals("''", HtmlEncoder.js(""), "js() always wraps its output in quotes");
        assertEquals("''", HtmlEncoder.css(""), "css() does too");
    }

    /** {@code asis()} is the documented escape hatch and must not touch its input. */
    @Test
    public void asisIsTheIdentity() {
        String hostile = "<script>alert(1)</script>";
        assertSame(hostile, HtmlEncoder.asis(hostile));
    }

    /** {@code htmlAttr()} is an alias for {@code html()}; the review's analysis depends on it. */
    @Test
    public void htmlAttrIsHtml() {
        String[] inputs = {"", "abc", "<>&\"'/=", " \t\r\n",
                ch(0xe9) + new String(Character.toChars(0x1F600))};
        for (String input : inputs) {
            assertEquals(HtmlEncoder.html(input), HtmlEncoder.htmlAttr(input));
        }
    }

    /**
     * Encoding is not idempotent, and must not be: {@code &} becomes {@code &amp;}, so encoding
     * twice produces {@code &amp;lt;} where the browser renders a literal {@code &lt;}. That is the
     * mechanism behind F12's double-encoded output, and it is worth pinning so that a future
     * "optimisation" to skip already-encoded input is recognised as the security change it would be.
     */
    @Test
    public void encodingTwiceDoubleEncodes() {
        // Note the ';' is itself encoded on the second pass, so the result is not merely the
        // first pass with its ampersand escaped.
        assertEquals("&amp;lt&#59;", HtmlEncoder.html(HtmlEncoder.html("<")));
        assertEquals("&amp;&#35;39&#59;", HtmlEncoder.html(HtmlEncoder.html("'")));
    }

    /** Each encoder handles a multi-character string as the concatenation of its code points. */
    @Test
    public void encodersAreCodePointWise() {
        List<Function<String, String>> encoders = List.of(
                HtmlEncoder::html, HtmlEncoder::htmlWhite, HtmlEncoder::htmlWhiteLineBreaks);
        String input = "a<b" + ch(0xe9) + new String(Character.toChars(0x1F600));

        for (Function<String, String> encoder : encoders) {
            StringBuilder piecewise = new StringBuilder();
            input.codePoints().forEach(c -> piecewise.append(
                    encoder.apply(new String(Character.toChars(c)))));
            assertEquals(piecewise.toString(), encoder.apply(input));
        }
    }
}
