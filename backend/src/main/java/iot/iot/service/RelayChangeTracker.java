package iot.iot.service;

import iot.iot.dto.ControlTarget;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Remembers the last observed FAN/LIGHT state so that {@link RoomDataCollector} logs only changes that
 * the BFF did not cause itself (BFF commands are already audited by {@link CommandAuditService}).
 */
@Component
public class RelayChangeTracker {
    public enum Source { NONE, BFF_COMMAND, RULE_ENGINE, EXTERNAL }

    private static final long EXPECTATION_TTL_MS = 15_000;
    private record Expectation(boolean on, long expiresAt) {}
    private final Map<ControlTarget, Boolean> last = new EnumMap<>(ControlTarget.class);
    private final Map<ControlTarget, Expectation> expected = new EnumMap<>(ControlTarget.class);

    /** The BFF is about to command the channel ON/OFF. */
    public synchronized void expect(ControlTarget target, boolean on, long nowMillis) {
        expected.put(target, new Expectation(on, nowMillis + EXPECTATION_TTL_MS));
    }
    public synchronized void clearExpectation(ControlTarget target) { expected.remove(target); }
    /** The BFF command was confirmed by Node-RED: the new state is the baseline, no extra log is needed. */
    public synchronized void confirmed(ControlTarget target, boolean on) { last.put(target, on); expected.remove(target); }

    public synchronized Source observe(ControlTarget target, boolean on, boolean autoMode, long nowMillis) {
        Boolean previous = last.put(target, on);
        if (previous == null || previous.booleanValue() == on) return Source.NONE;
        Expectation expectation = expected.get(target);
        if (expectation != null && expectation.expiresAt() >= nowMillis && expectation.on() == on) {
            expected.remove(target);
            return Source.BFF_COMMAND;
        }
        return autoMode ? Source.RULE_ENGINE : Source.EXTERNAL;
    }
}
