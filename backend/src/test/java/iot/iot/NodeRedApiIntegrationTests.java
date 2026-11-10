package iot.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import iot.iot.dto.*;
import iot.iot.dto.request.ControlRequest;
import iot.iot.service.ControlService;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named="SMARTCLASS_MYSQL_TEST_URL",matches=".+")
class NodeRedApiIntegrationTests {
    static final NodeRedStub STUB=new NodeRedStub();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",()->MySqlTestSupport.url("_nodered"));
        props.add("app.jwt.secret",MySqlTestSupport::jwtSecret);
        props.add("app.nodered.base-url",STUB::baseUrl);
        props.add("app.nodered.ack-timeout",()->1500);
        props.add("app.nodered.ack-poll-interval",()->50);
        props.add("app.nodered.connect-timeout",()->500);
        props.add("app.nodered.read-timeout",()->1000);
        props.add("debug",()->false);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    iot.iot.integration.nodered.NodeRedClient nodeRed;

    @AfterAll static void cleanup() { STUB.close(); }
    private long logCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM command_logs",Long.class); }
    private void sendControl(String token,String body,int expected) throws Exception {
        mvc.perform(post("/api/control").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body)).andExpect(status().is(expected));
    }

    private static final String LIVE = "{\"kind\":\"room-dashboard\",\"serverAt\":1762650000000,\"links\":{\"mqtt\":true,\"sensor\":true,\"relay\":true},"
        + "\"sensor\":{\"temperature\":29.5,\"humidity\":71.0,\"dht_ok\":true,\"motion\":1,\"pir_ready\":true,\"light_percent\":22,\"rssi\":-60},"
        + "\"relay\":{\"light_on\":true,\"fan_on\":false,\"light_mode\":\"AUTO\",\"fan_mode\":\"MANUAL_OFF\",\"uart_ok\":true,\"light_adc_high_is_bright\":true},"
        + "\"sensorAgeMs\":500,\"relayAgeMs\":800,\"pending\":null,\"notice\":{}}";
    private static String stateWithNotice(String notice) { return LIVE.replace("\"notice\":{}", "\"notice\":" + notice); }
    private static NodeRedStub.Reply reply(int status, String body) { return new NodeRedStub.Reply(status, body, 0, false); }
    /** Behaves like the flow: baseline state, 202 on POST, then pending, then the final notice. */
    private static java.util.function.Function<NodeRedStub.Call, NodeRedStub.Reply> flow(String finalNotice) {
        AtomicInteger gets = new AtomicInteger();
        return call -> {
            if (call.method().equals("POST")) return reply(202, "{\"ok\":true,\"message\":\"sent\"}");
            int n = gets.incrementAndGet();
            return reply(200, stateWithNotice(n == 1 ? "{}" : n == 2 ? "{\"status\":\"pending\",\"at\":10}" : finalNotice));
        };
    }
    private String login() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"teacher\",\"password\":\"Teacher@12345\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(login).path("data").path("accessToken").asText();
    }

    @Test
    void jwtApiHttpStubAndRealMySqlShareTheContractWithoutPhysicalCommands() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/control").contentType("application/json").content("{\"target\":\"FAN\",\"action\":\"ON\"}"))
            .andExpect(status().isUnauthorized());
        assertTrue(STUB.calls.isEmpty());
        String token = login();

        // State: flow JSON is converted to the device-record contract.
        STUB.handler = call -> reply(200, LIVE);
        var data = mapper.readTree(mvc.perform(get("/api/state").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals("node1_sensor", data.path("devices").get(0).path("device_id").asText());
        assertEquals(29.5, data.path("devices").get(0).path("temp").asDouble());
        assertEquals("node2_actuator", data.path("devices").get(1).path("device_id").asText());
        assertTrue(data.path("devices").get(1).path("LIGHT").asBoolean());
        assertEquals("/smarthome/api/state", STUB.calls.get(0).path());
        STUB.handler = call -> reply(200, "{\"temp\":1}");
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + token)).andExpect(status().isBadGateway());
        STUB.calls.clear();

        for (String invalid : List.of("{}", "{\"target\":\"INVALID\",\"action\":\"ON\"}", "{\"target\":\"FAN\",\"action\":\"INVALID\"}",
            "{\"target\":\"FAN\",\"action\":\"ON\",\"trigger_by\":\"admin\"}", "{\"target\":\"FAN\",\"action\":\"ON\",\"role\":\"ADMIN\"}")) sendControl(token, invalid, 400);
        assertTrue(STUB.calls.isEmpty());

        jdbc.update("INSERT INTO command_logs(device_id,target,action,trigger_by) VALUES ('node2_actuator','LIGHT','OFF','RULE_ENGINE')");
        var ruleBefore = jdbc.queryForList("SELECT * FROM command_logs WHERE trigger_by='RULE_ENGINE'");
        long before = logCount();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "HTTP must run outside database transactions");
            return invocation.callRealMethod();
        }).when(nodeRed).command(org.mockito.ArgumentMatchers.any());

        // Confirmed ON: POST {"command":"FAN_ON"} then the state notice confirms it.
        STUB.handler = flow("{\"status\":\"success\",\"at\":20,\"command\":\"FAN_ON\",\"message\":\"ok\"}");
        mvc.perform(post("/api/control").header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"target\":\"FAN\",\"action\":\"ON\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.trigger_by").value("teacher")).andExpect(jsonPath("$.data.log_status").value("SAVED"));
        assertEquals(before + 1, logCount());
        var post = STUB.calls.stream().filter(c -> c.method().equals("POST")).findFirst().orElseThrow();
        assertEquals("/smarthome/api/command", post.path());
        assertEquals(mapper.readTree("{\"command\":\"FAN_ON\"}"), mapper.readTree(post.body()));
        assertNull(post.authorization());
        assertEquals("teacher", jdbc.queryForObject("SELECT trigger_by FROM command_logs ORDER BY id DESC LIMIT 1", String.class));

        // AUTO is a valid action and is audited as AUTO.
        STUB.handler = flow("{\"status\":\"success\",\"at\":30,\"command\":\"LIGHT_AUTO\",\"message\":\"ok\"}");
        sendControl(token, "{\"target\":\"LIGHT\",\"action\":\"AUTO\"}", 200);
        assertEquals("AUTO", jdbc.queryForObject("SELECT action FROM command_logs ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(before + 2, logCount());

        // Refused (409), unconfirmed (504), invalid POST body (502): never recorded, never resent.
        STUB.handler = call -> call.method().equals("POST") ? reply(409, "{\"ok\":false,\"message\":\"busy\"}") : reply(200, LIVE);
        sendControl(token, "{\"target\":\"FAN\",\"action\":\"ON\"}", 409);
        STUB.handler = flow("{\"status\":\"error\",\"at\":40,\"message\":\"timeout\"}");
        sendControl(token, "{\"target\":\"FAN\",\"action\":\"OFF\"}", 504);
        STUB.handler = call -> call.method().equals("POST") ? reply(202, "not-json") : reply(200, LIVE);
        sendControl(token, "{\"target\":\"FAN\",\"action\":\"ON\"}", 502);
        assertEquals(before + 2, logCount());
        assertEquals(ruleBefore, jdbc.queryForList("SELECT * FROM command_logs WHERE trigger_by='RULE_ENGINE'"));

        STUB.close();
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        sendControl(token, "{\"target\":\"FAN\",\"action\":\"ON\"}", 503);
        assertEquals(before + 2, logCount());
    }
}
