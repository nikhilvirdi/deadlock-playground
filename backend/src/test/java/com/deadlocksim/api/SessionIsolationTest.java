package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;
import com.deadlocksim.model.Scenario;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionIsolationTest {
    private final AtomicLong clock = new AtomicLong();
    private ApiServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = new ApiServer(InetAddress.getLoopbackAddress(), 0, null, null,
                new SessionStore(2, Duration.ofSeconds(30), clock::get));
        port = server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void independentClientsCanConfigureAndStepConcurrentlyAndResetOnlyTheirOwnHistory() throws Exception {
        var first = client(new CookieManager());
        var second = client(new CookieManager());
        var configureFirst = first.sendAsync(post("scenario", scenario("First", "A")), HttpResponse.BodyHandlers.ofString());
        var configureSecond = second.sendAsync(post("scenario", scenario("Second", "B")), HttpResponse.BodyHandlers.ofString());
        var firstResponse = configureFirst.get(3, TimeUnit.SECONDS);
        var secondResponse = configureSecond.get(3, TimeUnit.SECONDS);
        String firstCookie = cookie(firstResponse);
        String secondCookie = cookie(secondResponse);
        assertNotEquals(firstCookie, secondCookie);
        assertTrue(firstCookie.matches("deadlock-session=[A-Za-z0-9_-]{43}"));
        String attributes = firstResponse.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(attributes.contains("; Path=/; HttpOnly; SameSite=Strict"));
        assertFalse(attributes.contains("; Secure"));
        assertFalse(firstResponse.body().contains(token(firstCookie)));
        assertEquals("no-store", firstResponse.headers().firstValue("Cache-Control").orElseThrow());

        var firstStep = first.sendAsync(post("step", "{}"), HttpResponse.BodyHandlers.ofString());
        var secondStep = second.sendAsync(post("step", "{}"), HttpResponse.BodyHandlers.ofString());
        assertEquals("First", stateName(data(firstStep.get(3, TimeUnit.SECONDS))));
        assertEquals("Second", stateName(data(secondStep.get(3, TimeUnit.SECONDS))));

        JsonObject firstView = data(get(first, "history?view=true"));
        JsonObject secondView = data(get(second, "history?view=true"));
        assertNotEquals(firstView.get("serverId"), secondView.get("serverId"));
        assertEquals(firstView, data(get(first, "history?view=true")));
        assertEquals(secondView, data(get(second, "history?view=true")));

        assertEquals(409, first.send(post("history/jump", """
                {"seq":0,"from":"%s:%s"}
                """.formatted(secondView.get("serverId").getAsString(), secondView.get("revision").getAsString())),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        assertEquals(200, first.send(post("reset", "{\"confirmed\":true}"), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(data(get(first, "history?view=true")).get("state").isJsonNull());
        assertTrue(data(get(first, "history")).getAsJsonArray("events").isEmpty());
        assertEquals(secondView, data(get(second, "history?view=true")));
        assertEquals(409, get(client(new CookieManager()), "state").statusCode());
    }

    @Test
    void idleSessionsFreeCapacityAndTheirCookiesCannotRestoreTheOldState() throws Exception {
        var first = client(new CookieManager());
        var second = client(new CookieManager());
        var third = client(new CookieManager());

        for (int i = 0; i < 3; i++) {
            assertTrue(data(get(third, "history?view=true")).get("state").isJsonNull());
        }
        assertEquals(400, third.send(post("scenario", "{}"), HttpResponse.BodyHandlers.ofString()).statusCode());

        var firstResponse = first.send(post("scenario", scenario("First", "A")), HttpResponse.BodyHandlers.ofString());
        var secondResponse = second.send(post("scenario", scenario("Second", "B")), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, firstResponse.statusCode());
        assertEquals(200, secondResponse.statusCode());
        var overLimit = third.send(post("scenario", scenario("Over limit", "C")), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, overLimit.statusCode());
        assertEquals("SESSION_LIMIT", code(overLimit));
        assertTrue(overLimit.headers().firstValue("Set-Cookie").isEmpty());

        clock.set(Duration.ofSeconds(29).toNanos());
        assertEquals("Second", stateName(data(get(second, "history?view=true"))));
        clock.set(Duration.ofSeconds(31).toNanos());
        server.sessions.expireIdle();
        assertNull(server.sessions.acquire(token(cookie(firstResponse))));
        assertEquals(409, get(first, "state").statusCode());
        assertTrue(data(get(first, "history?view=true")).get("state").isJsonNull());
        assertEquals("Second", stateName(data(get(second, "history?view=true"))));
        assertEquals(200, third.send(post("scenario", scenario("New", "C")), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("New", stateName(data(get(third, "history?view=true"))));
    }

    @Test
    void inFlightSessionExpiryAndSessionLocksAreIndependent() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        SimulationEngine engine = new SimulationEngine() {
            @Override
            public void configure(Scenario scenario) {
                started.countDown();
                try {
                    if (!resume.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Test request timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                super.configure(scenario);
            }
        };
        var second = client(new CookieManager());
        var response = second.send(post("scenario", scenario("Second", "B")), HttpResponse.BodyHandlers.ofString());
        var held = server.sessions.acquire(token(cookie(response)));
        try {
            clock.set(Duration.ofSeconds(31).toNanos());
            server.sessions.expireIdle();
            assertEquals("Second", stateName(data(get(second, "history?view=true"))));
        } finally {
            server.sessions.release(held);
        }

        var first = ApiTestSessions.client(server, port, engine);
        var pending = first.sendAsync(post("scenario", scenario("First", "A")), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var other = second.sendAsync(post("step", "{}"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, other.get(3, TimeUnit.SECONDS).statusCode());
            assertFalse(pending.isDone());
        } finally {
            resume.countDown();
        }
        assertEquals(200, pending.get(3, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void wrongOriginsFormBodiesAndUnknownCookiesCannotMutateAnActiveSession() throws Exception {
        var client = client(new CookieManager());
        assertEquals(200, client.send(post("scenario", scenario("Active", "A")), HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonObject before = data(get(client, "history?view=true"));
        URI step = uri("step");
        for (String type : new String[] { null, "text/plain", "application/x-www-form-urlencoded", "application/jsonp" }) {
            var request = HttpRequest.newBuilder(step).POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (type != null) request.header("Content-Type", type);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(415, response.statusCode());
            assertEquals("UNSUPPORTED_MEDIA_TYPE", code(response));
        }
        for (String origin : new String[] { "null", "https://other.example", "http://localhost:" + (port == 65535 ? 1 : port + 1) }) {
            var request = HttpRequest.newBuilder(step).header("Origin", origin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        var crossSite = HttpRequest.newBuilder(step).header("Sec-Fetch-Site", "same-site").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, client.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(raw("evil.example", "http://evil.example", "{}").startsWith("HTTP/1.1 403"));

        var forged = HttpRequest.newBuilder(uri("state")).header("Cookie", "deadlock-session=" + "a".repeat(43)).GET().build();
        assertEquals(409, HttpClient.newHttpClient().send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(before, data(get(client, "history?view=true")));
        var allowed = HttpRequest.newBuilder(step).header("Origin", "http://localhost:" + port)
                .header("Sec-Fetch-Site", "same-origin").header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(200, client.send(allowed, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void publicConfiguredOriginUsesSecureHostOnlyCookieAndIgnoresForwardedScheme() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiServer(InetAddress.getByName("0.0.0.0"), 0, null, null));
        server.stop();
        server = new ApiServer(InetAddress.getLoopbackAddress(), 0, null, "https://simulator.example");
        port = server.start();
        String wrong = raw("simulator.example", "http://simulator.example", scenario("Wrong", "A"));
        assertTrue(wrong.startsWith("HTTP/1.1 403"));
        String response = raw("simulator.example", "https://simulator.example", scenario("Secure", "A"));
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.lines().anyMatch(line -> line.matches(
                "(?i)set-cookie: __Host-deadlock-session=[A-Za-z0-9_-]{43}; Path=/; HttpOnly; SameSite=Strict; Secure")));
        assertFalse(response.contains("; Domain="));
    }

    private String raw(String host, String origin, String body) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            String request = "POST /api/scenario HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + origin
                    + "\r\nX-Forwarded-Proto: http\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private URI uri(String route) {
        return URI.create("http://localhost:" + port + "/api/" + route);
    }

    private HttpRequest post(String route, String body) {
        return HttpRequest.newBuilder(uri(route)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> get(HttpClient client, String route) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(route)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client(CookieManager jar) {
        return HttpClient.newBuilder().cookieHandler(jar).build();
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static String token(String cookie) {
        return cookie.split("=", 2)[1];
    }

    private static String stateName(JsonObject data) {
        return data.getAsJsonObject("state").getAsJsonObject("scenario").get("name").getAsString();
    }

    private static JsonObject data(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
    }

    private static String code(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private static String scenario(String name, String resource) {
        return """
                {"name":"%s","resources":[{"id":"%s","total":1}],"processes":[{"id":"P",
                "operations":[{"type":"REQUEST","resource":"%s"},{"type":"RELEASE","resource":"%s"}]}]}
                """.formatted(name, resource, resource, resource);
    }
}
