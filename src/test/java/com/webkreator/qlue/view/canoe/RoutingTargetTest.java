package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.Canoe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 — the regression harness for Phase A of the remediation plan.
 *
 * <p>Everything else in this suite asserts what Canoe <em>does</em>. This class is the one place
 * that says what Phase A is aiming <em>at</em>: for one representative attribute name from each
 * category the plan touches, a row records the context Canoe reaches today, the context it should
 * reach when Phase A is complete, and the task that moves it. The current column is asserted, so
 * the tree stays green and each landing task announces itself by failing exactly the rows it owns.
 *
 * <p><strong>How to work this file.</strong> When a row's task lands, this test fails on that row.
 * Do not adjust the target — the target column is the specification, settled here in advance. Move
 * the row's {@code current} to equal its {@code target}, clear its {@code flippedBy}, and note the
 * task in the row comment. When Phase A is complete the two columns are identical everywhere and
 * {@link #theTableRecordsWhichRowsPhaseAChanges} degenerates to "nothing left to flip", at which
 * point this class has done its job and becomes a plain pin of the routing table.
 *
 * <p>The categories, per R1: a recognised event handler, an unrecognised event handler, a URL
 * attribute (one name Canoe knows and one it does not), {@code style}, a policy-bearing attribute,
 * and a plain-text attribute. The two colon rows are here as well because R2 — the first and
 * highest-impact task of the phase — is precisely the claim that a colon in the value must stop
 * changing the answers this table records.
 *
 * <p><strong>Phase A is complete.</strong> R2's two rows — the recognised handler with a colon in
 * its body, and {@code style} after a property name — R4's row for a handler the old {@code on*}
 * table did not list, R5's policy attribute and R5+R6's unrecognised URL attribute have all had
 * their current column moved onto their target and their {@code flippedBy} cleared. No row names a
 * task any more, so {@link #theTableRecordsWhichRowsPhaseAChanges} has degenerated to "nothing left
 * to flip" exactly as this file's procedure said it would, and the class is a plain pin of the
 * routing table from here on.
 *
 * <p>One row was <em>added</em> rather than flipped, which the procedure did not anticipate and
 * which is worth stating: R5 created a category the table had no cell for. Before it, an attribute
 * name nobody had classified was indistinguishable from a plain-text one — that is the whole of F3's
 * markup and policy half — so "unlisted attribute" could not have been a row. It is one now, and it
 * sits next to the plain-text row it used to be identical to.
 *
 * <p>Rows are asserted at the {@code CTX_*} level rather than the {@code ATTR_*} level, because the
 * context is what picks the encoder and the encoder is what Phase A is really about;
 * {@link #theEncoderEachContextImplies} pins that last step so a row's target reads as an outcome
 * rather than as a number. {@code AttributePrefixTest} owns the {@code ATTR_*}-level mechanism.
 */
public class RoutingTargetTest {

    /**
     * One row of the Phase A routing table: where a reference inside the given template prefix is
     * encoded today, where it must be encoded when Phase A is done, and which task flips it.
     * {@code flippedBy} is null when the two columns already agree — those rows are the ones the
     * named tasks must <em>preserve</em>, and they regress just as loudly as a flip that never came.
     */
    static final class Row {

        final String category;
        final String templatePrefix;
        final int current;
        final int target;
        final String flippedBy;

        Row(String category, String templatePrefix, int current, int target, String flippedBy) {
            this.category = category;
            this.templatePrefix = templatePrefix;
            this.current = current;
            this.target = target;
            this.flippedBy = flippedBy;
        }

        @Override
        public String toString() {
            return category + ": " + CanoeTestSupport.quote(templatePrefix)
                    + " currently " + CanoeTestSupport.contextName(current)
                    + ", target " + CanoeTestSupport.contextName(target)
                    + (flippedBy == null ? " (already at target)" : ", flipped by " + flippedBy);
        }
    }

    static Stream<Row> rows() {
        return Stream.of(
                // An event handler the old on* table listed. onclick resolved to ATTR_JS then and
                // resolves to ATTR_JS now, so CTX_JS - suppression - is both current and target.
                // R2 and R4 both had to preserve it, and R4 is why it is no longer a special case:
                // the prefix rule that replaced the table classifies this name like every other.
                new Row("recognised handler",
                        "<a onclick=\"", Canoe.CTX_JS, Canoe.CTX_JS, null),

                // The same recognised handler after a colon in its body (F17). The value scan's
                // unconditional reset used to discard ATTR_JS and hand the value to html(), which
                // the HTML parser undoes. R2 deleted the reset and this row reached its target: the
                // name-derived CTX_JS now survives any value content, and no later Phase A task may
                // move it.
                new Row("recognised handler, colon in the body (F17)",
                        "<a onclick=\"f({a:1,b:'", Canoe.CTX_JS, Canoe.CTX_JS, null),

                // An event handler the old on* table did not list (F2). onpointerdown was one of
                // the 76 of 94 spec handlers that table missed, so it fell to ATTR_HTML and html().
                // R4 replaced the table with a prefix rule - any name beginning "on" is ATTR_JS -
                // and this row reached its target: it suppresses exactly as onclick does, and no
                // later Phase A task may move it.
                new Row("handler outside the old table (F2)",
                        "<button onpointerdown=\"", Canoe.CTX_JS, Canoe.CTX_JS, null),

                // A URL attribute Canoe knows. href is one of the five ATTR_URI names and gets
                // url(); that is correct routing and every Phase A task must leave it alone.
                // (What url() itself does to the value is Phase C's problem - R11/R12.)
                new Row("recognised URL attribute",
                        "<a href=\"", Canoe.CTX_URI, Canoe.CTX_URI, null),

                // A URL attribute Canoe did not know (the URL half of F3). formaction submits the
                // form wherever its value points, and it was html()-encoded like a title. R5
                // stopped unknown names reaching ATTR_HTML and R6 put formaction on the URL-bearing
                // name list; they landed together, so this row reached CTX_URI rather than the
                // suppression R5 alone would have given it, and no later Phase A task may move it.
                // What url() then does to the value is Phase C's problem and F6's: the name is
                // routed correctly and off-origin URLs still pass, which the ledger records on this
                // sink as F6 rather than F3.
                new Row("unrecognised URL attribute (F3)",
                        "<button formaction=\"", Canoe.CTX_URI, Canoe.CTX_URI, null),

                // style, before any colon. The name resolves to ATTR_CSS, which suppresses - Canoe
                // refuses to interpolate into CSS, and R14 records that as the settled decision
                // rather than routing to a CSS encoder. Current and target agree.
                new Row("style",
                        "<div style=\"", Canoe.CTX_SUPPRESS, Canoe.CTX_SUPPRESS, null),

                // style after the first declaration's colon (F4). The reset turned suppression into
                // html() encoding on the basic syntax of CSS. R2 deleted the reset and this row
                // reached its target: the name-derived suppression survives the colon.
                new Row("style, colon in the value (F4)",
                        "<div style=\"color:", Canoe.CTX_SUPPRESS, Canoe.CTX_SUPPRESS, null),

                // A policy-bearing attribute (F20). The HTML parser consumes sandbox's decoded
                // value as a directive, so no encoding helps and html() was meaningless here; R5's
                // fail-closed default suppresses it, and its name is off the plain-text allowlist
                // along with rel, integrity and nonce - both in the list itself and in
                // Canoe.NAMES_THAT_MAY_NOT_BE_ADDED, which refuses to let an application put it
                // back through configuration.
                new Row("policy attribute (F20)",
                        "<iframe sandbox=\"", Canoe.CTX_SUPPRESS, Canoe.CTX_SUPPRESS, null),

                // R5's own row, which the table had no cell for while ATTR_HTML was the default:
                // an attribute name nobody has classified. It was indistinguishable from the
                // plain-text row below - that was the whole of the finding - and it is the one place
                // in this table where the current column moved without any name being added to a
                // list.
                new Row("unlisted attribute (R5)",
                        "<div my-widget-config=\"", Canoe.CTX_SUPPRESS, Canoe.CTX_SUPPRESS, null),

                // A plain-text attribute. title is a genuine text sink and html() is the right
                // encoder; it is on R5's allowlist, so the routing did not change when the
                // ATTR_HTML default inverted underneath it. This is the row that keeps R5 honest
                // about being an allowlist rather than a blanket suppression, and it is the one
                // that would have failed if the fail-closed default had been shipped without one.
                new Row("plain-text attribute",
                        "<p title=\"", Canoe.CTX_HTML_ATTR, Canoe.CTX_HTML_ATTR, null));
    }

    /**
     * The current column, asserted. Green today by construction; when a Phase A task lands, the
     * rows naming that task fail here, which is the signal to move their current column onto the
     * target and update the ledger — see the class javadoc for the procedure.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    public void theCurrentRoutingHoldsUntilTheNamedTaskLands(Row row) {
        assertEquals(row.current, CanoeTestSupport.contextAfter(row.templatePrefix),
                () -> row.flippedBy == null
                        ? row + " — no Phase A task may change this row; if it moved, a task"
                                + " overreached"
                        : row + " — if this failed because " + row.flippedBy + " landed, set the"
                                + " row's current column to its target and clear flippedBy");
    }

    /**
     * The table's own bookkeeping: a row changes if and only if it names the task that changes it,
     * and the target column is the plan's end state rather than a drifting copy of the current one.
     *
     * <p>The last assertion is the substance of Phase A in one line: once the phase is complete,
     * {@code CTX_HTML_ATTR} — the context whose encoder the HTML parser undoes — is reached only
     * by the plain-text allowlist, never by a handler, a URL name, a style value or a policy name.
     */
    @Test
    public void theTableRecordsWhichRowsPhaseAChanges() {
        List<Row> table = rows().toList();

        for (Row row : table) {
            if (row.current == row.target) {
                assertNull(row.flippedBy,
                        row + " is already at its target, so it must not name a flipping task");
            } else {
                assertNotNull(row.flippedBy,
                        row + " changes in Phase A, so it must name the task that flips it");
            }
        }

        for (Row row : table) {
            assertTrue(row.target != Canoe.CTX_HTML_ATTR
                            || row.category.equals("plain-text attribute"),
                    row + " — after Phase A only the plain-text allowlist may reach"
                            + " CTX_HTML_ATTR");
        }
    }

    /**
     * The encoder each context in the table implies, pinned through the same static dispatcher the
     * writer uses. This is what turns a target column entry into an outcome: CTX_JS and
     * CTX_SUPPRESS mean the value is dropped, CTX_URI means {@code url()}, CTX_HTML_ATTR means
     * {@code htmlAttr()} — whose output the HTML parser decodes, which is why Phase A's whole job
     * is confining it to plain-text sinks.
     */
    @Test
    public void theEncoderEachContextImplies() {
        String payload = "');alert(1)//";

        assertEquals("", CanoeTestSupport.encodeFor(payload, Canoe.CTX_JS),
                "CTX_JS suppresses: Canoe refuses to interpolate into JavaScript");
        assertEquals("", CanoeTestSupport.encodeFor(payload, Canoe.CTX_SUPPRESS),
                "CTX_SUPPRESS drops the value");
        assertEquals(HtmlEncoder.url(payload), CanoeTestSupport.encodeFor(payload, Canoe.CTX_URI),
                "CTX_URI dispatches to url()");
        assertEquals(HtmlEncoder.htmlAttr(payload),
                CanoeTestSupport.encodeFor(payload, Canoe.CTX_HTML_ATTR),
                "CTX_HTML_ATTR dispatches to htmlAttr()");

        // The one property a target context must have regardless of which encoder it names: no
        // encoder output can open a tag. Body-context safety rests on this everywhere else too.
        for (int context : List.of(Canoe.CTX_JS, Canoe.CTX_SUPPRESS, Canoe.CTX_URI,
                Canoe.CTX_HTML_ATTR)) {
            CanoeTestSupport.assertCannotOpenTag(CanoeTestSupport.encodeFor("<script>", context));
        }
    }
}
