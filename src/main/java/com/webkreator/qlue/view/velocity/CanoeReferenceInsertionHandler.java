/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.view.Canoe;
import org.apache.velocity.app.event.ReferenceInsertionEventHandler;
import org.apache.velocity.context.Context;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * This class is a bridge between Canoe and Velocity.
 */
public class CanoeReferenceInsertionHandler implements ReferenceInsertionEventHandler {

    public static final String SAFE_REFERENCE_NAME = "_x";

    public static final String SAFE_REFERENCE_PREFIX1 = "$" + SAFE_REFERENCE_NAME + ".";

    public static final String SAFE_REFERENCE_PREFIX2 = "$!" + SAFE_REFERENCE_NAME + ".";

    /** Velocity's formal notation for {@link #SAFE_REFERENCE_PREFIX1}: <code>${_x.…}</code>. */
    public static final String SAFE_REFERENCE_PREFIX3 = "${" + SAFE_REFERENCE_NAME + ".";

    /** Velocity's formal notation for {@link #SAFE_REFERENCE_PREFIX2}: <code>$!{_x.…}</code>. */
    public static final String SAFE_REFERENCE_PREFIX4 = "$!{" + SAFE_REFERENCE_NAME + ".";

    /**
     * Every spelling of the bypass, and deliberately nothing more.
     *
     * <p>Velocity hands {@code referenceInsert()} the reference's <em>source text</em>, and
     * {@code ASTReference.getRoot()} recognises exactly four ways to open a reference:
     * <code>$name</code>, <code>$!name</code>, <code>${name}</code> and <code>$!{name}</code>
     * (velocity-engine-core 2.4.1, {@code ASTReference.java}, the <code>startsWith("$!{")</code> /
     * <code>equals("${")</code> / <code>startsWith("$")</code> chain). This list is those four with
     * {@link #SAFE_REFERENCE_NAME} and the method-call dot substituted in, so all four spellings of
     * the bypass behave identically and none of them fails silently.
     *
     * <p>There is no fifth spelling to add. Whitespace inside the braces —
     * <code>${ _x.asis($v) }</code>, or a newline after the brace — is not a reference at all:
     * Velocity's lexer only enters the reference state on the exact token <code>${</code> or
     * <code>$!{</code>, so the whole thing is emitted as literal template text and this handler
     * never fires for it. <code>${_x .asis($v)}</code> is a parse error. Leading schmoo is stripped
     * before the literal is built — <code>#$_x.asis($v)</code> arrives here as
     * <code>$_x.asis($v)</code>, <code>\\${_x.asis($v)}</code> as <code>${_x.asis($v)}</code> — and a
     * preceding block comment contributes nothing, so neither hides the prefix. The one branch of
     * {@code getRoot()} that sits <em>before</em> that chain is the <code>\!</code> "slashbang" case,
     * which Velocity's own comment calls not a reference at all; it can reach this method, but its
     * literal always still carries the backslash (<code>$\!_x.asis($v)</code>), so it cannot match a
     * prefix. A RUNT returns from {@code render()} before the event cartridge runs.
     *
     * <p><strong>A prefix list, and not a matcher, is the point.</strong> This is the one place in
     * Canoe where a match means "emit attacker-reachable data unencoded", so the cost of the two
     * error directions is wildly asymmetric: a missed spelling encodes something twice, which is
     * visible and harmless, while a spurious match is XSS. Anything cleverer — trimming, a regular
     * expression, a lenient name comparison — buys convenience with that asymmetry pointing the
     * wrong way. Note that the trailing dot is load-bearing: it is what keeps <code>$_xyz.foo()</code>
     * and <code>${_xtra}</code> off this list, because they differ from the bypass at the character
     * where the dot would be.
     */
    private static final List<String> SAFE_REFERENCE_PREFIXES = List.of(
            SAFE_REFERENCE_PREFIX1, SAFE_REFERENCE_PREFIX2,
            SAFE_REFERENCE_PREFIX3, SAFE_REFERENCE_PREFIX4);

    /**
     * Velocity's node for a double-quoted string literal, which is the only place a nested render
     * can begin. Compared by name so that no class reference is retained by the stack walk.
     */
    static final String STRING_LITERAL_CLASS =
            "org.apache.velocity.runtime.parser.node.ASTStringLiteral";

    /** The package every frame between this handler and that node belongs to. */
    static final String VELOCITY_PACKAGE = "org.apache.velocity.";

