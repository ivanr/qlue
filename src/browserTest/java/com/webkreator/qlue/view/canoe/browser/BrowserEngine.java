package com.webkreator.qlue.view.canoe.browser;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

/**
 * The three engines the plan asks for.
 *
 * <p>They are named here rather than assumed present, because "which engines actually ran" is part
 * of what a browser-tier result means. A suite that quietly runs one engine and reports "browser
 * tests passed" is claiming cross-engine agreement it never measured — and &sect;5.2 lists
 * {@code xlink:href} and {@code srcdoc} as vectors that behave differently across engines, so the
 * claim is not idle.
 *
 * <p>Only the <em>launch</em> may skip. Once a browser is running a failure is a failure; see
 * {@link BrowserTestBase}.
 */
public enum BrowserEngine {

    CHROMIUM {
        @Override
        BrowserType type(Playwright playwright) {
            return playwright.chromium();
        }
    },

    FIREFOX {
        @Override
        BrowserType type(Playwright playwright) {
            return playwright.firefox();
        }
    },

    WEBKIT {
        @Override
        BrowserType type(Playwright playwright) {
            return playwright.webkit();
        }
    };

    abstract BrowserType type(Playwright playwright);
}
