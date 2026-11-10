package iot.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import iot.iot.dto.*;
import iot.iot.exception.IntegrationException;
import iot.iot.integration.nodered.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class NodeRedClientTests {
    private static final String ACCEPTED = "{\"ok\":true,\"message\":\"Đã gửi lệnh, đang chờ ESP32 cập nhật trạng thái.\"}";
    private final NodeRedStub stub = new NodeRedStub();
    private final ObjectMapper mapper = new ObjectMapper();
    private final NodeRedProperties props = new NodeRedProperties(stub.baseUrl(), "/smarthome/api/state", "/smarthome/api/command",
        500, 1000, "node2_actuator", "node1_sensor", 600, 20);
    private final NodeRedHttpConfig config = new NodeRedHttpConfig();
    private final CloseableHttpClient http = config.nodeRedHttpClient(props);
    private final NodeRedClient client = new NodeRedClient(config.nodeRedRestClient(http), props, mapper);
    private final NodeRedCommandRequest command = new NodeRedCommandRequest(ControlTarget.FAN, ControlAction.ON);
    @AfterEach void cleanup() throws Exception { http.close(); stub.close(); }

    private static String state(String notice) {
        return "{\"kind\":\"room-dashboard\",\"serverAt\":1,\"links\":{},\"sensor\":{},\"relay\":{},\"pending\":null,\"notice\":" + notice + "}";
    }
    private static String notice(String status, long at, String command) {
        return "{\"status\":\"" + status + "\",\"at\":" + at + (command == null ? "" : ",\"command\":\"" + command + "\"") + ",\"message\":\"m\"}";
    }
    private static NodeRedStub.Reply json(int status, String body) { return new NodeRedStub.Reply(status, body, 0, false); }
    private int count(String method) { return (int) stub.calls.stream().filter(call -> call.method().equals(method)).count(); }

    @Test
    void statePreservesNestedUnknownFieldsWithoutReadingTelemetry() throws Exception {
        String json = "{\"sensors\":[{\"temp\":29.5}],\"extra\":{\"online\":false,\"future_field\":[1,null]}}";
        stub.reply(200, json);
        assertEquals(mapper.readTree(json), client.state());
        assertEquals(1, stub.calls.size()); assertEquals("GET", stub.calls.get(0).method());
        assertEquals("/smarthome/api/state", stub.calls.get(0).path());
        assertNull(stub.calls.get(0).authorization());
    }

    @Test
    void wireCommandMatchesTheFlowAllowList() {
        assertEquals("FAN_ON", new NodeRedCommandRequest(ControlTarget.FAN, ControlAction.ON).wireCommand());
        assertEquals("FAN_AUTO", new NodeRedCommandRequest(ControlTarget.FAN, ControlAction.AUTO).wireCommand());
        assertEquals("LIGHT_OFF", new NodeRedCommandRequest(ControlTarget.LIGHT, ControlAction.OFF).wireCommand());
        assertEquals("LIGHT_AUTO", new NodeRedCommandRequest(ControlTarget.LIGHT, ControlAction.AUTO).wireCommand());
    }

    @Test
    void successIsOnlyReportedWhenTheStateNoticeConfirmsTheSameCommand() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        stub.handler = call -> {
            if (call.method().equals("POST")) return json(202, ACCEPTED);
            return switch (gets.incrementAndGet()) {
                case 1 -> json(200, state(notice("success", 100, "FAN_OFF")));   // baseline: older command
                case 2 -> json(200, state(notice("pending", 200, null)));
                default -> json(200, state(notice("success", 300, "FAN_ON")));
            };
        };
        var result = client.command(command);
        assertEquals(CommandStatus.SUCCESS, result.status());
        assertEquals("node2_actuator", result.payload().path("device_id").asText());
        assertEquals("FAN", result.payload().path("target").asText());
        assertEquals("ON", result.payload().path("action").asText());
        assertEquals(1, count("POST")); assertEquals(3, count("GET"));
        var post = stub.calls.stream().filter(call -> call.method().equals("POST")).findFirst().orElseThrow();
        assertEquals("/smarthome/api/command", post.path());
        assertEquals(mapper.readTree("{\"command\":\"FAN_ON\"}"), mapper.readTree(post.body()));
        assertNull(post.authorization());
    }

    @Test
    void autoCommandIsSentAsFanAuto() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        stub.handler = call -> call.method().equals("POST") ? json(202, ACCEPTED)
            : json(200, state(gets.incrementAndGet() == 1 ? "{}" : notice("success", 5, "FAN_AUTO")));
        var result = client.command(new NodeRedCommandRequest(ControlTarget.FAN, ControlAction.AUTO));
        assertEquals("AUTO", result.payload().path("action").asText());
        var post = stub.calls.stream().filter(call -> call.method().equals("POST")).findFirst().orElseThrow();
        assertEquals(mapper.readTree("{\"command\":\"FAN_AUTO\"}"), mapper.readTree(post.body()));
    }

    @Test
    void refusedCommandIs409AndNeverPolls() {
        stub.handler = call -> call.method().equals("POST") ? json(409, "{\"ok\":false,\"message\":\"busy\"}") : json(200, state("{}"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST")); assertEquals(1, count("GET"));
    }

    @Test
    void flowGivingUpOnTheRelayMapsTo504() {
        AtomicInteger gets = new AtomicInteger();
        stub.handler = call -> call.method().equals("POST") ? json(202, ACCEPTED)
            : json(200, state(gets.incrementAndGet() == 1 ? "{}" : notice("error", 900, null)));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
    }

    @Test
    void noticeForAnotherCommandOrStillPendingTimesOutWithoutResending() {
        AtomicInteger gets = new AtomicInteger();
        stub.handler = call -> call.method().equals("POST") ? json(202, ACCEPTED)
            : json(200, state(gets.incrementAndGet() == 1 ? "{}" : notice("success", 50, "LIGHT_ON")));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"ok\":false}", "{\"ok\":true} {}"})
    void invalidPostBodiesAre502WithoutRetry(String body) {
        stub.handler = call -> call.method().equals("POST") ? json(202, body) : json(200, state("{}"));
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 503, 302})
    void upstreamErrorsAndRedirectsDoNotRetryOrTryAlternateEndpoint(int status) {
        stub.handler = call -> call.method().equals("POST") ? json(status, "{}") : json(200, state("{}"));
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
        assertEquals("/smarthome/api/command", stub.calls.get(1).path());
    }

    @Test
    void postReadTimeoutDoesNotResendTheCommand() {
        stub.handler = call -> call.method().equals("POST") ? new NodeRedStub.Reply(202, ACCEPTED, 1800, false) : json(200, state("{}"));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
    }

    @Test
    void droppedConnectionDuringPostDoesNotRetry() {
        stub.handler = call -> call.method().equals("POST") ? new NodeRedStub.Reply(200, "{}", 0, true) : json(200, state("{}"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(IntegrationException.class, () -> client.command(command)).getStatus());
        assertEquals(1, count("POST"));
    }

    @Test
    void offlineMapsTo503() {
        stub.close();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(IntegrationException.class, client::state).getStatus());
    }

    @Test
    void configurationRejectsUnboundedTimeoutsAndExternalApiPaths() {
        assertThrows(IllegalStateException.class, () -> new NodeRedProperties(stub.baseUrl(), "/state", "//other-host/cmd", 500, 250, "node2_actuator", "node1_sensor", 8000, 400));
        assertThrows(IllegalStateException.class, () -> new NodeRedProperties(stub.baseUrl(), "/state", "/cmd", 0, 250, "node2_actuator", "node1_sensor", 8000, 400));
        assertThrows(IllegalStateException.class, () -> new NodeRedProperties(stub.baseUrl(), "/state", "/cmd", 500, 250, "node2_actuator", "node1_sensor", 0, 400));
        assertThrows(IllegalStateException.class, () -> new NodeRedProperties(stub.baseUrl(), "/state", "/cmd", 500, 250, "node2_actuator", " ", 8000, 400));
    }
}
