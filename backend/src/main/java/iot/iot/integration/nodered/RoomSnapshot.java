package iot.iot.integration.nodered;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed view of GET /smarthome/api/state of the SmartHome flow:
 * {kind:"room-dashboard", serverAt, links, sensor, relay, sensorAgeMs, relayAgeMs, pending, notice}.
 * Values of a link that is not fresh are reported as null so that stale data is never stored or shown.
 */
public record RoomSnapshot(
    long serverAtMillis, boolean mqtt, boolean sensorLink, boolean relayLink,
    Double temp, Double humid, Double brightness, Boolean occupancy, boolean dhtOk, boolean pirReady,
    Integer sensorRssi, Long sensorAgeMs,
    Boolean fanOn, Boolean lightOn, String fanMode, String lightMode, boolean uartOk,
    Double fanThresholdC, Integer lightOffPercent, Integer relayRssi, Long relayAgeMs,
    String pendingCommand) {

    public static final String KIND = "room-dashboard";

    public static RoomSnapshot parse(JsonNode raw) {
        if (raw == null || !raw.isObject() || !KIND.equals(raw.path("kind").asText(""))) {
            throw new IllegalArgumentException("Unsupported Node-RED state payload");
        }
        JsonNode links = raw.path("links"), sensor = raw.path("sensor"), relay = raw.path("relay");
        boolean sensorLink = links.path("sensor").asBoolean(false);
        boolean relayLink = links.path("relay").asBoolean(false);
        boolean dhtOk = sensorLink && sensor.path("dht_ok").asBoolean(false);
        boolean pirReady = sensorLink && sensor.path("pir_ready").asBoolean(false);

        Double percent = sensorLink ? number(sensor.path("light_percent")) : null;
        JsonNode direction = relay.path("light_adc_high_is_bright");
        boolean highIsBright = !(relayLink && direction.isBoolean() && !direction.booleanValue());
        Double brightness = percent == null ? null : (highIsBright ? percent : 100.0 - percent);
        int motion = sensorLink ? sensor.path("motion").asInt(-1) : -1;
        Boolean occupancy = pirReady && (motion == 0 || motion == 1) ? Boolean.valueOf(motion == 1) : null;

        return new RoomSnapshot(
            raw.path("serverAt").asLong(System.currentTimeMillis()),
            links.path("mqtt").asBoolean(false), sensorLink, relayLink,
            dhtOk ? number(sensor.path("temperature")) : null,
            dhtOk ? number(sensor.path("humidity")) : null,
            brightness, occupancy, dhtOk, pirReady,
            sensorLink ? integer(sensor.path("rssi")) : null, longValue(raw.path("sensorAgeMs")),
            relayLink ? bool(relay.path("fan_on")) : null, relayLink ? bool(relay.path("light_on")) : null,
            relayLink ? mode(relay.path("fan_mode")) : null, relayLink ? mode(relay.path("light_mode")) : null,
            relayLink && relay.path("uart_ok").asBoolean(false),
            relayLink ? number(relay.path("fan_threshold_c")) : null,
            relayLink ? integer(relay.path("light_off_percent")) : null,
            relayLink ? integer(relay.path("rssi")) : null, longValue(raw.path("relayAgeMs")),
            raw.path("pending").path("command").asText(null));
    }

    private static Double number(JsonNode node) { return node.isNumber() ? Double.valueOf(node.doubleValue()) : null; }
    private static Integer integer(JsonNode node) { return node.isNumber() ? Integer.valueOf(node.intValue()) : null; }
    private static Long longValue(JsonNode node) { return node.isNumber() ? Long.valueOf(node.longValue()) : null; }
    private static Boolean bool(JsonNode node) { return node.isBoolean() ? Boolean.valueOf(node.booleanValue()) : null; }
    private static String mode(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank() ? node.textValue() : "UNKNOWN";
    }
}
