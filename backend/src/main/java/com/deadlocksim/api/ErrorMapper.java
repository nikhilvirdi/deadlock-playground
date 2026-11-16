package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationException;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ErrorMapper {
    public static ErrorCode resolveCode(Throwable error) {
        if (error instanceof SimulationException simulation) {
            return ErrorCode.valueOf(simulation.kind().name());
        }
        return error instanceof IllegalArgumentException ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
    }

    public static JsonObject toJsonObject(ErrorCode code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code.name());
        error.addProperty("message", message != null ? message : code.name());
        root.add("error", error);
        return root;
    }

    public static JsonObject toJsonObject(Throwable error) {
        ErrorCode code = resolveCode(error);
        String message = code == ErrorCode.INTERNAL_ERROR ? "The simulator could not complete the request." : error.getMessage();
        return toJsonObject(code, message);
    }

    public static void sendError(HttpExchange exchange, Throwable error) throws IOException {
        writeError(exchange, resolveCode(error), toJsonObject(error));
    }

    public static void sendError(HttpExchange exchange, ErrorCode code, String message) throws IOException {
        writeError(exchange, code, toJsonObject(code, message));
    }

    private static void writeError(HttpExchange exchange, ErrorCode code, JsonObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code.getHttpStatus(), bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
