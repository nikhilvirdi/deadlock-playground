package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ApiValidationTest {
    private final SimulationEngine engine = new SimulationEngine();
    private final ApiServer server = ApiTestSessions.server();
    private HttpClient client;
    private int port;

    @BeforeEach
    void start() throws IOException {
        port = server.start();
        client = ApiTestSessions.client(server, port, engine);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void invalidCountsAndHistoryIndicesAreNeverCoerced() throws Exception {
        configure(false);
        assertEquals(200, post("request", allocation("P", "R", "1")).statusCode());
        String state = get("state").body();
        String history = get("history").body();
        for (String count : List.of("1.9", "4294967297", "2147483648", "0", "-1", "\"1\"", "true", "null")) {
            assertFailure(post("request", allocation("P", "R", count)), 400, "VALIDATION_ERROR");
            assertFailure(post("release", allocation("P", "R", count)), 400, "VALIDATION_ERROR");
        }
        for (String seq : List.of("1.9", "4294967296", "\"0\"", "false")) {
            assertFailure(post("history/jump", "{\"seq\":" + seq + "}"), 400, "VALIDATION_ERROR");
        }
        assertFailure(post("request", "{\"process\":\"P\",\"resource\":\"R\"}"), 400, "VALIDATION_ERROR");
        assertFailure(post("release", "{\"process\":2,\"resource\":\"R\",\"count\":1}"), 400, "VALIDATION_ERROR");
        assertFailure(post("history/jump", "{\"seq\":-1}"), 400, "INVALID_HISTORY_INDEX");
        assertUnchanged(state, history);
        assertEquals(200, post("release", allocation("P", "R", "1")).statusCode());
    }

    @Test
    void rejectsMalformedJsonAndScenarioFieldsWithoutReplacingActiveState() throws Exception {
        configure(false);
        String state = get("state").body();
        String history = get("history").body();
        for (String body : List.of("{name:\"x\"}", "{\"name\":'x'}", "{\"name\":\"x\"} {}",
                "{/*comment*/\"name\":\"x\"}", "{", "[]", "null", "{\"resources\":[],\"processes\":[]}")) {
            assertFailure(post("scenario", body), 400, "VALIDATION_ERROR");
        }
        for (String resource : List.of("{\"id\":\"R\"}", "{\"id\":\"R\",\"total\":1.9}",
                "{\"id\":\"R\",\"total\":4294967297}", "{\"id\":\"R\",\"total\":0}",
                "{\"id\":true,\"total\":1}", "{\"id\":\" \",\"total\":1}", "{\"id\":\"R\",\"total\":1,\"shareable\":\"true\"}")) {
            assertFailure(post("scenario", scenario(resource, "{\"id\":\"P\"}")), 400, "VALIDATION_ERROR");
        }
        String resource = "{\"id\":\"R\",\"total\":2}";
        for (String priority : List.of("1.2", "2147483648", "-2147483649", "\"2\"")) {
            assertFailure(post("scenario", scenario(resource, "{\"id\":\"P\",\"priority\":" + priority + "}")),
                    400, "VALIDATION_ERROR");
        }
        for (String operation : List.of("{\"type\":\"REQUEST\",\"resource\":\"R\",\"count\":1.9}",
                "{\"type\":\"RELEASE\",\"resource\":\"R\",\"count\":4294967297}",
                "{\"type\":\"REQUEST\"}", "{\"type\":true,\"resource\":\"R\"}")) {
            assertFailure(post("scenario", scenario(resource, "{\"id\":\"P\",\"operations\":[" + operation + "]}")),
                    400, "VALIDATION_ERROR");
        }
        assertFailure(post("reset", "{\"confirmed\":\"true\"}"), 400, "VALIDATION_ERROR");
        assertFailure(post("recover", "{\"strategy\":false}"), 400, "INVALID_POLICY");
        assertUnchanged(state, history);
    }

    @Test
    void unknownIdentifiersAndImpossibleRequestsDoNotChangeState() throws Exception {
        configure(false);
        String state = get("state").body();
        String history = get("history").body();
        assertFailure(post("request", allocation("No waiting process to check", "R", "1")), 400, "VALIDATION_ERROR");
        assertFailure(post("request", allocation("P", "No scenario loaded", "1")), 400, "VALIDATION_ERROR");
        assertFailure(post("request", allocation("P", "R", "3")), 400, "VALIDATION_ERROR");
        assertFailure(post("release", allocation("P", "R", "1")), 400, "NOT_HELD");
        assertUnchanged(state, history);
        assertEquals("GRANTED", data(post("request", allocation("P", "R", "2"))).get("outcome").getAsString());
        assertEquals("BLOCKED", data(post("request", allocation("Q", "R", "1"))).get("outcome").getAsString());
    }

    @Test
    void sharedHoldingCapacityFailureLeavesStateAndFutureHistoryIntact() throws Exception {
        configure(true);
        assertEquals(200, post("request", allocation("P", "R", "2")).statusCode());
        assertEquals(200, post("release", allocation("P", "R", "1")).statusCode());
        assertEquals(200, post("history/jump", "{\"seq\":1}").statusCode());
        String state = get("state").body();
        String history = get("history").body();
        assertFailure(post("request", allocation("P", "R", "1")), 400, "VALIDATION_ERROR");
        assertUnchanged(state, history);
        assertEquals(200, post("release", allocation("P", "R", "2")).statusCode());
        assertEquals(200, get("state").statusCode());
    }

    @Test
    void rejectedScriptedReleaseDoesNotTruncateFutureHistory() throws Exception {
        String script = "{\"id\":\"P\",\"operations\":[{\"type\":\"RELEASE\",\"resource\":\"R\"}]}";
        assertEquals(200, post("scenario", scenario("{\"id\":\"R\",\"total\":1}", script)).statusCode());
        assertEquals(200, post("request", allocation("P", "R", "1")).statusCode());
        assertEquals(200, post("step", "{}").statusCode());
        assertEquals(200, post("history/jump", "{\"seq\":0}").statusCode());
        String state = get("state").body();
        String history = get("history").body();
        assertFailure(post("step", "{}"), 400, "NOT_HELD");
        assertUnchanged(state, history);
    }

    @Test
    void scenarioLimitsAcceptTheirBoundaryAndRejectOneMore() throws Exception {
        JsonObject atLimit = json(scenario("{\"id\":\"R\",\"total\":" + ApiInput.MAX_INSTANCES + "}",
                "{\"id\":\"P\",\"priority\":-2147483648}"));
        JsonArray resources = atLimit.getAsJsonArray("resources");
        JsonArray processes = atLimit.getAsJsonArray("processes");
        for (int i = 1; i < ApiInput.MAX_RESOURCES; i++) resources.add(json("{\"id\":\"R" + i + "\",\"total\":1}"));
        for (int i = 1; i < ApiInput.MAX_PROCESSES; i++) processes.add(json("{\"id\":\"P" + i + "\",\"priority\":2147483647}"));
        JsonArray operations = new JsonArray();
        JsonObject operation = json("{\"type\":\"REQUEST\",\"resource\":\"R\",\"count\":1}");
        for (int i = 0; i < ApiInput.MAX_OPERATIONS; i++) operations.add(operation);
        processes.get(0).getAsJsonObject().add("operations", operations);
        assertEquals(200, post("scenario", atLimit.toString()).statusCode());
        String state = get("state").body();
        String history = get("history").body();

        for (String field : List.of("resources", "processes")) {
            JsonObject excessive = atLimit.deepCopy();
            excessive.getAsJsonArray(field).add(json("{\"id\":\"extra\",\"total\":1}"));
            assertFailure(post("scenario", excessive.toString()), 400, "VALIDATION_ERROR");
        }
        operations.add(operation);
        assertFailure(post("scenario", atLimit.toString()), 400, "VALIDATION_ERROR");
        assertFailure(post("scenario", scenario("{\"id\":\"R\",\"total\":" + (ApiInput.MAX_INSTANCES + 1) + "}",
                "{\"id\":\"P\"}")), 400, "VALIDATION_ERROR");
        assertUnchanged(state, history);
        assertEquals(200, post("step", "{}").statusCode());
    }

    @Test
    void bodyLimitCoversFixedAndChunkedRequests() throws Exception {
        String boundary = "{}" + " ".repeat(ApiInput.MAX_BODY_BYTES - 2);
        assertEquals("NO_SCENARIO", json(post("step", boundary).body()).getAsJsonObject("error").get("code").getAsString());
        assertFailure(post("step", boundary + " "), 413, "PAYLOAD_TOO_LARGE");
        byte[] excessive = (boundary + " ").getBytes(StandardCharsets.UTF_8);
        HttpRequest chunked = request("step").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(excessive))).build();
        assertFailure(client.send(chunked, HttpResponse.BodyHandlers.ofString()), 413, "PAYLOAD_TOO_LARGE");
        assertEquals(200, get("health").statusCode());
    }

    @Test
    void incompleteBodyDoesNotBlockHealthAndConnectionExpires() throws Exception {
        try (Socket stalled = new Socket()) {
            stalled.connect(new InetSocketAddress("localhost", port), 2000);
            stalled.setSoTimeout(9000);
            stalled.getOutputStream().write(("POST /api/step HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{").getBytes(StandardCharsets.UTF_8));
            assertEquals(200, get("health").statusCode());
            try {
                assertEquals(-1, stalled.getInputStream().read());
            } catch (SocketException closedByServer) {
                // Closing a connection with an incomplete request can reset the socket.
            }
            assertEquals(200, get("health").statusCode());
        }
    }

    @Test
    void concurrentRequestsReserveCapacityOnlyOnce() throws Exception {
        configure(false);
        var first = client.sendAsync(request("request").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(allocation("P", "R", "2"))).build(),
                HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("request").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(allocation("Q", "R", "2"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of("GRANTED", "BLOCKED"), Set.of(data(first.get()).get("outcome").getAsString(),
                data(second.get()).get("outcome").getAsString()));
        assertEquals(0, data(get("state")).getAsJsonObject("state").getAsJsonArray("resources")
                .get(0).getAsJsonObject().get("available").getAsInt());
    }

    @Test
    void fullHistoryDoesNotEvictSnapshotsAndCanStillBranch() throws Exception {
        configure(true);
        engine.request("P", "R", 1);
        for (int i = 2; i < ApiInput.MAX_HISTORY_EVENTS - 1; i++) {
            if (i % 2 == 0) engine.request("P", "R", 1);
            else engine.release("P", "R", 1);
        }
        assertEquals(200, post("release", allocation("P", "R", "1")).statusCode());
        String state = get("state").body();
        String history = get("history").body();
        assertEquals(ApiInput.MAX_HISTORY_EVENTS, data(get("history")).getAsJsonArray("events").size());
        assertFailure(post("request", allocation("P", "R", "1")), 409, "HISTORY_LIMIT");
        assertUnchanged(state, history);
        assertEquals(200, post("history/jump", "{\"seq\":0}").statusCode());
        assertEquals(history, get("history").body());
        assertEquals(200, post("request", allocation("P", "R", "1")).statusCode());
        assertEquals(2, data(get("history")).getAsJsonArray("events").size());
    }

    @Test
    void largeConfigurationsReachTheirMemoryBudgetBeforeTheHistoryEventCap() throws Exception {
        var scenario = new com.deadlocksim.model.Scenario("Large history");
        for (int i = 0; i < ApiInput.MAX_PROCESSES; i++) {
            scenario.addProcess(new com.deadlocksim.model.Process("P" + i, 1));
        }
        for (int i = 0; i < ApiInput.MAX_RESOURCES; i++) {
            scenario.addResource(new com.deadlocksim.model.Resource("R" + i, 1, false));
        }
        engine.configure(scenario);
        for (int i = 0; i < 221; i++) engine.detect();
        String current = get("state").body();
        String events = get("history").body();
        assertFailure(post("detect", "{}"), 409, "HISTORY_LIMIT");
        assertUnchanged(current, events);
        assertEquals(200, post("history/jump", "{\"seq\":0}").statusCode());
        assertEquals(200, post("detect", "{}").statusCode());
        assertEquals(2, data(get("history")).getAsJsonArray("events").size());
    }

    private void configure(boolean shared) throws Exception {
        String body = scenario("{\"id\":\"R\",\"total\":2,\"shareable\":" + shared + "}",
                "{\"id\":\"P\"},{\"id\":\"Q\"}");
        JsonObject payload = json(body);
        payload.add("prevention", json("{\"mutualExclusion\":" + shared + "}"));
        assertEquals(200, post("scenario", payload.toString()).statusCode());
    }

    private void assertUnchanged(String state, String history) throws Exception {
        HttpResponse<String> after = get("state");
        assertEquals(200, after.statusCode());
        assertEquals(state, after.body());
        assertEquals(history, get("history").body());
    }

    private static void assertFailure(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        JsonObject body = json(response.body());
        assertFalse(body.get("ok").getAsBoolean());
        assertEquals(code, body.getAsJsonObject("error").get("code").getAsString());
    }

    private static String scenario(String resources, String processes) {
        return "{\"name\":\"Validation\",\"resources\":[" + resources + "],\"processes\":[" + processes + "]}";
    }

    private static String allocation(String process, String resource, String count) {
        return "{\"process\":\"" + process + "\",\"resource\":\"" + resource + "\",\"count\":" + count + "}";
    }

    private HttpRequest.Builder request(String route) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + route))
                .timeout(Duration.ofSeconds(2));
    }

    private HttpResponse<String> get(String route) throws Exception {
        return client.send(request(route).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String route, String body) throws Exception {
        return client.send(request(route).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject data(HttpResponse<String> response) {
        return json(response.body()).getAsJsonObject("data");
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
