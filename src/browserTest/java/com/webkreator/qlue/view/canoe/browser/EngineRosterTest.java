package com.webkreator.qlue.view.canoe.browser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Which engines ran, stated in the test report rather than left to be inferred from its absence.
 *
 * <p>Everything else in this package parameterises over the engines that <em>launched</em>, so an
 * engine that is not installed contributes no tests at all — and a report with no Firefox rows is
 * indistinguishable from a report where Firefox was never asked for. That is the same class of
 * mistake as a skip that swallows a real failure: it reads green and means less than it looks.
 *
 * <p>One row per engine, always. A missing engine is a skip carrying the reason the launch gave, so
 * "we ran Chromium only, because this container has a read-only browser cache with nothing else in
 * it" is a sentence the report makes rather than a sentence someone has to write.
 */
@Tag("browser")
public class EngineRosterTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserEngine.class)
    public void theEngineIsAvailable(BrowserEngine engine) {
        if (!BrowserTestBase.enginesThatRan().contains(engine)) {
            abort(engine + " did not launch, so nothing in the browser tier ran against it: "
                    + BrowserTestBase.unavailabilityOf(engine));
        }
        assertTrue(BrowserTestBase.enginesThatRan().contains(engine));

        // Which build, not just which engine (R28). A cross-engine result is only reproducible
        // against a named version, and R28's one engine-specific limitation is a defect in a
        // particular Playwright Firefox build rather than in Firefox as such.
        String version = BrowserTestBase.versionOf(engine);
        System.out.println("[canoe] " + engine + " " + version);
        assertNotEquals("unknown", version,
                engine + " launched but would not name its version, so the run cannot say which"
                        + " build it is a result about");
    }
}
