package iot.iot.integration.nodered;

import iot.iot.dto.ControlAction;
import iot.iot.dto.ControlTarget;

/** Domain command received from the React API (target + action). */
public record NodeRedCommandRequest(ControlTarget target, ControlAction action) {
    /**
     * The flow SmartHome_Dashboard_AUTO_SETTINGS only accepts {"command":"<TARGET>_<ACTION>"},
     * e.g. FAN_ON, FAN_OFF, FAN_AUTO, LIGHT_ON, LIGHT_OFF, LIGHT_AUTO.
     */
    public String wireCommand() { return target.name() + "_" + action.name(); }
}
