package com.webkreator.qlue.view.canoe.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the five detectors of &sect;5.2 observed while one page was loaded.
 *
 * <p>The interesting decision here is which detectors count as <em>exploitation</em>.
 * {@link #exploited()} is the union of four of the five: script execution, a dialog, a request to
 * the attacker origin, and an off-origin navigation. Console errors are recorded and deliberately
 * excluded, and that is not a detail — several corpus payloads land in a syntactic position where
 * the attacker's characters arrive live and produce a <em>syntax error</em> rather than a call. If
 * a console error counted as a hit, those rows would pass while proving the opposite of what they
 * claim: that an injection ran. &sect;5.2 puts the console detector against the
 * {@code SUPPRESSED}/{@code REJECTED} ledger, not against {@code KNOWN_VULNERABLE}, and this class
 * follows that.
 *
 * <p>The script-execution detector is recorded twice over, from the Java side (the exposed binding
 * fired) and from the page side (a {@code window} array an init script installed). They should
 * always agree; {@code DetectorSelfTest} asserts they do, which is what proves the init script runs
 * <em>after</em> the binding is installed rather than clobbering it.
 */
public final class BrowserVerdict {

    private final String label;
    private final BrowserEngine engine;
    private final String url;

    private final List<String> scriptExecutions = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scriptExecutionFrames = Collections.synchronizedList(new ArrayList<>());
    private final List<String> windowSentinelCalls = new ArrayList<>();
    private final List<String> dialogs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sentinelRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> abortedSentinelRequests =
            Collections.synchronizedList(new ArrayList<>());
    private final List<String> navigations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> offOriginNavigations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> popups = Collections.synchronizedList(new ArrayList<>());
    private final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> serverRequests = new ArrayList<>();

    BrowserVerdict(String label, BrowserEngine engine, String url) {
        this.label = label;
        this.engine = engine;
        this.url = url;
    }

    public String label() {
        return label;
    }

    public BrowserEngine engine() {
        return engine;
    }

    public String url() {
        return url;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    void recordScriptExecution(String argument, String frameUrl) {
        scriptExecutions.add(argument);
        scriptExecutionFrames.add(frameUrl);
    }

    void recordWindowSentinel(List<String> calls) {
        windowSentinelCalls.addAll(calls);
    }

    void recordDialog(String type, String message) {
        dialogs.add(type + ": " + message);
    }

    void recordSentinelRequest(String requestUrl) {
        sentinelRequests.add(requestUrl);
    }

    void recordAbortedSentinelRequest(String requestUrl) {
        abortedSentinelRequests.add(requestUrl);
    }

    void recordNavigation(String frameUrl) {
        navigations.add(frameUrl);
    }

    void recordOffOriginNavigation(String frameUrl) {
        offOriginNavigations.add(frameUrl);
    }

    void recordPopup(String popupUrl) {
        popups.add(popupUrl);
    }

    void recordConsoleError(String message) {
        consoleErrors.add(message);
    }

    void recordPageError(String message) {
        pageErrors.add(message);
    }

    void recordServerRequests(List<String> requests) {
        serverRequests.addAll(requests);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Arguments the page passed to the exposed script-execution sentinel. */
    public List<String> scriptExecutions() {
        return List.copyOf(scriptExecutions);
    }

    /** The URL of the frame each script execution came from, index-aligned with the above. */
    public List<String> scriptExecutionFrames() {
        return List.copyOf(scriptExecutionFrames);
    }

    /** The same calls as seen from inside the page, via the init script's {@code window} array. */
    public List<String> windowSentinelCalls() {
        return List.copyOf(windowSentinelCalls);
    }

    public List<String> dialogs() {
        return List.copyOf(dialogs);
    }

    /** Every request the page made to {@code attacker.invalid}, whatever its scheme. */
    public List<String> sentinelRequests() {
        return List.copyOf(sentinelRequests);
    }

    /** The subset of the above that the {@code page.route} interceptor aborted. */
    public List<String> abortedSentinelRequests() {
        return List.copyOf(abortedSentinelRequests);
    }

    public List<String> navigations() {
        return List.copyOf(navigations);
    }

    public List<String> offOriginNavigations() {
        return List.copyOf(offOriginNavigations);
    }

    public List<String> popups() {
        return List.copyOf(popups);
    }

    public List<String> consoleErrors() {
        return List.copyOf(consoleErrors);
    }

    public List<String> pageErrors() {
        return List.copyOf(pageErrors);
    }

    /** What the sentinel origin actually served while this case was loaded. */
    public List<String> serverRequests() {
        return List.copyOf(serverRequests);
    }

    public boolean scriptExecuted() {
        return !scriptExecutions.isEmpty();
    }

    public boolean sentinelOriginContacted() {
        return !sentinelRequests.isEmpty();
    }

    /**
     * Whether any detector that means "the attacker got something" fired. Console output is not one
     * of them; see the class javadoc.
     */
    public boolean exploited() {
        return scriptExecuted()
                || !dialogs.isEmpty()
                || sentinelOriginContacted()
                || !offOriginNavigations.isEmpty();
    }

    /** The names of the detectors that fired, for a failure message that says which. */
    public List<String> firedDetectors() {
        List<String> fired = new ArrayList<>();
        if (scriptExecuted()) {
            fired.add("script-execution" + scriptExecutions);
        }
        if (!dialogs.isEmpty()) {
            fired.add("dialog" + dialogs);
        }
        if (!sentinelRequests.isEmpty()) {
            fired.add("sentinel-origin" + sentinelRequests);
        }
        if (!offOriginNavigations.isEmpty()) {
            fired.add("off-origin-navigation" + offOriginNavigations);
        }
        return fired;
    }

    @Override
    public String toString() {
        return describe();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(engine).append(' ').append(label).append(" -> ").append(url).append('\n');
        sb.append("  exploited            : ").append(exploited()).append('\n');
        sb.append("  script executions    : ").append(scriptExecutions).append('\n');
        sb.append("  window sentinel      : ").append(windowSentinelCalls).append('\n');
        sb.append("  dialogs              : ").append(dialogs).append('\n');
        sb.append("  sentinel requests    : ").append(sentinelRequests).append('\n');
        sb.append("  aborted at the route : ").append(abortedSentinelRequests).append('\n');
        sb.append("  navigations          : ").append(navigations).append('\n');
        sb.append("  off-origin navs      : ").append(offOriginNavigations).append('\n');
        sb.append("  popups               : ").append(popups).append('\n');
        sb.append("  console errors       : ").append(consoleErrors).append('\n');
        sb.append("  page errors          : ").append(pageErrors).append('\n');
        sb.append("  server saw           : ").append(serverRequests);
        return sb.toString();
    }
}
