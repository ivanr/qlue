package com.webkreator.qlue.view.canoe.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Proves the browser tier is wired up: Playwright resolves, a browser launches, and a page loads.
 * Everything else in {@code browser/} builds on this working.
 *
 * <p>Only the launch is allowed to skip. Once a browser is running, a failure is a failure — a smoke
 * test that converts every {@code PlaywrightException} into a skip is one that can never fail, and
 * it would report success for a browser tier that no longer works.
 */
@Tag("browser")
public class BrowserSmokeTest {

    @Test
    public void chromiumLoadsAPage() {
        try (Playwright playwright = createOrSkip()) {
            try (Browser browser = launchOrSkip(playwright)) {
                Page page = browser.newPage();
                page.setContent("<html><body><p id=\"probe\">canoe</p></body></html>");
                assertEquals("canoe", page.textContent("#probe"));
            }
        }
    }

    private static Playwright createOrSkip() {
        try {
            return Playwright.create();
        } catch (Exception e) {
            return abort("Playwright is unavailable in this environment: " + e.getMessage());
        }
    }

    private static Browser launchOrSkip(Playwright playwright) {
        try {
            return playwright.chromium().launch();
        } catch (Exception e) {
            return abort("Chromium is not installed; run ./gradlew playwrightInstall ("
                    + e.getMessage() + ")");
        }
    }
}
