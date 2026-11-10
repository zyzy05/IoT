package iot.iot.integration.nodered;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import iot.iot.exception.IntegrationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Converts the Node-RED room state into the device-record contract used by the React Dashboard. */
@Component
public class NodeRedStateMapper {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final ObjectMapper mapper;
    private final NodeRedProperties properties;
    public NodeRedStateMapper(ObjectMapper mapper, NodeRedProperties properties) {
        this.mapper = mapper; this.properties = properties;
    }

    public RoomSnapshot snapshot(JsonNode raw) {
        try { return RoomSnapshot.parse(raw); }
        catch (IllegalArgumentException error) {
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "Node-RED returned an invalid or uncorrelated response");
        }
    }

    /**
     * {server_at, links, busy, devices:[sensor record, actuator record]}.
     * Sensor record: temp, humid, lux (brightness %, direction-corrected, NOT lux), occupancy.
     * Actuator record: FAN, LIGHT, fan_mode, light_mode, AUTO (both channels AUTO).
     */
    public ObjectNode toApi(RoomSnapshot s) {
        ObjectNode root = mapper.createObjectNode();
        root.put("server_at", stamp(s.serverAtMillis(), 0L));
        ObjectNode links = root.putObject("links");
        links.put("mqtt", s.mqtt()); links.put("sensor", s.sensorLink()); links.put("relay", s.relayLink());
        root.put("busy", s.pendingCommand() != null);
        if (s.pendingCommand() != null) root.put("pending_command", s.pendingCommand());
        ArrayNode devices = root.putArray("devices");

        ObjectNode sensor = devices.addObject();
        sensor.put("device_id", properties.sensorDeviceId());
        sensor.put("type", "SENSOR");
        sensor.put("connected", s.sensorLink());
        sensor.put("status", s.sensorLink() ? "ONLINE" : "OFFLINE");
        number(sensor, "temp", s.temp()); number(sensor, "humid", s.humid()); number(sensor, "lux", s.brightness());
        if (s.occupancy() == null) sensor.putNull("occupancy"); else sensor.put("occupancy", s.occupancy());
        sensor.put("dht_ok", s.dhtOk()); sensor.put("pir_ready", s.pirReady());
        number(sensor, "rssi", s.sensorRssi());
        sensor.put("timestamp", stamp(s.serverAtMillis(), s.sensorAgeMs()));

        ObjectNode actuator = devices.addObject();
        actuator.put("device_id", properties.actuatorDeviceId());
        actuator.put("type", "ACTUATOR");
        actuator.put("connected", s.relayLink());
        actuator.put("status", s.relayLink() ? "ONLINE" : "OFFLINE");
        if (s.fanOn() == null) actuator.putNull("FAN"); else actuator.put("FAN", s.fanOn());
        if (s.lightOn() == null) actuator.putNull("LIGHT"); else actuator.put("LIGHT", s.lightOn());
        if (s.fanMode() == null) actuator.putNull("fan_mode"); else actuator.put("fan_mode", s.fanMode());
        if (s.lightMode() == null) actuator.putNull("light_mode"); else actuator.put("light_mode", s.lightMode());
        if (s.relayLink()) actuator.put("AUTO", "AUTO".equals(s.fanMode()) && "AUTO".equals(s.lightMode()));
        else actuator.putNull("AUTO");
        actuator.put("uart_ok", s.uartOk());
        number(actuator, "fan_threshold_c", s.fanThresholdC()); number(actuator, "light_off_percent", s.lightOffPercent());
        number(actuator, "rssi", s.relayRssi());
        actuator.put("timestamp", stamp(s.serverAtMillis(), s.relayAgeMs()));
        return root;
    }

    private static void number(ObjectNode node, String name, Number value) {
        if (value == null) node.putNull(name);
        else if (value instanceof Double d) node.put(name, d.doubleValue());
        else node.put(name, value.longValue());
    }

    private static String stamp(long serverAtMillis, Long ageMillis) {
        if (ageMillis == null) return null;
        return LOCAL_SECONDS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(serverAtMillis - ageMillis), VIETNAM));
    }
}
