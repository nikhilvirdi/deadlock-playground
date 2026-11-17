package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestReleaseStepTest {
    private SimulationEngine engine;
    private ApiServer server;
    private int boundPort;
    private HttpClient client;
    private Gson gson;

    @BeforeEach
    void setUp() throws IOException {
        engine = new SimulationEngine();
        server = ApiTestSessions.server();
        boundPort = server.start();
        client = ApiTestSessions.client(server, boundPort, engine);
        gson = new Gson();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void requestGranted() throws IOException, InterruptedException {
        String setupJson = """
                {
                  "name": "Grant Test",
                  "resources": [
                    { "id": "R1", "total": 2, "shareable": false }
                  ],
                  "processes": [
                    {
                      "id": "P1",
                      "priority": 1,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    }
                  ]
                }
                """;
        postJson("/api/scenario", setupJson);

        String requestBody = """
                { "process": "P1", "resource": "R1", "count": 1 }
                """;
        HttpResponse<String> response = postJson("/api/request", requestBody);

        assertEquals(200, response.statusCode());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(root.get("ok").getAsBoolean());
        JsonObject data = root.getAsJsonObject("data");
        assertEquals("GRANTED", data.get("outcome").getAsString());
        assertTrue(data.get("safety").isJsonNull());
        assertNotNull(data.get("reason").getAsString());
        assertNotNull(data.getAsJsonObject("state"));

        JsonObject state = data.getAsJsonObject("state");
        JsonObject r1 = state.getAsJsonArray("resources").get(0).getAsJsonObject();
        assertEquals(1, r1.get("available").getAsInt());
        JsonObject p1 = state.getAsJsonArray("processes").get(0).getAsJsonObject();
        JsonArray held = p1.getAsJsonArray("held");
        assertEquals(1, held.size());
        assertEquals("R1", held.get(0).getAsJsonObject().get("resource").getAsString());
        assertEquals(1, held.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void requestBlocked() throws IOException, InterruptedException {
        String setupJson = """
                {
                  "name": "Block Test",
                  "resources": [
                    { "id": "R1", "total": 1, "shareable": false }
                  ],
                  "processes": [
                    {
                      "id": "P1",
                      "priority": 1,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    },
                    {
                      "id": "P2",
                      "priority": 2,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    }
                  ]
                }
                """;
        postJson("/api/scenario", setupJson);
        postJson("/api/request", "{\"process\": \"P1\", \"resource\": \"R1\", \"count\": 1}");

        HttpResponse<String> response = postJson("/api/request", "{\"process\": \"P2\", \"resource\": \"R1\", \"count\": 1}");

        assertEquals(200, response.statusCode());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(root.get("ok").getAsBoolean());
        JsonObject data = root.getAsJsonObject("data");
        assertEquals("BLOCKED", data.get("outcome").getAsString());
        assertTrue(data.get("safety").isJsonNull());

        JsonObject p2 = data.getAsJsonObject("state").getAsJsonArray("processes").get(1).getAsJsonObject();
        assertEquals("WAITING", p2.get("state").getAsString());
        assertEquals("R1", p2.getAsJsonObject("waitingFor").get("resource").getAsString());
        assertEquals(1, p2.getAsJsonObject("waitingFor").get("count").getAsInt());
    }

    @Test
    void bankersModeUsesDeclaredClaimsAndDemoModeAllowsTheSameUnsafeGrant() throws IOException, InterruptedException {
        String scenario = """
                {
                  "name": "Future demand",
                  "bankersAvoidance": %s,
                  "resources": [{"id":"A","total":1},{"id":"B","total":1}],
                  "processes": [
                    {"id":"P1","operations":[{"type":"REQUEST","resource":"A"},{"type":"REQUEST","resource":"B"}]},
                    {"id":"P2","operations":[{"type":"REQUEST","resource":"B"},{"type":"REQUEST","resource":"A"}]},
                    {"id":"P3"}
                  ]
                }
                """;
        assertEquals(200, postJson("/api/scenario", scenario.formatted(true)).statusCode());
        assertEquals(200, postJson("/api/step", "{}").statusCode());

        HttpResponse<String> denied = postJson("/api/request", """
                {"process":"P2","resource":"B","count":1}
                """);
        assertEquals(200, denied.statusCode());
        JsonObject data = gson.fromJson(denied.body(), JsonObject.class).getAsJsonObject("data");
        assertEquals("DENIED_UNSAFE", data.get("outcome").getAsString());
        assertFalse(data.getAsJsonObject("safety").get("safe").getAsBoolean());
        assertTrue(data.getAsJsonObject("safety").get("sequence").isJsonNull());
        assertTrue(data.get("reason").getAsString().contains("Unsafe state"));
        JsonObject state = data.getAsJsonObject("state");
        assertTrue(state.getAsJsonObject("scenario").get("bankersAvoidance").getAsBoolean());
        assertEquals(1, state.getAsJsonArray("resources").get(1).getAsJsonObject().get("available").getAsInt());
        assertEquals(0, state.getAsJsonArray("processes").get(1).getAsJsonObject().getAsJsonArray("held").size());

        HttpResponse<String> unknownClaim = postJson("/api/request", """
                {"process":"P3","resource":"B","count":1}
                """);
        assertEquals(400, unknownClaim.statusCode());
        assertTrue(unknownClaim.body().contains("declared claim"));

        assertEquals(200, postJson("/api/step", "{}").statusCode());
        assertEquals(0, engine.getScenario().getProcesses().get(1).getNextOperationIndex());
        assertEquals(200, postJson("/api/release", """
                {"process":"P2","resource":"B","count":1}
                """).statusCode());
        HttpResponse<String> safe = postJson("/api/request", """
                {"process":"P2","resource":"B","count":1}
                """);
        JsonObject safeData = gson.fromJson(safe.body(), JsonObject.class).getAsJsonObject("data");
        assertEquals("GRANTED", safeData.get("outcome").getAsString());
        assertEquals("P2", safeData.getAsJsonObject("safety").getAsJsonArray("sequence").get(0).getAsString());

        assertEquals(200, postJson("/api/scenario", scenario.formatted(false)).statusCode());
        assertEquals(200, postJson("/api/step", "{}").statusCode());
        HttpResponse<String> demo = postJson("/api/request", """
                {"process":"P2","resource":"B","count":1}
                """);
        JsonObject demoData = gson.fromJson(demo.body(), JsonObject.class).getAsJsonObject("data");
        assertEquals("GRANTED", demoData.get("outcome").getAsString());
        assertTrue(demoData.get("safety").isJsonNull());
        assertEquals(200, postJson("/api/step", "{}").statusCode());
        assertEquals(200, postJson("/api/request", """
                {"process":"P2","resource":"A","count":1}
                """).statusCode());
        assertTrue(engine.detect().result().isDeadlocked());
    }

    @Test
    void requestRejectedPrevention() throws IOException, InterruptedException {
        String setupJson = """
                {
                  "name": "Circular Wait Prevention Test",
                  "prevention": {
                    "mutualExclusion": false,
                    "holdAndWait": false,
                    "noPreemption": false,
                    "circularWait": true
                  },
                  "resources": [
                    { "id": "R1", "total": 1, "shareable": false },
                    { "id": "R2", "total": 1, "shareable": false }
                  ],
                  "processes": [
                    {
                      "id": "P1",
                      "priority": 1,
                      "operations": [
                        { "type": "REQUEST", "resource": "R2", "count": 1 },
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    }
                  ]
                }
                """;
        postJson("/api/scenario", setupJson);
        postJson("/api/request", "{\"process\": \"P1\", \"resource\": \"R2\", \"count\": 1}");

        HttpResponse<String> response = postJson("/api/request", "{\"process\": \"P1\", \"resource\": \"R1\", \"count\": 1}");

        assertEquals(200, response.statusCode());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(root.get("ok").getAsBoolean());
        JsonObject data = root.getAsJsonObject("data");
        assertEquals("REJECTED_PREVENTION", data.get("outcome").getAsString());
        assertTrue(data.get("safety").isJsonNull());
        assertTrue(data.get("reason").getAsString().contains("Circular wait prevention"));
    }

    @Test
    void releaseWakesWaitingProcess() throws IOException, InterruptedException {
        String setupJson = """
                {
                  "name": "Release Wake Test",
                  "resources": [
                    { "id": "R1", "total": 1, "shareable": false }
                  ],
                  "processes": [
                    {
                      "id": "P1",
                      "priority": 1,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 },
                        { "type": "RELEASE", "resource": "R1", "count": 1 }
                      ]
                    },
                    {
                      "id": "P2",
                      "priority": 2,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    }
                  ]
                }
                """;
        postJson("/api/scenario", setupJson);
        postJson("/api/request", "{\"process\": \"P1\", \"resource\": \"R1\", \"count\": 1}");
        postJson("/api/request", "{\"process\": \"P2\", \"resource\": \"R1\", \"count\": 1}");

        HttpResponse<String> response = postJson("/api/release", "{\"process\": \"P1\", \"resource\": \"R1\", \"count\": 1}");

        assertEquals(200, response.statusCode());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(root.get("ok").getAsBoolean());
        JsonObject data = root.getAsJsonObject("data");
        JsonArray unblocked = data.getAsJsonArray("unblocked");
        assertEquals(1, unblocked.size());
        assertEquals("P2", unblocked.get(0).getAsString());

        JsonObject p2 = data.getAsJsonObject("state").getAsJsonArray("processes").get(1).getAsJsonObject();
        assertEquals("RUNNING", p2.get("state").getAsString());
        JsonArray p2Held = p2.getAsJsonArray("held");
        assertEquals(1, p2Held.size());
        assertEquals("R1", p2Held.get(0).getAsJsonObject().get("resource").getAsString());
        assertEquals(1, p2Held.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void stepReportsFinished() throws IOException, InterruptedException {
        String setupJson = """
                {
                  "name": "Step Finish Test",
                  "resources": [
                    { "id": "R1", "total": 1, "shareable": false }
                  ],
                  "processes": [
                    {
                      "id": "P1",
                      "priority": 1,
                      "operations": [
                        { "type": "REQUEST", "resource": "R1", "count": 1 }
                      ]
                    }
                  ]
                }
                """;
        postJson("/api/scenario", setupJson);

        HttpResponse<String> response = postJson("/api/step", "{}");

        assertEquals(200, response.statusCode());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(root.get("ok").getAsBoolean());
        JsonObject data = root.getAsJsonObject("data");
        assertTrue(data.get("finished").getAsBoolean());
        JsonObject executed = data.getAsJsonObject("executed");
        assertNotNull(executed.get("description"));
        assertTrue(executed.get("description").getAsString().contains("P1"));

        JsonObject p1 = data.getAsJsonObject("state").getAsJsonArray("processes").get(0).getAsJsonObject();
        assertEquals("COMPLETED", p1.get("state").getAsString());
    }

    private HttpResponse<String> postJson(String endpoint, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + boundPort + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