    /**
     * The directives that call {@code ASTStringLiteral.value()} for something other than text on its
     * way to the page — and for which, therefore, the encoding must <em>not</em> be deferred.
     *
     * <p>Deferring is only safe because the value is going to be printed through a reference later,
     * and encoded there. These three never print it:
     *
     * <ul>
     *   <li>{@code Evaluate} parses the string as VTL and renders the result. A deferred value would
     *       make attacker data into template source: {@code #evaluate("…$data…")} with a payload of
     *       <code>#set($injected = 1)$injected</code> would render {@code 1}. That is server-side
     *       template injection, which is a strictly worse outcome than the XSS this class exists to
     *       prevent.
     *   <li>{@code Parse} uses the string as a template name and then parses and renders that
     *       template.
     *   <li>{@code Include} uses it as a resource name and copies the resource's bytes to the writer
     *       unparsed. Both of those turn a deferred value into attacker-chosen path traversal, and
     *       {@code #parse} turns the chosen file into template source as well.
     * </ul>
     *
     * <p>Encoding is the right answer for all three. It neutralises them because
     * {@code html()} and {@code htmlWhite()} are allowlists of {@code [a-zA-Z0-9]} plus a little
     * whitespace: <code>$</code>, <code>#</code>, <code>(</code>, <code>.</code> and <code>/</code>
     * all come back as numeric character references, so neither VTL nor a path can be reconstituted
     * from the output.
     *
     * <p><strong>None of this closes the underlying hole, and it is not meant to.</strong> The plain
     * spellings — {@code #set($t = $data)#evaluate($t)}, {@code #parse($data)} — hand these
     * directives the raw value and always have, because a bare reference assignment and a bare
     * reference argument never reach {@code value()} through a literal and so never fire this
     * handler at all. The threat model is the answer there: the attacker controls data and never the
     * template, and a directive whose argument is compiled or resolved to a file is outside what an
     * output encoder can defend. What this list does is decline to <em>widen</em> that hole to a
     * spelling that did not have it.
     *
     * <p>The remaining {@code value()} callers are deliberately absent, because each of them does
     * hand the string on to something that prints it through a reference: {@code ASTSetDirective}
     * (the shape the deferral exists for), {@code VelocimacroProxy} (a macro argument, printed in the
     * macro body), {@code Foreach} (the iterable), {@code Break} and {@code Stop} (a scope object),
     * {@code ASTMethod} (an application method's argument, whose return value is printed through the
     * enclosing reference — and which wants the value as the user typed it, not HTML-encoded) and
     * the comparison and expression nodes, which never reach a writer at all.
     *
     * <p><strong>Why a deny-list and not an allow-list</strong>, given that everything else in this
     * class resolves an unknown to the safe answer. The set of {@code value()} callers is closed and
     * was read off the source rather than guessed, and exactly three of them do not print — so the
     * deny-list is exact, while an allow-list would have to enumerate every expression node that can
     * hold a literal and would silently stop deferring in each shape it missed. What the deny-list
     * cannot see is a <em>custom</em> directive that compiles or resolves its literal argument; a
     * {@code userdirective} is application code, and the threat model owns it for the same reason it
     * owns {@code #evaluate($t)}. It is also blind to a repackaged Velocity, but harmlessly: a shaded
     * class name matches neither {@link #VELOCITY_PACKAGE} nor {@link #STRING_LITERAL_CLASS}, so
     * nothing is ever deferred and the value is simply encoded here as well as where it is printed.
     */
    static final List<String> LITERAL_CONSUMERS_THAT_DO_NOT_PRINT = List.of(
            "org.apache.velocity.runtime.directive.Evaluate",
            "org.apache.velocity.runtime.directive.Parse",
            "org.apache.velocity.runtime.directive.Include");

    /**
     * The backstop on the walk. Measured against velocity-engine-core 2.4.1: the node sits 5 frames
     * below {@code referenceInsert()} for a plain interpolated {@code #set}, and 10 for the deepest
     * shape the suite renders (a macro invoked inside the literal). 64 leaves room for roughly ten
     * further levels of nesting, and truncating the walk answers "encode", which is the safe
     * direction.
     */
    static final int MAX_FRAMES = 64;

    /** Stateless and thread-safe; {@code getInstance()} with no options retains no class refs. */
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    protected Canoe qlueWriter;

