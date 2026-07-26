package com.webkreator.qlue.view.canoe.corpus;

/**
 * The recorded outcome of putting a payload through a template.
 *
 * <p>Canoe is currently broken in at least six exploitable ways. A suite that asserted only
 * <em>desired</em> behaviour would be red from its first commit and useless as a regression net for
 * the fixes; a suite that asserted only <em>current</em> behaviour would enshrine the
 * vulnerabilities. So every case carries an explicit, reviewed verdict, and the test asserts that
 * the recorded verdict still holds.
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
     * <p>Must cite a finding from {@code CANOE-SECURITY-REVIEW-2026-07-25.md}, or open a new one.
     */
    KNOWN_VULNERABLE,

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
     * {@code $_x.asis()}, which disables Canoe for that value entirely. F7 and F11 live here.
     */
    SUPPRESSED_UNINTENDED,

    /**
     * Canoe raises an encoding error. An availability failure rather than a security one, but it
     * takes the page down.
     *
     * <p>Note what actually happens, which is worse than the review assumed: {@code
     * VelocityViewFactory.render()} tests {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on
     * the <em>top-level</em> exception, but Velocity always wraps the {@code IOException} — the
     * production {@code Template.merge()} path yields {@code "IO Error rendering template '...'"}.
     * The {@code [Encoding Error]} branch is therefore unreachable and the exception propagates as
     * an unhandled 500. Recorded as F13.
     */
    REJECTED;

    /** True for the two verdicts that mean Canoe emitted nothing. */
    public boolean isSuppression() {
        return this == SUPPRESSED_BY_DESIGN || this == SUPPRESSED_UNINTENDED;
    }

    /**
     * True for verdicts that represent something needing fixing. {@link #SUPPRESSED_BY_DESIGN} is
     * excluded: it records the component working as designed, and counting it would leave the defect
     * total permanently above zero with no way to tell which entries are legitimately stuck.
     */
    public boolean isDefect() {
        return this == KNOWN_VULNERABLE || this == SUPPRESSED_UNINTENDED || this == REJECTED;
    }
}
