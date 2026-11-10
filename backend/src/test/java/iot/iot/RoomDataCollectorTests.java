package iot.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import iot.iot.dto.ControlTarget;
import iot.iot.entity.CommandLog;
import iot.iot.entity.Device;
import iot.iot.entity.Telemetry;
import iot.iot.exception.IntegrationException;
import iot.iot.integration.nodered.*;
import iot.iot.repository.*;
import iot.iot.service.RelayChangeTracker;
import iot.iot.service.RoomDataCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomDataCollectorTests {
    private static final String BASE = """
        {"kind":"room-dashboard","serverAt":1762650000000,"links":{"mqtt":true,"sensor":true,"relay":true},
         "sensor":{"temperature":29.5,"humidity":71.0,"dht_ok":true,"motion":1,"pir_ready":true,"light_percent":22,"rssi":-60},
         "relay":{"light_on":false,"fan_on":false,"light_mode":"AUTO","fan_mode":"AUTO","uart_ok":true,"light_adc_high_is_bright":true},
         "sensorAgeMs":500,"relayAgeMs":800,"pending":null,"notice":{}}""";
    private final ObjectMapper json = new ObjectMapper();
    private final NodeRedClient client = mock(NodeRedClient.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final TelemetryRepository telemetry = mock(TelemetryRepository.class);
    private final CommandLogRepository commands = mock(CommandLogRepository.class);
    private final RelayChangeTracker tracker = new RelayChangeTracker();
    private final NodeRedProperties props = new NodeRedProperties("http://127.0.0.1:1880", "/s", "/c", 1000, 1000, "node2_actuator", "node1_sensor", 8000, 400);
    private final RoomDataCollector collector = new RoomDataCollector(client, new NodeRedStateMapper(json, props), props, devices, telemetry, commands, tracker, 15_000);

    @BeforeEach void setup() {
        when(devices.existsByDeviceId(anyString())).thenReturn(true);
        when(devices.getReferenceById(anyString())).thenReturn(new Device());
    }
    private ObjectNode state() throws Exception { return (ObjectNode) json.readTree(BASE); }

    @Test
    void storesTelemetryAtMostOncePerInterval() throws Exception {
        when(client.state()).thenReturn(state());
        collector.pollOnce(1_000); collector.pollOnce(3_000); collector.pollOnce(15_999); collector.pollOnce(16_000);
        var captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(telemetry, times(2)).save(captor.capture());
        Telemetry row = captor.getAllValues().get(0);
        assertEquals(29.5, row.getTemp()); assertEquals(71.0, row.getHumid()); assertEquals(22.0, row.getLux()); assertEquals(Boolean.TRUE, row.getOccupancy());
    }

    @Test
    void doesNotStoreTelemetryWhenSensorLinkIsStale() throws Exception {
        ObjectNode stale = state();
        ((ObjectNode) stale.path("links")).put("sensor", false);
        when(client.state()).thenReturn(stale);
        collector.pollOnce(1_000);
        verify(telemetry, never()).save(any());
    }

    @Test
    void invalidDhtStoresOnlyTheValuesThatAreKnown() throws Exception {
        ObjectNode raw = state();
        ((ObjectNode) raw.path("sensor")).put("dht_ok", false);
        when(client.state()).thenReturn(raw);
        collector.pollOnce(1_000);
        var captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(telemetry).save(captor.capture());
        assertNull(captor.getValue().getTemp()); assertNull(captor.getValue().getHumid());
        assertEquals(22.0, captor.getValue().getLux());
    }

    @Test
    void relayChangeInAutoModeIsLoggedAsRuleEngineButFirstPollOnlySetsTheBaseline() throws Exception {
        when(client.state()).thenReturn(state());
        collector.pollOnce(1_000);
        verify(commands, never()).save(any());
        ObjectNode changed = state();
        ((ObjectNode) changed.path("relay")).put("light_on", true);
        when(client.state()).thenReturn(changed);
        collector.pollOnce(3_000);
        var captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(commands).save(captor.capture());
        assertEquals("LIGHT", captor.getValue().getTarget()); assertEquals("ON", captor.getValue().getAction());
        assertEquals("RULE_ENGINE", captor.getValue().getTriggerBy());
        collector.pollOnce(5_000);
        verify(commands, times(1)).save(any());
    }

    @Test
    void manualChangeWithoutBffCommandIsLoggedAsNodeRedUi() throws Exception {
        ObjectNode before = state();
        ((ObjectNode) before.path("relay")).put("fan_mode", "MANUAL_OFF");
        when(client.state()).thenReturn(before);
        collector.pollOnce(1_000);
        ObjectNode after = state();
        ((ObjectNode) after.path("relay")).put("fan_mode", "MANUAL_ON").put("fan_on", true);
        when(client.state()).thenReturn(after);
        collector.pollOnce(3_000);
        var captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(commands).save(captor.capture());
        assertEquals("FAN", captor.getValue().getTarget()); assertEquals("NODERED_UI", captor.getValue().getTriggerBy());
    }

    @Test
    void changeCausedByTheBffIsNotLoggedByTheCollector() throws Exception {
        ObjectNode before = state();
        ((ObjectNode) before.path("relay")).put("fan_mode", "MANUAL_OFF");
        when(client.state()).thenReturn(before);
        collector.pollOnce(1_000);
        tracker.expect(ControlTarget.FAN, true, 1_500);
        ObjectNode after = state();
        ((ObjectNode) after.path("relay")).put("fan_mode", "MANUAL_ON").put("fan_on", true);
        when(client.state()).thenReturn(after);
        collector.pollOnce(2_000);
        verify(commands, never()).save(any());
    }

    @Test
    void staleRelayLinkNeverProducesLogsAndNodeRedOutageIsSwallowed() throws Exception {
        ObjectNode raw = state();
        ((ObjectNode) raw.path("links")).put("relay", false);
        when(client.state()).thenReturn(raw);
        collector.pollOnce(1_000);
        verify(commands, never()).save(any());
        when(client.state()).thenThrow(new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "down"));
        assertDoesNotThrow(() -> collector.pollOnce(3_000));
        verifyNoMoreInteractions(commands);
    }

    @Test
    void databaseFailureDoesNotEscapeTheScheduler() throws Exception {
        when(client.state()).thenReturn(state());
        when(telemetry.save(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertDoesNotThrow(() -> collector.pollOnce(1_000));
    }

    @Test
    void unregisteredSensorDeviceSkipsTheInsert() throws Exception {
        when(devices.existsByDeviceId("node1_sensor")).thenReturn(false);
        when(client.state()).thenReturn(state());
        collector.pollOnce(1_000);
        verify(telemetry, never()).save(any());
    }
}
