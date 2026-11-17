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
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.deadlocksim.api.ApiInput.*;

public class ApiServer {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final int MAX_HISTORY_WEIGHT = 524_288;
    private final String emptyViewId = UUID.randomUUID().toString();
    final SessionStore sessions;
    private final InetAddress bindAddress;
    private final int port;
    private final Path frontendPath;
    private final URI publicOrigin;
    private final String cookieName;
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService expiry;

    public ApiServer(InetAddress bindAddress, int port, Path frontendPath, String publicOrigin) {
        this(bindAddress, port, frontendPath, publicOrigin, new SessionStore());
    }

    ApiServer(InetAddress bindAddress, int port, Path frontendPath, String origin, SessionStore sessions) {
        this.sessions = sessions;
        this.bindAddress = bindAddress;
        this.port = port;
        this.frontendPath = frontendPath;
        this.publicOrigin = origin == null ? null : URI.create(origin);
        if (publicOrigin != null && (!"https".equals(publicOrigin.getScheme()) || publicOrigin.getHost() == null
                || publicOrigin.getUserInfo() != null || !publicOrigin.getPath().isEmpty()
                || publicOrigin.getRawQuery() != null || publicOrigin.getRawFragment() != null
                || publicOrigin.getPort() == 0 || publicOrigin.getPort() > 65535)) {
            throw new IllegalArgumentException("PUBLIC_ORIGIN must be an HTTPS origin without a path");
        }
        if (publicOrigin == null && !bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("Non-loopback binding requires an HTTPS PUBLIC_ORIGIN");
        }
        cookieName = publicOrigin == null ? "deadlock-session" : "__Host-deadlock-session";
    }

