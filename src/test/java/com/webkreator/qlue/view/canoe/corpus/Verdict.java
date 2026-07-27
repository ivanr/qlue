package com.webkreator.qlue.view.canoe.corpus;

/**
 * The recorded outcome of putting a payload through a template.
 *
 * <p>Canoe was broken in at least six exploitable ways when this suite was written. A suite that
 * asserted only <em>desired</em> behaviour would have been red from its first commit and useless as
 * a regression net for the fixes; a suite that asserted only <em>current</em> behaviour would have
 * enshrined the vulnerabilities. So every case carries an explicit, reviewed verdict, and the test
 * asserts that the recorded verdict still holds.
 *
 * <p>The consequence worth understanding before reading a failure: a {@link #KNOWN_VULNERABLE} case
 * <strong>fails when the vulnerability disappears</strong>. That failure is the signal to update the
 * ledger, not a bug. The suite is green today and green after the fixes, and red exactly during the
 * window where behaviour changed and nobody said so.
 *
 * <p>A verdict is set by running the case and then reviewing the result against the sink by hand —
 * never the other way round, or the ledger degrades into a rubber stamp that records bugs as
 * intended behaviour. {@code VerdictEvaluator} derives the observed verdict independently, so a
 * wrong entry fails rather than sitting there as unasserted data. Every {@link #KNOWN_VULNERABLE}
 * entry must additionally cite a finding.
 *
 * <p><strong>After R26 there are six verdicts and {@link #KNOWN_VULNERABLE} is empty.</strong> The
 * sixth, {@link #ACCEPTED_RESIDUAL}, is where the 68 rows that could not be fixed went: attacker
 * data reaches the sink live and the sink is not code execution. It carries the same failure
 * property — the row fails when the data stops arriving — plus a declared {@link ResidualSink}, a
 * cited finding, and a pinned list of the cases allowed to hold it. {@code CanoeCorpusTest} asserts
 * the {@code KNOWN_VULNERABLE} count is zero, which is the number this suite was built to move.
 */
public enum Verdict {

    /**
     * Attacker data reaches the sink inert. The test asserts it stays that way.
     */
    SAFE,

    /**
     * Attacker data reaches the sink live: after the HTML parser decodes character references, the
     * consuming parser — JavaScript, CSS, URL, or HTML — sees the attacker's original characters.
     *
     * <p>Must cite a finding from the Canoe security reviews, which are held outside this
     * repository, or open a new one. The citation is required by {@code XssCase.validate()} and
     * recorded in {@code matrix.md}; nothing in the build resolves it against the reviews.
     */
    KNOWN_VULNERABLE,

    /**
     * Attacker data reaches the sink live, exactly as {@link #KNOWN_VULNERABLE} says — and the sink
     * it reaches is <strong>not code execution</strong>. The residue is accepted, on the record,
     * with a reason.
     *
     * <p>R26 added this verdict because the last 68 {@code KNOWN_VULNERABLE} invocations could not
     * be driven to zero by fixing anything. Every one of them is F6 — {@code url()} is a scheme
     * filter and not an origin filter — on a surface R9 scoped out <em>by design</em>: an
     * {@code <a href>} that leaves the origin, an {@code <img src>} that fetches from somewhere
     * else, a {@code <form action>} that posts elsewhere. R9 rejects an off-origin authority on the
     * six resource-loading combinations, where the response becomes script, a document or a
     * stylesheet with the page's privileges. It deliberately does not reject one on
     * {@code <a href>}, because an off-origin link is an ordinary thing for a page to contain and a
     * component that refused to emit one would be turned off. The remaining exposure is an open
     * redirect, a form retarget or a referrer leak, and this verdict is what says so out loud
     * instead of leaving 68 rows on a list headed "drive this to zero".
     *
     * <p><strong>What stops it becoming a rubber stamp.</strong> The same property that makes
     * {@link #KNOWN_VULNERABLE} mean something: a case with this verdict <strong>fails when the
     * data stops reaching the sink</strong>. {@code VerdictEvaluator} still observes
     * {@code KNOWN_VULNERABLE} for these rows — it reads rendered output and cannot tell a redirect
     * from an execution — and {@code Observation.matches()} accepts that one observation against
     * this verdict and nothing else. So a row that starts suppressing, or starts arriving inert,
     * fails here and has to be re-verdicted deliberately. "Accepted" is a claim about the sink, not
     * a licence for the row to mean whatever the code does next.
     *
     * <p>Three further guards, all in {@code CanoeCorpusTest} and {@code MatrixReportTest}:
     *
     * <ul>
     *   <li>the row must cite a finding, exactly as {@link #KNOWN_VULNERABLE} must;
     *   <li>the case must declare a {@link ResidualSink} — <em>which</em> non-executing sink the
     *       data reaches — and no case with any other verdict may declare one;
     *   <li>the set of cases carrying it is pinned to an explicit list, so a new residual fails the
     *       build rather than joining the set silently.
     * </ul>
     *
     * <p>This is not {@link #SAFE} and must never be collapsed into it. The data is live at the
     * sink; what is recorded is a judgement about what that sink does, and the judgement is
     * appealable. An application that cannot afford an open redirect has a real reason to ask for
     * an origin filter on {@code <a href>} as well, and {@link ResidualSink#FORM_RETARGET} says
     * where such a conversation should start.
     */
    ACCEPTED_RESIDUAL,

    /**
     * Canoe emits the empty string, and that is the designed behaviour. Refusing to output into
     * JavaScript and CSS contexts is the centrepiece of Canoe's design, so these entries record the
     * component working, not failing.
     */
    SUPPRESSED_BY_DESIGN,

