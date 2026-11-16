package com.deadlocksim.api;

import com.deadlocksim.engine.DetectReport;
import com.deadlocksim.engine.RecoveryReport;
import com.deadlocksim.engine.RecoveryRound;
import com.deadlocksim.engine.ReleaseResult;
import com.deadlocksim.engine.RequestResult;
import com.deadlocksim.engine.SimulationEngine;
import com.deadlocksim.engine.StepResult;
import com.deadlocksim.model.Scenario;
import com.deadlocksim.model.history.HistoryEvent;
import com.deadlocksim.model.victim.PriorityPolicy;
import com.deadlocksim.model.victim.RecoveryCostPolicy;
import com.deadlocksim.model.victim.ResourcesHeldPolicy;
import com.deadlocksim.model.victim.VictimPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.deadlocksim.api.ApiInput.*;

public class ApiServer {
    private final SimulationEngine engine;
    private final int port;
    private final Path frontendPath;
    private HttpServer server;
    private ThreadPoolExecutor executor;

    public ApiServer(SimulationEngine engine) {
        this(engine, 0, Path.of("../frontend"));
    }

    public ApiServer(SimulationEngine engine, int port) {
        this(engine, port, Path.of("../frontend"));
    }

    public ApiServer(SimulationEngine engine, int port, Path frontendPath) {
        this.engine = engine;
        this.port = port;
        this.frontendPath = frontendPath != null ? frontendPath : Path.of("../frontend");
    }

    public ApiServer(SimulationEngine engine, int port, String frontendFolder) {
        this(engine, port, frontendFolder != null ? Path.of(frontendFolder) : Path.of("../frontend"));
    }

    public SimulationEngine getEngine() {
        return engine;
    }

    public Path getFrontendPath() {
        return frontendPath;
    }

    public int start() throws IOException {
        // The built-in server reads these JVM-wide properties when it initializes.
        defaultProperty("sun.net.httpserver.maxReqTime", "5");
        defaultProperty("sun.net.httpserver.maxRspTime", "10");
        defaultProperty("sun.net.httpserver.timerMillis", "250");
        defaultProperty("jdk.httpserver.maxConnections", "32");
        server = HttpServer.create(new InetSocketAddress(port), 32);
        executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.start();
        return server.getAddress().getPort();
    }

    private static void defaultProperty(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            executor.shutdownNow();
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (!path.startsWith("/api/")) {
                    if (requireMethod(exchange, "GET")) serveStatic(exchange, path);
                    return;
                }

                String expected = switch (path) {
                    case "/api/health", "/api/state", "/api/history" -> "GET";
                    case "/api/scenario", "/api/reset", "/api/history/jump", "/api/request",
                         "/api/release", "/api/step", "/api/detect", "/api/recover" -> "POST";
                    default -> null;
                };
                if (expected == null) {
                    ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "Endpoint not found");
                    return;
                }
                if (!requireMethod(exchange, expected)) return;
                if (path.equals("/api/health")) {
                    sendJson(exchange, 200, "{\"ok\":true,\"data\":{\"status\":\"UP\"}}");
                    return;
                }