    public int start() throws IOException {
        // The built-in server reads these JVM-wide properties when it initializes.
        defaultProperty("sun.net.httpserver.maxReqTime", "5");
        defaultProperty("sun.net.httpserver.maxRspTime", "10");
        defaultProperty("sun.net.httpserver.timerMillis", "250");
        defaultProperty("jdk.httpserver.maxConnections", "32");
        // Drain slightly oversized uploads after writing the 413 so closing the socket can deliver its body.
        defaultProperty("sun.net.httpserver.drainAmount", Integer.toString(MAX_BODY_BYTES + 1));
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 32);
        executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.start();
        expiry = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "session-expiry");
            thread.setDaemon(true);
            return thread;
        });
        expiry.scheduleWithFixedDelay(sessions::expireIdle, 1, 1, TimeUnit.MINUTES);
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
            expiry.shutdownNow();
            sessions.clear();
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith("/api/")) {
                    if (requireMethod(exchange, "GET")) serveStatic(exchange, path);
                    return;
                }

                exchange.getResponseHeaders().set("Cache-Control", "no-store");
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

                boolean mutation = expected.equals("POST");
                checkOrigin(exchange);
                if (mutation) requireJson(exchange);
                JsonObject body = mutation ? parseJsonObject(exchange, switch (path) {
                    case "/api/reset", "/api/step", "/api/detect" -> true;
                    default -> false;
                }) : null;
                sendJson(exchange, 200, handleSession(exchange, path, body, mutation));
            } catch (RequestFailure error) {
                if (error.code == ErrorCode.PAYLOAD_TOO_LARGE) exchange.getResponseHeaders().set("Connection", "close");
                ErrorMapper.sendError(exchange, error.code, error.getMessage());
            } catch (RuntimeException error) {
                if (exchange.getResponseCode() == -1) ErrorMapper.sendError(exchange, error);
            }
        }
    }

    private String handleSession(HttpExchange exchange, String path, JsonObject body, boolean mutation) {
        SessionStore.Session session = sessions.acquire(readCookie(exchange));
        SimulationEngine engine = session == null ? new SimulationEngine() : session.engine;
        try {
            synchronized (engine) {
                boolean created = session == null && path.equals("/api/scenario");
                if (created) {
                    engine.configure(JsonMapper.parseScenario(body));
                    session = sessions.create(engine);
                    if (session == null) throw new RequestFailure(ErrorCode.SESSION_LIMIT, "All simulation sessions are in use. Try again later.");
                    exchange.getResponseHeaders().add("Set-Cookie", cookieName + "=" + session.token
                            + "; Path=/; HttpOnly; SameSite=Strict" + (publicOrigin == null ? "" : "; Secure"));
                }
                String viewId = session == null ? emptyViewId : session.viewId;
                boolean includeView = "view=true".equals(exchange.getRequestURI().getRawQuery());
                JsonObject data = created ? new JsonObject() : handleApi(engine, viewId, path, body, includeView);
                if (created) data.add("state", JsonMapper.mapState(engine));
                if (mutation) addViewRevision(data, engine, viewId);
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.add("data", data);
                return result.toString();
            }
        } finally {
            if (session != null) sessions.release(session);
        }
    }

    private String readCookie(HttpExchange exchange) {
        String found = null;
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String entry : header.split(";")) {
                String[] parts = entry.strip().split("=", 2);
                if (!parts[0].equals(cookieName)) continue;
                if (found != null || parts.length != 2 || !parts[1].matches("[A-Za-z0-9_-]{43}")) return null;
                found = parts[1];
            }
        }
        return found;
    }

    private void checkOrigin(HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders().getOrDefault("Host", List.of());
        String expected = null;
        if (hosts.size() == 1) {
            try {
                String scheme = publicOrigin == null ? "http://" : "https://";
                URI host = URI.create(scheme + hosts.get(0));
                if (host.getHost() != null && host.getUserInfo() == null && host.getPath().isEmpty()
                        && host.getRawQuery() == null && host.getRawFragment() == null) {
                    String name = host.getHost().toLowerCase(Locale.ROOT);
                    if (publicOrigin == null && LOCAL_HOSTS.contains(name) && effectivePort(host) == exchange.getLocalAddress().getPort()) {
                        expected = originOf(host);
                    } else if (publicOrigin != null && originOf(host).equals(originOf(publicOrigin))) {
                        expected = originOf(publicOrigin);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed authorities are rejected with the same response as unrelated hosts.
            }
        }
        List<String> origins = exchange.getRequestHeaders().getOrDefault("Origin", List.of());
        List<String> sites = exchange.getRequestHeaders().getOrDefault("Sec-Fetch-Site", List.of());
        if (expected == null || (!origins.isEmpty() && !origins.equals(List.of(expected)))
                || (!sites.isEmpty() && !sites.equals(List.of("same-origin")) && !sites.equals(List.of("none")))) {
            throw new RequestFailure(ErrorCode.FORBIDDEN_ORIGIN, "The request must come from the simulator's own origin");
        }
    }

    private static int effectivePort(URI origin) {
        return origin.getPort() == -1 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
    }

    private static String originOf(URI origin) {
        int defaultPort = "https".equals(origin.getScheme()) ? 443 : 80;
        return origin.getScheme() + "://" + origin.getHost().toLowerCase(Locale.ROOT)
                + (effectivePort(origin) == defaultPort ? "" : ":" + effectivePort(origin));
    }

    private static void requireJson(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().getOrDefault("Content-Type", List.of());
        if (values.size() != 1 || !values.get(0).split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new RequestFailure(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Send mutation requests as application/json");
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", method);
        ErrorMapper.sendError(exchange, ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed: " + exchange.getRequestMethod());
        return false;
    }

    private JsonObject handleApi(SimulationEngine engine, String viewId, String path, JsonObject body, boolean includeView) {
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
                if (includeView) {
                    addViewRevision(data, engine, viewId);
                    data.add("state", engine.getScenario() == null ? JsonNull.INSTANCE : JsonMapper.mapState(engine));
                }
                return data;
            }
            case "/api/history/jump" -> {
                int seq = integer(body, "seq", Integer.MIN_VALUE, Integer.MAX_VALUE);
                if (body.has("from") && !text(body, "from").equals(viewId + ":" + engine.getRevision())) {
                    throw new RequestFailure(ErrorCode.STALE_VIEW, "History changed. Select an event from the refreshed history.");
                }
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
                if (scenario != null && scenario.getPreventionToggles().isNoPreemption() && waitingCount(engine) > 0) {
                    eventBudget = 2 + 2 * waitingCount(engine);
                }
                requireHistoryRoom(engine, eventBudget);
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
                requireHistoryRoom(engine, 1 + 2 * waitingCount(engine));
                ReleaseResult result = engine.release(process, resource, count);
                JsonArray unblocked = new JsonArray();
                result.unblockedProcessIds().forEach(unblocked::add);
                data.add("unblocked", unblocked);
            }
            case "/api/step" -> {
                requireHistoryRoom(engine, 3 + 2 * waitingCount(engine));
                StepResult result = engine.step();
                JsonObject executed = new JsonObject();
                executed.addProperty("description", result.description());
                data.add("executed", executed);
                data.addProperty("finished", result.finished());
            }
            case "/api/detect" -> {
                requireHistoryRoom(engine, 1);
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
                requireHistoryRoom(engine, 2 + 4 * processCount(engine) + 2 * waitingCount(engine));
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

    private static void addViewRevision(JsonObject data, SimulationEngine engine, String viewId) {
        data.addProperty("serverId", viewId);
        data.addProperty("revision", engine.getRevision());
    }

    private static String recoveryOption(JsonObject body, String field) {
        try {
            return text(body, field);
        } catch (IllegalArgumentException error) {
            throw new RequestFailure(ErrorCode.INVALID_POLICY, error.getMessage());
        }
    }

    private static int processCount(SimulationEngine engine) {
        Scenario scenario = engine.getScenario();
        return scenario == null ? 0 : scenario.getProcesses().size();
    }

    private static int waitingCount(SimulationEngine engine) {
        Scenario scenario = engine.getScenario();
        return scenario == null ? 0 : (int) scenario.getProcesses().stream().filter(process -> process.isWaiting()).count();
    }

    private static void requireHistoryRoom(SimulationEngine engine, int eventBudget) {
        // Budget the whole action before mutation so existing snapshots never need eviction.
        Scenario scenario = engine.getScenario();
        int processes = processCount(engine);
        int resources = scenario == null ? 0 : scenario.getResources().size();
        int snapshotWeight = 16 + 8 * processes + resources * (1 + 2 * processes);
        int limit = Math.min(MAX_HISTORY_EVENTS, MAX_HISTORY_WEIGHT / snapshotWeight);
        if (engine.getViewedSeq() + 1 > limit - eventBudget) {
            throw new RequestFailure(ErrorCode.HISTORY_LIMIT,
                    "Event history is full for this action. Step back or reset to continue.");
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String relative = (path.isEmpty() || "/".equals(path)) ? "index.html" : path.substring(1);
        if (!relative.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                || List.of(relative.split("/")).stream().anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
            return;
        }

        String contentType = switch (relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
            case "html" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            default -> null;
        };
        try (InputStream input = contentType == null ? null : openFrontendFile(relative)) {
            if (input == null) {
                ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (contentType.equals("text/html")) {
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; "
                        + "script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                        + "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'none'; "
                        + "frame-ancestors 'none'; form-action 'self'");
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                input.transferTo(output);
            }
        } catch (IOException | InvalidPathException | SecurityException error) {
            if (exchange.getResponseCode() == -1) ErrorMapper.sendError(exchange, ErrorCode.NOT_FOUND, "File not found");
        }
    }

    private InputStream openFrontendFile(String relative) throws IOException {
        if (frontendPath == null) return ApiServer.class.getResourceAsStream("/frontend/" + relative);
        Path root = frontendPath.toRealPath();
        Path target = root.resolve(relative).toRealPath();
        if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
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
}
