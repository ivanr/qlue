package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.view.Canoe;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Canoe} that records every {@link #currentContext()} call made against it.
 *
 * <p>{@code CanoeReferenceInsertionHandler} calls {@code currentContext()} exactly once per
 * reference, immediately before choosing an encoder, and that call is the only place in the system
 * where "the context at this reference position" exists as a value. Recording the calls is therefore
 * the only way to observe the sequence {@code ParserSteeringTest} states its property over; nothing
 * about the rendered bytes recovers it, because two different contexts can produce the same bytes
 * (both {@code CTX_JS} and {@code CTX_SUPPRESS} produce none at all).
 *
 * <p>One call is not a reference. {@code CanoeTestSupport.render()} asks for the final context when
 * it builds its result, so the last entry is the state the machine finished in rather than a
 * reference position. That is deliberate and it is compared like the rest: if a payload changed
 * where the parser ended up, the property has been broken whether or not a reference sat there.
 */
public final class ContextRecordingCanoe extends Canoe {

    private final List<Integer> contexts = new ArrayList<>();

    public ContextRecordingCanoe(Writer writer) {
        super(writer);
    }

    @Override
    public int currentContext() {
        int context = super.currentContext();
        contexts.add(context);
        return context;
    }

    /**
     * The contexts observed, in order: one per reference the handler processed, then the final
     * context the harness asked for.
     *
     * <p>References the handler skips do not appear. A {@code $_x.} reference returns early from
     * {@code referenceInsert()} before consulting the writer, so it contributes no entry — which is
     * correct, since it is not encoded for any context.
     */
    public List<Integer> contexts() {
        return Collections.unmodifiableList(contexts);
    }
}