                JsonObject body = expected.equals("POST") ? parseJsonObject(exchange, switch (path) {
                    case "/api/reset", "/api/step", "/api/detect" -> true;
                    default -> false;
                }) : null;
                String response;
                synchronized (engine) {
                    // Mutation and response serialization must use the same engine state.
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", true);
                    result.add("data", handleApi(path, body));
                    response = result.toString();
                }
                sendJson(exchange, 200, response);
            } catch (RequestFailure error) {
                if (error.code == ErrorCode.PAYLOAD_TOO_LARGE) exchange.getResponseHeaders().set("Connection", "close");
                ErrorMapper.sendError(exchange, error.code, error.getMessage());
            } catch (RuntimeException error) {
                if (exchange.getResponseCode() == -1) ErrorMapper.sendError(exchange, error);
            }
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", method);
        ErrorMapper.sendError(exchange, ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed: " + exchange.getRequestMethod());
        return false;
    }

    private JsonObject handleApi(String path, JsonObject body) {
        JsonObject data = new JsonObject();
        switch (path) {
            case "/api/scenario" -> {
                Scenario scenario = JsonMapper.parseScenario(body);
                engine.configure(scenario);
            }
            case "/api/reset" -> {
                engine.reset(flag(body, "confirmed"));
                data.add("state", JsonNull.INSTANCE);
                return data;
            }
            case "/api/history" -> {
                JsonArray events = new JsonArray();
                for (HistoryEvent event : engine.getHistoryEvents()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("seq", event.getSequence());
                    value.addProperty("type", event.getType().name());
                    value.addProperty("description", event.getDescription());
                    events.add(value);
                }
                data.add("events", events);
                return data;
            }
            case "/api/history/jump" -> {
                int seq = integer(body, "seq", Integer.MIN_VALUE, Integer.MAX_VALUE);
                try {
                    engine.jumpTo(seq);
                } catch (IllegalArgumentException error) {
                    throw new RequestFailure(ErrorCode.INVALID_HISTORY_INDEX, error.getMessage());
                }
            }
            case "/api/request" -> {
                String process = text(body, "process");
                String resource = text(body, "resource");
                int count = integer(body, "count", 1, Integer.MAX_VALUE);
                Scenario scenario = engine.getScenario();
                int eventBudget = scenario != null && scenario.isBankersAvoidance() ? 2 : 1;
                if (scenario != null && scenario.getPreventionToggles().isNoPreemption() && waitingCount() > 0) {
                    eventBudget = 2 + 2 * waitingCount();
                }
                requireHistoryRoom(eventBudget);
                RequestResult result = engine.request(process, resource, count);
                data.addProperty("outcome", result.outcome().name());
                data.addProperty("reason", result.reason());
                if (result.safety() == null) {
                    data.add("safety", JsonNull.INSTANCE);
                } else {
                    JsonObject safety = new JsonObject();
                    safety.addProperty("safe", result.safety().isSafe());
                    JsonArray sequence = new JsonArray();
                    result.safety().getSequence().forEach(sequence::add);
                    safety.add("sequence", result.safety().isSafe() ? sequence : JsonNull.INSTANCE);
                    safety.addProperty("blockingReason", result.safety().isSafe() ? null : result.reason());
                    data.add("safety", safety);
                }
            }
            case "/api/release" -> {
                String process = text(body, "process");
                String resource = text(body, "resource");
                int count = integer(body, "count", 1, Integer.MAX_VALUE);
                requireHistoryRoom(1 + 2 * waitingCount());
                ReleaseResult result = engine.release(process, resource, count);
                JsonArray unblocked = new JsonArray();
                result.unblockedProcessIds().forEach(unblocked::add);
                data.add("unblocked", unblocked);
            }
            case "/api/step" -> {
                requireHistoryRoom(3 + 2 * waitingCount());
                StepResult result = engine.step();
                JsonObject executed = new JsonObject();
                executed.addProperty("description", result.description());
                data.add("executed", executed);
                data.addProperty("finished", result.finished());
            }
            case "/api/detect" -> {
                requireHistoryRoom(1);
                DetectReport report = engine.detect();
                data.addProperty("strategy", report.strategy());
                data.addProperty("deadlocked", report.result().isDeadlocked());
                JsonArray processes = new JsonArray();
                report.result().getProcessIds().forEach(processes::add);
                data.add("processes", processes);
                JsonArray cycle = new JsonArray();
                report.result().getCycle().forEach(cycle::add);
                data.add("cycle", cycle.isEmpty() ? JsonNull.INSTANCE : cycle);
            }
            case "/api/recover" -> {
                String policyName = body.has("policy") && !body.get("policy").isJsonNull()
                        ? recoveryOption(body, "policy") : "RECOVERY_COST";
                VictimPolicy policy = switch (policyName.toUpperCase(Locale.ROOT)) {
                    case "PRIORITY" -> new PriorityPolicy();
                    case "RESOURCES_HELD" -> new ResourcesHeldPolicy();
                    case "RECOVERY_COST" -> new RecoveryCostPolicy();
                    default -> throw new RequestFailure(ErrorCode.INVALID_POLICY, "Unknown policy: " + policyName);
                };
                String strategy = recoveryOption(body, "strategy");
                boolean preempt = switch (strategy.toUpperCase(Locale.ROOT)) {
                    case "PREEMPT" -> true;
                    case "TERMINATE" -> false;
                    default -> throw new RequestFailure(ErrorCode.INVALID_POLICY, "Unknown strategy: " + strategy);
                };
                requireHistoryRoom(2 + 4 * processCount() + 2 * waitingCount());
                RecoveryReport report = engine.recover(policy, preempt);
                JsonArray rounds = new JsonArray();
                for (RecoveryRound round : report.rounds()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("victim", round.victimId());
                    value.addProperty("strategy", round.strategy());
                    value.addProperty("resolvedAfter", round.resolvedAfter());
                    rounds.add(value);
                }
                data.add("rounds", rounds);
                data.addProperty("resolved", report.resolved());
            }
            case "/api/state" -> {}
            default -> throw new IllegalStateException("Unrecognized API route");
        }
        data.add("state", JsonMapper.mapState(engine));
        return data;
    }

    private static String recoveryOption(JsonObject body, String field) {
        try {
            return text(body, field);
        } catch (IllegalArgumentException error) {
            throw new RequestFailure(ErrorCode.INVALID_POLICY, error.getMessage());
        }
    }

    private int processCount() {
        Scenario scenario = engine.getScenario();
        return scenario == null ? 0 : scenario.getProcesses().size();
    }

    private int waitingCount() {
        Scenario scenario = engine.getScenario();
        return scenario == null ? 0 : (int) scenario.getProcesses().stream().filter(process -> process.isWaiting()).count();
    }

    private void requireHistoryRoom(int eventBudget) {
        // Budget the whole action before mutation so existing snapshots never need eviction.
        if (engine.getViewedSeq() + 1 > MAX_HISTORY_EVENTS - eventBudget) {
            throw new RequestFailure(ErrorCode.HISTORY_LIMIT,
                    "Event history is full for this action. Step back or reset to continue.");
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        try {
            Path root = frontendPath.toRealPath();
            String relativePath = path.isEmpty() || "/".equals(path) ? "index.html" : path.substring(1);
            Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root)) {
                ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
                return;
            }
            Path target = candidate.toRealPath();
            if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
                return;
            }

            String filename = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            String contentType;
            if (filename.endsWith(".html")) {
                contentType = "text/html";
            } else if (filename.endsWith(".css")) {
                contentType = "text/css";
            } else if (filename.endsWith(".js")) {
                contentType = "application/javascript";
            } else if (filename.endsWith(".svg")) {
                contentType = "image/svg+xml";
            } else if (filename.endsWith(".png")) {
                contentType = "image/png";
            } else {
                ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
                return;
            }

            try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, Files.size(target));
                try (OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            }
        } catch (IOException | InvalidPathException | SecurityException error) {
            if (exchange.getResponseCode() == -1) ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
        }
    }

    private static JsonObject parseJsonObject(HttpExchange exchange, boolean allowEmpty) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null && Long.parseLong(contentLength) > MAX_BODY_BYTES) {
            throw new RequestFailure(ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new RequestFailure(ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
        }

        try {
            String body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (allowEmpty && body.matches("[ \\t\\r\\n]*")) return new JsonObject();
            JsonReader reader = new JsonReader(new StringReader(body));
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Malformed JSON body");
            return object(value, "body");
        } catch (CharacterCodingException | JsonParseException | MalformedJsonException | EOFException error) {
            throw new IllegalArgumentException("Malformed JSON body");
        }
    }

    private static final class RequestFailure extends RuntimeException {
        private final ErrorCode code;

        private RequestFailure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    void sendError(HttpExchange exchange, int statusCode, String code, String message) throws IOException {
        ErrorCode errorCode;
        try {
            errorCode = ErrorCode.valueOf(code);
        } catch (Exception e) {
            errorCode = ErrorCode.INTERNAL_ERROR;
        }
        ErrorMapper.sendError(exchange, errorCode, message);
    }
}
