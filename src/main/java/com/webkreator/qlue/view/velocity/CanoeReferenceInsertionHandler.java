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

import java.util.List;

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

    protected Canoe qlueWriter;

    public CanoeReferenceInsertionHandler(Canoe qlueWriter) {
        this.qlueWriter = qlueWriter;
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

        // Otherwise encode the text using the correct encoder. The instance form is used rather than
        // the static Canoe.encode(value, context) because a resource-loading URL sink (R9) needs this
        // writer's configured trusted-origin allowlist, which is per instance and not a function of
        // the context alone.
        return qlueWriter.encode(arg1.toString());
    }
}
