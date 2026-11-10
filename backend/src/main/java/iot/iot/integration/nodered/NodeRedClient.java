package iot.iot.integration.nodered;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import iot.iot.dto.CommandStatus;
import iot.iot.exception.IntegrationException;

/**
 * HTTP client for the SmartHome Node-RED flow (not modified).
 *
 * The flow answers POST /smarthome/api/command with an immediate 202 {ok,message}; the real outcome
 * (ESP32 relay state matching the command) appears in GET /smarthome/api/state as
 * notice{status,at,command}. {@link #command} therefore sends the command and polls the state notice.
 */
@Component
public class NodeRedClient {
    private final RestClient client;
    private final NodeRedProperties properties;
    private final ObjectMapper mapper;
    public NodeRedClient(@Qualifier("nodeRedRestClient") RestClient client,
                         NodeRedProperties properties, ObjectMapper mapper) {
        this.client = client; this.properties = properties; this.mapper = mapper;
    }

    /** Raw JSON of GET /smarthome/api/state. */
    public JsonNode state() {
        return call(client.get().uri(properties.stateUri()).accept(MediaType.APPLICATION_JSON), (req, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IntegrationException(HttpStatus.BAD_GATEWAY, "Node-RED returned an unsuccessful HTTP response");
            }
            return parse(response.getBody());
        });
    }

    /**
     * Sends the command once (never retried) and waits for the flow to confirm that the ESP32 reported a
     * matching relay state. Throws 409 when the flow refused the command (nothing was sent), 504 when no
     * confirmation arrived in time, 502 for invalid responses and 503 when Node-RED is unreachable.
     */
    public NodeRedCommandResponse command(NodeRedCommandRequest command) {
        String wire = command.wireCommand();
        long baseline = state().path("notice").path("at").asLong(0);
        postCommand(wire);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.ackTimeout());
        while (true) {
            pause();
            JsonNode notice = state().path("notice");
            if (notice.path("at").asLong(0) > baseline) {
                String status = notice.path("status").asText("");
                if ("success".equals(status) && wire.equals(notice.path("command").asText(""))) {
                    return success(command, wire, notice);
                }
                // After our own accepted POST the flow only reports an error when it gave up waiting for the relay.
                if ("error".equals(status)) {
                    throw new IntegrationException(HttpStatus.GATEWAY_TIMEOUT,
                        "Node-RED did not receive a matching relay state after the command");
                }
            }
            if (System.nanoTime() - deadline >= 0) throw timeout();
        }
    }

    private void postCommand(String wire) {
        call(client.post().uri(properties.commandUri())
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .body(Map.of("command", wire)), (req, response) -> {
            int code = response.getStatusCode().value();
            if (code == 409) {
                throw new IntegrationException(HttpStatus.CONFLICT,
                    "Node-RED did not send the command: another command is pending or the relay has no fresh state");
            }
            if (code != 200 && code != 202) {
                throw new IntegrationException(HttpStatus.BAD_GATEWAY, "Node-RED returned an unsuccessful HTTP response");
            }
            JsonNode body = parse(response.getBody());
            if (!body.isObject() || !body.path("ok").asBoolean(false)) throw invalidResponse();
            return body;
        });
    }

    private NodeRedCommandResponse success(NodeRedCommandRequest command, String wire, JsonNode notice) {
        ObjectNode body = mapper.createObjectNode();
        body.put("status", CommandStatus.SUCCESS.name());
        body.put("device_id", properties.actuatorDeviceId());
        body.put("target", command.target().name());
        body.put("action", command.action().name());
        body.put("command", wire);
        body.put("message", notice.path("message").asText(""));
        return new NodeRedCommandResponse(CommandStatus.SUCCESS, body);
    }

    private void pause() {
        try { Thread.sleep(properties.ackPollInterval()); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while waiting for Node-RED");
        }
    }

    private JsonNode parse(InputStream stream) {
        try {
            JsonNode body = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(stream);
            if (body == null || body.isNull() || !body.isContainerNode()) throw invalidResponse();
            return body;
        } catch (IOException error) {
            if (isTimeout(error)) throw timeout();
            throw invalidResponse();
        }
    }

    private <T> T call(RestClient.RequestHeadersSpec<?> request, RestClient.RequestHeadersSpec.ExchangeFunction<T> handler) {
        try {
            return request.exchange(handler);
        } catch (ResourceAccessException error) {
            if (isTimeout(error)) throw timeout();
            throw new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "Node-RED is unavailable");
        } catch (RestClientException error) {
            throw invalidResponse();
        }
    }

    private boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException || cause instanceof HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }

    private IntegrationException timeout() {
        return new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "Node-RED response timed out");
    }

    private IntegrationException invalidResponse() {
        return new IntegrationException(HttpStatus.BAD_GATEWAY, "Node-RED returned an invalid or uncorrelated response");
    }
}
