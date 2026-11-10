package iot.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import iot.iot.exception.IntegrationException;
import iot.iot.integration.nodered.NodeRedProperties;
import iot.iot.integration.nodered.NodeRedStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the exact JSON shape produced by SmartHome_Dashboard_AUTO_SETTINGS.json (GET /smarthome/api/state). */
class NodeRedStateMapperTests {
    private final ObjectMapper json = new ObjectMapper();
    private final NodeRedStateMapper mapper = new NodeRedStateMapper(json,
        new NodeRedProperties("http://127.0.0.1:1880", "/s", "/c", 1000, 1000, "node2_actuator", "node1_sensor", 8000, 400));
    private static final String LIVE = """
        {"kind":"room-dashboard","serverAt":1762650000000,"links":{"mqtt":true,"sensor":true,"relay":true},
         "sensor":{"temperature":29.5,"humidity":71.0,"dht_ok":true,"motion":1,"pir_ready":true,"ldr_adc":900,"light_percent":22,"rssi":-60},
         "relay":{"light_on":true,"fan_on":false,"light_mode":"AUTO","fan_mode":"MANUAL_OFF","uart_ok":true,"rssi":-55,
                  "settings_supported":true,"fan_threshold_c":35.0,"light_off_percent":70,"light_adc_high_is_bright":true,
                  "settings_persisted":false,"settings_storage_ok":true,"brightness_percent":22},
         "sensorAgeMs":500,"relayAgeMs":800,"pending":null,"notice":{}}""";

    private JsonNode device(JsonNode api, String id) {
        for (JsonNode node : api.path("devices")) if (id.equals(node.path("device_id").asText())) return node;
        throw new AssertionError("device not found: " + id);
    }

    @Test
    void mapsLiveFlowStateToDeviceRecordsExpectedByTheDashboard() throws Exception {
        var api = mapper.toApi(mapper.snapshot(json.readTree(LIVE)));
        var sensor = device(api, "node1_sensor"); var actuator = device(api, "node2_actuator");
        assertTrue(sensor.path("connected").asBoolean()); assertEquals("ONLINE", sensor.path("status").asText());
        assertEquals(29.5, sensor.path("temp").asDouble()); assertEquals(71.0, sensor.path("humid").asDouble());
        assertEquals(22.0, sensor.path("lux").asDouble()); assertTrue(sensor.path("occupancy").asBoolean());
        assertEquals("2025-11-09T07:59:59", sensor.path("timestamp").asText());
        assertTrue(actuator.path("connected").asBoolean());
        assertTrue(actuator.path("LIGHT").asBoolean()); assertFalse(actuator.path("FAN").asBoolean());
        assertEquals("AUTO", actuator.path("light_mode").asText()); assertEquals("MANUAL_OFF", actuator.path("fan_mode").asText());
        assertFalse(actuator.path("AUTO").asBoolean());
        assertFalse(api.path("busy").asBoolean()); assertEquals("2025-11-09T08:00:00", api.path("server_at").asText());
    }

    @Test
    void reversedLdrDirectionInvertsBrightness() throws Exception {
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(LIVE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("relay")).put("light_adc_high_is_bright", false);
        assertEquals(78.0, device(mapper.toApi(mapper.snapshot(raw)), "node1_sensor").path("lux").asDouble());
    }

    @Test
    void bothChannelsInAutoReportAutoTrueAndPendingCommandIsBusy() throws Exception {
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(LIVE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("relay")).put("fan_mode", "AUTO");
        raw.putObject("pending").put("command", "FAN_ON").put("at", 1);
        var api = mapper.toApi(mapper.snapshot(raw));
        assertTrue(device(api, "node2_actuator").path("AUTO").asBoolean());
        assertTrue(api.path("busy").asBoolean()); assertEquals("FAN_ON", api.path("pending_command").asText());
    }

    @Test
    void staleLinksProduceOfflineRecordsWithNullValuesInsteadOfOldData() throws Exception {
        String stale = """
            {"kind":"room-dashboard","serverAt":1762650000000,"links":{"mqtt":true,"sensor":false,"relay":false},
             "sensor":{"temperature":null,"humidity":null,"dht_ok":false,"motion":null,"pir_ready":false,"light_percent":null,"ldr_adc":null,"rssi":null},
             "relay":{"light_on":null,"fan_on":null,"light_mode":"UNKNOWN","fan_mode":"UNKNOWN","uart_ok":false,"rssi":null},
             "sensorAgeMs":null,"relayAgeMs":null,"pending":null,"notice":{}}""";
        var api = mapper.toApi(mapper.snapshot(json.readTree(stale)));
        var sensor = device(api, "node1_sensor"); var actuator = device(api, "node2_actuator");
        assertFalse(sensor.path("connected").asBoolean()); assertEquals("OFFLINE", sensor.path("status").asText());
        for (String field : new String[]{"temp", "humid", "lux", "occupancy", "timestamp"}) assertTrue(sensor.path(field).isNull(), field);
        assertFalse(actuator.path("connected").asBoolean());
        for (String field : new String[]{"FAN", "LIGHT", "AUTO", "fan_mode", "light_mode"}) assertTrue(actuator.path(field).isNull(), field);
    }

    @Test
    void motionIsUnknownUntilPirIsReadyAndDhtErrorHidesTemperature() throws Exception {
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(LIVE);
        var sensor = (com.fasterxml.jackson.databind.node.ObjectNode) raw.path("sensor");
        sensor.put("pir_ready", false); sensor.put("dht_ok", false);
        var record = device(mapper.toApi(mapper.snapshot(raw)), "node1_sensor");
        assertTrue(record.path("occupancy").isNull()); assertTrue(record.path("temp").isNull()); assertTrue(record.path("humid").isNull());
        assertEquals(22.0, record.path("lux").asDouble());
    }

    @Test
    void unrecognisedPayloadIs502() throws Exception {
        for (String body : new String[]{"{}", "[]", "{\"kind\":\"other\"}", "{\"temp\":1,\"FAN\":true}"}) {
            var error = assertThrows(IntegrationException.class, () -> mapper.snapshot(json.readTree(body)));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        }
    }
}