    /**
     * Canoe emits the empty string where it should have emitted an encoded value. Fail-safe, so not
     * a vulnerability, but a defect: the value vanishes with no error and no diagnostic. Tracked
     * separately from {@link #SUPPRESSED_BY_DESIGN} because these are what push developers towards
     * {@code $_x.asis()}, which disables Canoe for that value entirely. F7 and F11 lived here; R7
     * closed the first and R19 closed the attribute-value half of the second, so what is left is
     * F11's other half — the {@code COMMENT_*} and {@code DOCTYPE*} states.
     *
     * <p><strong>R26 re-read the twelve that remain and left them here deliberately.</strong> They
     * are four cases — {@code comment.body}, {@code comment.conditional},
     * {@code shape.unclosed-comment} and {@code doctype.internal-subset} — times the three
     * {@code TAG_BREAKOUT} payloads. A comment body and a DOCTYPE internal subset are the two
     * positions in the tokenizer with no encoder that is correct for them: {@code html()} would
     * escape characters a comment does not decode, and the one character that actually matters
     * inside a comment ({@code -->}, and inside a DOCTYPE {@code >}) has no character reference the
     * parser would honour there. Suppression is fail-safe and cheap — nobody interpolates into a
     * comment on purpose — so the answer is not "route it" but "keep dropping it and say why". They
     * stay {@code SUPPRESSED_UNINTENDED} rather than moving to {@link #SUPPRESSED_BY_DESIGN}
     * because the drop is still silent and still undiagnosed: the debug diagnostic R5 added names
     * the attribute an unknown-name suppression dropped, and there is no equivalent for a value
     * that vanishes inside a comment. That is the availability half of the ledger, recorded as a
     * decision rather than as a hole.
     */
    SUPPRESSED_UNINTENDED,

    /**
     * Canoe raises an encoding error. An availability failure rather than a security one, but it
     * takes the page down.
     *
     * <p><strong>What that means to a caller, after R21.</strong> The rejection reaches
     * {@code VelocityViewFactory.render()}'s caller as a {@code CanoeEncodingException} — Canoe's own
     * exception, unwrapped from Velocity's, carrying the reason and the coordinates as fields — and
     * the partial page is left unflushed so the response can still be replaced by an error page. The
     * request fails, deliberately: a rejection is a template-authoring error, and the recoveries the
     * alternative would have needed (a marker appended inside an attribute list, or a truncation a
     * browser renders as if it were the whole page) are less honest than failing.
     *
     * <p>It used to be worse than the review assumed, and this verdict is what recorded it (F13).
     * {@code render()} tested {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on the
     * <em>top-level</em> exception, and Velocity always wraps — the production
     * {@code Template.merge()} path yields {@code "IO Error rendering template '...'"} — so the
     * {@code [Encoding Error]} branch was unreachable, the exception propagated as an unhandled 500,
     * and the {@code finally} block had already flushed the half-written page, which committed the
     * response and left the container unable to send the 500 at all.
     *
     * <p>None of that changes <em>which</em> inputs are rejected, and R21 changed none of them:
     * {@code VerdictEvaluator} derives this verdict from the harness finding a
     * {@code CanoeEncodingException} in the cause chain, which is the same set of renders it found
     * an {@code IOException} carrying the prefix in before.
     *
     * <p><strong>R20 then decided which of them should stop being rejections</strong>, and eight
     * invocations left this verdict for {@link #SAFE}: the three XHTML-style void elements
     * ({@code <br/>}, {@code <hr/>}, {@code <img/>}) and the second DOCTYPE. The name-length rows
     * stayed, at 127/128 rather than 35/36. What survives is a rejection because it is a
     * template-authoring <em>error</em> — a literal {@code <} in prose, {@code </ p>}, {@code </>}, a
     * control character in the template's own text — and the reasoning for each is on
     * {@code CanoeRobustnessTest.rejections()}. A row that carries this verdict from here on should be
     * a shape someone can defend rejecting, not one nobody has got round to.
     */
    REJECTED;

    /** True for the two verdicts that mean Canoe emitted nothing. */
    public boolean isSuppression() {
        return this == SUPPRESSED_BY_DESIGN || this == SUPPRESSED_UNINTENDED;
    }

    /**
     * True for the two verdicts that mean the attacker's characters arrived at the sink intact.
     *
     * <p>The predicate every test that used to ask {@code == KNOWN_VULNERABLE} about <em>reach</em>
     * should ask instead. {@link #ACCEPTED_RESIDUAL} differs from {@link #KNOWN_VULNERABLE} only in
     * what the sink does with the data, so a check about whether data got there — the browser tier's
     * "must a detector fire", the body-context bound, the URL group's authority-position rule — is
     * false for one and true for the other only by accident of R26's paperwork. Splitting the
     * verdict without this method would have silently emptied five such tests, which is a worse
     * outcome than the one R26 set out to fix.
     */
    public boolean reachesSinkLive() {
        return this == KNOWN_VULNERABLE || this == ACCEPTED_RESIDUAL;
    }

    /**
     * True for verdicts that represent something needing fixing. {@link #SUPPRESSED_BY_DESIGN} is
     * excluded: it records the component working as designed, and counting it would leave the defect
     * total permanently above zero with no way to tell which entries are legitimately stuck.
     *
     * <p>{@link #ACCEPTED_RESIDUAL} is excluded for the same reason and not for a weaker one: it is
     * a reviewed decision about a sink, pinned to an explicit list of cases that may only shrink, so
     * counting it would put the total permanently above zero again — which is the exact failure mode
     * R26 exists to end. Use {@link #reachesSinkLive()} when the question is "did the data get
     * there", which is the question the residue answers yes to.
     */
    public boolean isDefect() {
        return this == KNOWN_VULNERABLE || this == SUPPRESSED_UNINTENDED || this == REJECTED;
    }
}