    public CanoeReferenceInsertionHandler(Canoe qlueWriter) {
        this.qlueWriter = qlueWriter;
    }

    /**
     * Is this reference being rendered into an interpolated string literal that some later reference
     * will print — in which case the encoding belongs there and not here?
     *
     * <p>Velocity renders <code>#set($msg = "Hello $name")</code> by calling
     * {@code ASTStringLiteral.value()}, which allocates a private {@code StringBuilderWriter} and
     * renders the literal's node tree into <em>that</em>. The event cartridge is attached to the
     * context rather than to the writer, so {@code referenceInsert()} still fires — but the writer
     * the value is about to be written to is not the {@link Canoe}, and
     * {@code qlueWriter.currentContext()} is therefore an answer about somewhere else entirely: the
     * position the main output stream happened to have reached when the {@code #set} ran. Encoding
     * for it is wrong twice over, because the value is encoded again for the position it is really
     * printed at.
     *
     * <p><strong>Why the stack and not the context.</strong> Measured in velocity-engine-core 2.4.1
     * rather than assumed: {@code InternalContextAdapter} — through
     * {@code InternalHousekeepingContext}, {@code InternalWrapperContext} and
     * {@code InternalEventContext} — exposes a template-name stack, a macro-name stack, the
     * introspection cache and the current {@code Resource}, and <em>nothing about which node is
     * evaluating</em>. {@code ASTStringLiteral} has no {@code render(context, writer)} at all;
     * interpolation exists only inside {@code value(InternalContextAdapter)}, and
     * {@code evaluate()} routes through {@code value()} too. {@code ASTReference.render()} writes to
     * whichever writer it was handed and never shows it to the handler. So the one place the
     * information exists is the call stack, where the frame is not merely a hint: a live
     * {@code ASTStringLiteral} frame below this one <em>is</em> the {@code StringBuilderWriter}
     * whose {@code toString()} becomes the {@code #set} variable.
     *
     * <p><strong>And the frame directly below that one says what becomes of the string.</strong>
     * Deferring is only correct because the string is going to be printed through a reference later
     * and encoded there, which is a claim about the literal's <em>consumer</em>, not about the
     * literal. {@code ASTStringLiteral.value()} is called from exactly one frame, so the frame below
     * it is by construction that consumer, and three of them never print the string:
     * {@link #LITERAL_CONSUMERS_THAT_DO_NOT_PRINT} is the list and carries the reasoning. Reading one
     * frame further is what keeps {@code #evaluate("…$data…")} from compiling attacker data as VTL
     * and {@code #parse("$data")} from resolving it to a file, and it is deliberately one frame
     * rather than "anywhere below": a {@code #set} inside a {@code #parse}d template — the commonest
     * shape there is — has {@code Parse} on its stack too, four frames below the literal, and it is a
     * perfectly ordinary nested render that must still defer.
     *
     * <p><strong>The asymmetry, which is the safety argument.</strong> A false negative — failing to
     * spot a nested render — encodes the value here as well as where it is printed: visible and
     * harmless. A false positive
     * returns attacker-controlled data to Velocity unencoded, and if that value is not later written
     * through a reference it is raw data in the page. The two errors are not comparable, so this
     * method must never widen the "defer" answer: every bound below — the frame limit, the package
     * boundary, the exact class-name comparison, an unrecognised or unreadable consumer — resolves an
     * uncertain stack to <em>encode</em>.
     *
     * <p><strong>The bound.</strong> The walk stops at the first literal, but the common case is that
     * there is none, and walking to the bottom of a servlet stack on every reference is not free. The walk
     * therefore skips to the first {@code org.apache.velocity.} frame and stops at the first frame
     * after that run ends. Nothing is lost: every frame between this handler and an
     * {@code ASTStringLiteral} belongs to that package — {@code EventCartridge},
     * {@code EventHandlerUtil}, {@code ASTReference}, {@code SimpleNode}, and for the deeper shapes
     * {@code VelocimacroProxy}, {@code Parse}, {@code Foreach} and the other directives, all of which
     * pass the literal's {@code StringBuilderWriter} down unchanged. Verified by rendering each of
     * those shapes and reading the frames: below {@code referenceInsert()} the node is 5 frames down
     * for a bare literal and for an interpolated macro argument, 8 through a {@code #parse}d fragment
     * inside one, 9 through a {@code #foreach} inside one and 10 through a macro invoked inside one —
     * and in every case inside the first unbroken run of Velocity frames. A frame outside the package
     * can only be a caller that entered Velocity, and a literal below <em>it</em> belongs to an outer
     * render whose writer this reference is not being written to.
     *
     * <p>What that costs when the answer is "no", which is the usual answer: the walk reads the run
     * and one frame past it — 11 frames for a reference in ordinary template text, 20 for one inside
     * a macro inside a {@code #foreach}. Without the boundary it would read to the bottom of the
     * stack: about a hundred frames under JUnit, more under a servlet container.
     *
     * <p>Package-private rather than private so that {@code NestedRenderDetectionTest} can time the
     * real walk; {@link #encodingMustBeDeferred(Stream)} below is where its behaviour is asserted.
     *
     * @return {@code true} only if the value is provably going somewhere other than Canoe, and
     *         provably on its way back out through a reference that will encode it
     */
    static boolean encodingMustBeDeferred() {
        return STACK_WALKER.walk(
                frames -> encodingMustBeDeferred(frames.map(StackWalker.StackFrame::getClassName)));
    }

