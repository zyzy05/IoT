package iot.iot.integration.nodered;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.nodered")
public record NodeRedProperties(String baseUrl, String statePath, String commandPath,
                                int connectTimeout, int readTimeout, String actuatorDeviceId,
                                @DefaultValue("node1_sensor") String sensorDeviceId,
                                @DefaultValue("8000") int ackTimeout,
                                @DefaultValue("400") int ackPollInterval) {
    public NodeRedProperties {
        URI base;
        try { base = URI.create(baseUrl); }
        catch (RuntimeException error) { throw new IllegalStateException("NODERED_BASE_URL must be a valid HTTP(S) origin"); }
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
            || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
            || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalStateException("NODERED_BASE_URL must be an HTTP(S) origin without credentials or path");
        }
        for (String path : new String[]{statePath, commandPath}) {
            if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("?") || path.contains("#") || path.contains("\\") || path.contains("..")) {
                throw new IllegalStateException("Node-RED API paths must be absolute local paths without query or traversal");
            }
            try { URI.create(path); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("Invalid Node-RED API path"); }
        }
        if (connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalStateException("Node-RED timeouts must be positive milliseconds");
        }
        if (ackTimeout <= 0 || ackPollInterval <= 0) {
            throw new IllegalStateException("Node-RED ACK timeout and poll interval must be positive milliseconds");
        }
        if (actuatorDeviceId == null || actuatorDeviceId.isBlank() || actuatorDeviceId.length() > 50) {
            throw new IllegalStateException("NODERED_ACTUATOR_DEVICE_ID must contain 1 to 50 characters");
        }
        if (sensorDeviceId == null || sensorDeviceId.isBlank() || sensorDeviceId.length() > 50) {
            throw new IllegalStateException("NODERED_SENSOR_DEVICE_ID must contain 1 to 50 characters");
        }
    }
    public URI stateUri() { return URI.create(baseUrl).resolve(statePath); }
    public URI commandUri() { return URI.create(baseUrl).resolve(commandPath); }
}
