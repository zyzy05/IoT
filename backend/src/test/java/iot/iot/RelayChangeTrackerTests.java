package iot.iot;

import static iot.iot.service.RelayChangeTracker.Source.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import iot.iot.dto.ControlTarget;
import iot.iot.service.RelayChangeTracker;
import org.junit.jupiter.api.Test;

class RelayChangeTrackerTests {
    private final RelayChangeTracker tracker = new RelayChangeTracker();

    @Test
    void firstObservationAndUnchangedStateAreIgnored() {
        assertEquals(NONE, tracker.observe(ControlTarget.FAN, true, true, 0));
        assertEquals(NONE, tracker.observe(ControlTarget.FAN, true, true, 10));
    }
    @Test
    void autoChangeIsRuleEngineAndManualChangeIsExternal() {
        tracker.observe(ControlTarget.FAN, false, true, 0);
        assertEquals(RULE_ENGINE, tracker.observe(ControlTarget.FAN, true, true, 10));
        assertEquals(EXTERNAL, tracker.observe(ControlTarget.FAN, false, false, 20));
    }
    @Test
    void changeCausedByTheBffIsConsumedOnceAndNotAttributedToTheRuleEngine() {
        tracker.observe(ControlTarget.LIGHT, false, true, 0);
        tracker.expect(ControlTarget.LIGHT, true, 100);
        assertEquals(BFF_COMMAND, tracker.observe(ControlTarget.LIGHT, true, false, 200));
        assertEquals(EXTERNAL, tracker.observe(ControlTarget.LIGHT, false, false, 300));
    }
    @Test
    void confirmedCommandMovesTheBaselineSoTheCollectorSeesNoChange() {
        tracker.observe(ControlTarget.FAN, false, true, 0);
        tracker.expect(ControlTarget.FAN, true, 100);
        tracker.confirmed(ControlTarget.FAN, true);
        assertEquals(NONE, tracker.observe(ControlTarget.FAN, true, false, 200));
    }
    @Test
    void expiredOrOppositeExpectationDoesNotHideAChange() {
        tracker.observe(ControlTarget.FAN, false, false, 0);
        tracker.expect(ControlTarget.FAN, true, 0);
        assertEquals(EXTERNAL, tracker.observe(ControlTarget.FAN, true, false, 16_000));
        tracker.expect(ControlTarget.FAN, true, 20_000);
        assertEquals(EXTERNAL, tracker.observe(ControlTarget.FAN, false, false, 20_100));
    }
    @Test
    void clearedExpectationIsForgotten() {
        tracker.observe(ControlTarget.FAN, false, false, 0);
        tracker.expect(ControlTarget.FAN, true, 100);
        tracker.clearExpectation(ControlTarget.FAN);
        assertEquals(EXTERNAL, tracker.observe(ControlTarget.FAN, true, false, 200));
    }
    @Test
    void channelsAreTrackedIndependently() {
        tracker.observe(ControlTarget.FAN, false, true, 0);
        assertEquals(NONE, tracker.observe(ControlTarget.LIGHT, true, true, 10));
    }
}
