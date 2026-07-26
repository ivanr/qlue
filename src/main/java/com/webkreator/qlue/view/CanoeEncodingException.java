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
package com.webkreator.qlue.view;

import java.io.IOException;

/**
 * The exception {@link Canoe} raises when it refuses to render a template.
 *
 * <p><strong>Why it exists (F13, R21).</strong> Canoe used to throw a bare {@link IOException} whose
 * message began {@link Canoe#ERROR_PREFIX}, and {@code VelocityViewFactory.render()} recognised an
 * encoding error by testing {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on the exception it
 * caught. It never caught Canoe's: Velocity wraps an {@code IOException} from the writer in a
 * {@code VelocityException} of its own — {@code "IO Error rendering template '...'"} on the
 * production {@code Template.merge()} path, {@code "IO Error in writer: ..."} on {@code evaluate()} —
 * so the test was applied to the wrapper's message and could never be true. A type in the cause chain
 * is what a wrapper cannot hide: {@link #findIn(Throwable)} finds it wherever Velocity, or a future
 * template engine, or an application's own decorator chose to put it.
 *
 * <p><strong>The coordinates are fields, not only prose.</strong> {@code raiseError()} has always
 * computed the line and position of the offending character and has always spent them on a string.
 * They are carried here as {@link #getLine()} and {@link #getPosition()}, with the bare
 * {@link #getReason()} beside them, so that a caller can report or triage a rejection without parsing
 * the message back apart. R20 (the rejection-table triage) was the first consumer, and
 * {@code CanoeRobustnessTest} reads them for every row of the table.
 *
 * <p><strong>The message is unchanged.</strong> {@code getMessage()} is still
 * {@code "Encoding Error: <reason> (line: L, pos: P)"}, byte for byte what the bare
 * {@code IOException} carried, and this class is the one place that builds it. {@link
 * Canoe#ERROR_PREFIX} is kept for that reason — see its javadoc — but nothing in {@code src/main}
 * matches on it any more.
 *
 * <p>An encoding error is <em>not</em> attacker-controlled. Every shape that reaches
 * {@code raiseError()} is a template-authoring error — a literal {@code <} in prose, {@code </ p>},
 * a name longer than {@link Canoe#MAX_TAGNAME_LEN} — so catching this exception means "this page's
 * template is wrong", not "somebody is attacking us". R20 triaged that set and moved the shapes that
 * were merely unusual rather than wrong ({@code <br/>}, a long {@code data-*} name, a second DOCTYPE)
 * out of it; {@code CanoeRobustnessTest.rejections()} is the surviving table, with the reasoning for
 * each row on it.
 */
public class CanoeEncodingException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * How far {@link #findIn(Throwable)} walks. {@code Throwable.getCause()} returns null rather than
     * {@code this} for a self-cycle, but an overridden {@code getCause()} can build a two-cycle that
     * would spin forever, and a cause chain deeper than this is a wrapper bug rather than a nesting
     * anyone meant. The same bound, for the same reason, as {@code CanoeTestSupport}'s walk.
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final String reason;

    private final int line;

    private final int position;

    /**
     * @param reason   the error, without the prefix and without the coordinates
     * @param line     the one-based line the offending character is on
     * @param position the one-based position of the offending character within that line
     */
    public CanoeEncodingException(String reason, int line, int position) {
        super(Canoe.ERROR_PREFIX + reason + " (line: " + line + ", pos: " + position + ")");
        this.reason = reason;
        this.line = line;
        this.position = position;
    }

    /**
     * The error on its own: {@code "Invalid character after tag name"}, with no prefix and no
     * coordinates. This is the string that identifies <em>which</em> rejection fired, and the one a
     * caller should group or count by; {@link #getMessage()} varies with the position and so cannot
     * be used for that.
     */
    public String getReason() {
        return reason;
    }

    /** The one-based line the offending character is on. Only LF advances the counter. */
    public int getLine() {
        return line;
    }

    /** The one-based position of the offending character within its line. */
    public int getPosition() {
        return position;
    }

    /**
     * The {@link CanoeEncodingException} in a throwable's cause chain, or null if there is none.
     *
     * <p>This is the replacement for {@code startsWith(Canoe.ERROR_PREFIX)} on a top-level message,
     * and it is the method callers outside the framework should use too: a template engine is
     * entitled to wrap an {@code IOException} from its writer, and Velocity always does. The match is
     * on type rather than on message, so no exception that merely quotes Canoe's message — a parse
     * error echoing a hostile string, say — can be mistaken for a rejection.
     *
     * @param t the exception a caller caught; null is answered with null
     */
    public static CanoeEncodingException findIn(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof CanoeEncodingException) {
                return (CanoeEncodingException) current;
            }
            current = current.getCause();
        }
        return null;
    }
}
