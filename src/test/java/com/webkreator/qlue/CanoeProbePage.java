package com.webkreator.qlue;

/**
 * A real {@link Page}, with a real {@link QlueApplication} behind it, for tests that need to drive
 * {@code VelocityViewFactory.render(page, view, writer)} — the production render path — rather than
 * the {@code VelocityEngine.evaluate()} shortcut the rest of the Canoe suite uses. See
 * {@code com.webkreator.qlue.view.velocity.ProductionRenderProbe}, which is its only caller.
 *
 * <p>Declared in {@code com.webkreator.qlue} because {@code Page.setApp()} is package-private and
 * {@code QlueApplication}'s no-argument constructor is protected, so neither can be reached from the
 * test suite's own packages. The same reason {@code CanoeStateProbe} lives in
 * {@code com.webkreator.qlue.view}.
 *
 * <p>Nothing is overridden. {@code render()} reads the model, the velocity tools, the direct-output
 * flag, the shadow input, the command object and the errors off the page, and every one of those
 * comes from the real implementation here — including {@code getCommandObject()}, which returns the
 * page itself and whose public fields {@code render()} then reflects into the model. The only
 * production input that is absent is the {@code TransactionContext}: {@code getContext()} returns
 * null on a page that was never routed, which makes {@code render()} skip the block publishing
 * {@code _ctx}, {@code _req}, {@code _res} and the session. No template under test refers to any of
 * those, and supplying them would mean mocking the servlet stack to no purpose.
 */
public class CanoeProbePage extends Page {

    public CanoeProbePage() {
        this(false);
    }

    /**
     * @param allowDirectOutput what {@code Page.allowDirectOutput()} should report, which is the
     *                          switch that decides whether {@code render()} publishes the encoding
     *                          tool {@code $_x} into the model at all. A page that has not called it
     *                          leaves {@code $_x} unbound, and under Qlue's
     *                          {@code runtime.strict_mode.enable} that makes {@code $_x.asis($data)}
     *                          a rendering failure rather than a silent encode —
     *                          {@code ViewFactoryRenderTest} (T20) asserts both halves.
     */
    public CanoeProbePage(boolean allowDirectOutput) {
        setApp(new ProbeApplication(allowDirectOutput));
    }

    /**
     * Exists only because {@code QlueApplication()} is protected. It supplies
     * {@code getVelocityTools()} (one {@code DefaultVelocityTool} named {@code _f}) and
     * {@code allowDirectOutput()}, which are the two things {@code render()} asks the application
     * for. {@code getVelocityTools()} is the framework default; {@code allowDirectOutput()} defaults
     * to the framework's {@code false} and is overridden only when a test asks for it, because it is
     * one of the two production switches T20 has to cover.
     */
    private static final class ProbeApplication extends QlueApplication {

        private final boolean allowDirectOutput;

        ProbeApplication(boolean allowDirectOutput) {
            this.allowDirectOutput = allowDirectOutput;
        }

        @Override
        public boolean allowDirectOutput() {
            return allowDirectOutput;
        }
    }
}