    /**
     * The decision above, over a stream of class names, so that the bound can be tested exactly
     * rather than timed. Package-private for {@code NestedRenderDetectionTest}, which feeds it
     * synthetic stacks and counts how many names it consumes.
     *
     * <p>The stream is consumed lazily and in order, so the operators are the bounds: {@code limit}
     * caps the work, {@code dropWhile} skips this class's own frames, and {@code takeWhile} ends the
     * walk where the Velocity frames end. Within that run the first {@code ASTStringLiteral} frame
     * is the innermost literal being interpolated — the one whose writer this reference is being
     * written to — and the frame after it is its consumer.
     *
     * <p>Every way out of this method that is not "a literal, with a consumer that prints it" answers
     * {@code false}, which is the encoding answer: no literal in the run, a literal below the run, a
     * literal past the frame limit, a literal whose consumer is off the end of the run, and a literal
     * whose consumer is on {@link #LITERAL_CONSUMERS_THAT_DO_NOT_PRINT}.
     */
    static boolean encodingMustBeDeferred(Stream<String> classNames) {
        Iterator<String> run = classNames
                .limit(MAX_FRAMES)
                .dropWhile(name -> !name.startsWith(VELOCITY_PACKAGE))
                .takeWhile(name -> name.startsWith(VELOCITY_PACKAGE))
                .iterator();

        while (run.hasNext()) {
            if (STRING_LITERAL_CLASS.equals(run.next())) {
                // The frame below the literal called value() on it, so it is what becomes of the
                // string. If the run ended at the literal we cannot say what that is, and an
                // unidentified consumer resolves to encoding like every other uncertainty here.
                return run.hasNext() && !LITERAL_CONSUMERS_THAT_DO_NOT_PRINT.contains(run.next());
            }
        }
        return false;
    }

    @Override
    public Object referenceInsert(Context context, String arg0, Object arg1) {
        // We ignore references that start with one of the prefixes we consider to be safe. This
        // allows developers to bypass the automatic encoding mechanism and prepare output
        // themselves. All four spellings Velocity accepts are on the list; see
        // SAFE_REFERENCE_PREFIXES for why it is a list of literal prefixes and not a matcher.
        for (String prefix : SAFE_REFERENCE_PREFIXES) {
            if (arg0.startsWith(prefix)) {
                return arg1;
            }
        }

        // Give up if there's nothing to output
        if (arg1 == null) {
            return null;
        }

        // A reference inside an interpolated string literal is not being written to Canoe at all, so
        // this writer's context is an answer about somewhere else. Hand the value back untouched
        // and let it be encoded once, later, at the position it is actually printed - the
        // only position Canoe genuinely knows. See encodingMustBeDeferred() for why the call stack is
        // the only place that information exists, which consumers of the literal disqualify the
        // deferral, and the asymmetry that decides which way an uncertain answer must fall.
        if (encodingMustBeDeferred()) {
            return arg1;
        }

        // Otherwise encode the text using the correct encoder. The instance form is used rather than
        // the static Canoe.encode(value, context) because a resource-loading URL sink needs this
        // writer's configured trusted-origin allowlist, which is per instance and not a function of
        // the context alone.
        return qlueWriter.encode(arg1.toString());
    }
}
