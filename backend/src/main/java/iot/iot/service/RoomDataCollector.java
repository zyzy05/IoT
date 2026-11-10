package iot.iot.service;

import iot.iot.dto.ControlTarget;
import iot.iot.entity.CommandLog;
import iot.iot.entity.Telemetry;
import iot.iot.exception.IntegrationException;
import iot.iot.integration.nodered.NodeRedClient;
import iot.iot.integration.nodered.NodeRedProperties;
import iot.iot.integration.nodered.NodeRedStateMapper;
import iot.iot.integration.nodered.RoomSnapshot;
import iot.iot.repository.CommandLogRepository;
import iot.iot.repository.DeviceRepository;
import iot.iot.repository.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Replaces the Node-RED to MySQL arrow: the flow has no database node, so the BFF polls the flow state and
 * writes telemetry (every telemetry-interval) and relay changes that were not caused by the BFF itself.
 *
 * trigger_by: RULE_ENGINE = relay changed while its channel is in AUTO mode (ESP32 rule logic);
 * NODERED_UI = relay changed in MANUAL mode without a BFF command (e.g. the Node-RED dashboard).
 */
@Component
@ConditionalOnProperty(prefix = "app.collector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoomDataCollector {
    public static final String RULE_ENGINE = "RULE_ENGINE";
    public static final String EXTERNAL_ACTOR = "NODERED_UI";
    private static final Logger log = LoggerFactory.getLogger(RoomDataCollector.class);

    private final NodeRedClient nodeRed;
    private final NodeRedStateMapper mapper;
    private final NodeRedProperties properties;
    private final DeviceRepository devices;
    private final TelemetryRepository telemetry;
    private final CommandLogRepository commands;
    private final RelayChangeTracker tracker;
    private final long telemetryIntervalMs;
    private long lastTelemetryAt;
    private boolean unavailableLogged;

    public RoomDataCollector(NodeRedClient nodeRed, NodeRedStateMapper mapper, NodeRedProperties properties,
                             DeviceRepository devices, TelemetryRepository telemetry, CommandLogRepository commands,
                             RelayChangeTracker tracker,
                             @Value("${app.collector.telemetry-interval-ms:15000}") long telemetryIntervalMs) {
        this.nodeRed = nodeRed; this.mapper = mapper; this.properties = properties; this.devices = devices;
        this.telemetry = telemetry; this.commands = commands; this.tracker = tracker;
        this.telemetryIntervalMs = telemetryIntervalMs;
    }

    @Scheduled(fixedDelayString = "${app.collector.poll-interval-ms:2000}",
               initialDelayString = "${app.collector.initial-delay-ms:5000}")
    public void poll() { pollOnce(System.currentTimeMillis()); }

    public void pollOnce(long nowMillis) {
        RoomSnapshot snapshot;
        try {
            snapshot = mapper.snapshot(nodeRed.state());
            unavailableLogged = false;
        } catch (IntegrationException error) {
            if (!unavailableLogged) { log.warn("Room data collector: Node-RED state unavailable ({})", error.getMessage()); unavailableLogged = true; }
            return;
        }
        try {
            recordRelayChanges(snapshot, nowMillis);
            recordTelemetry(snapshot, nowMillis);
        } catch (DataAccessException | TransactionException error) {
            log.warn("Room data collector: could not store data ({})", error.getClass().getSimpleName());
        }
    }

    private void recordRelayChanges(RoomSnapshot s, long now) {
        if (!s.relayLink()) return;
        observe(ControlTarget.FAN, s.fanOn(), s.fanMode(), now);
        observe(ControlTarget.LIGHT, s.lightOn(), s.lightMode(), now);
    }

    private void observe(ControlTarget target, Boolean on, String mode, long now) {
        if (on == null) return;
        RelayChangeTracker.Source source = tracker.observe(target, on, "AUTO".equals(mode), now);
        if (source == RelayChangeTracker.Source.RULE_ENGINE) saveLog(target, on, RULE_ENGINE);
        else if (source == RelayChangeTracker.Source.EXTERNAL) saveLog(target, on, EXTERNAL_ACTOR);
    }

    private void saveLog(ControlTarget target, boolean on, String actor) {
        CommandLog entry = new CommandLog();
        entry.setDevice(devices.getReferenceById(properties.actuatorDeviceId()));
        entry.setTarget(target.name()); entry.setAction(on ? "ON" : "OFF"); entry.setTriggerBy(actor);
        commands.save(entry);
    }

    private void recordTelemetry(RoomSnapshot s, long now) {
        if (!s.sensorLink()) return;
        if (s.temp() == null && s.humid() == null && s.brightness() == null && s.occupancy() == null) return;
        if (lastTelemetryAt != 0 && now - lastTelemetryAt < telemetryIntervalMs) return;
        lastTelemetryAt = now;
        String deviceId = properties.sensorDeviceId();
        if (!devices.existsByDeviceId(deviceId)) { log.warn("Room data collector: sensor device {} is not registered", deviceId); return; }
        Telemetry row = new Telemetry();
        row.setDevice(devices.getReferenceById(deviceId));
        row.setTemp(s.temp()); row.setHumid(s.humid()); row.setLux(s.brightness()); row.setOccupancy(s.occupancy());
        telemetry.save(row);
    }
}
