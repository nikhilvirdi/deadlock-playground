package com.deadlocksim.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class ApiInput {
    static final int MAX_BODY_BYTES = 256 * 1024;
    static final int MAX_PROCESSES = 32;
    static final int MAX_RESOURCES = 32;
    static final int MAX_INSTANCES = 256;
    static final int MAX_OPERATIONS = 2048;
    static final int MAX_HISTORY_EVENTS = 2048;
    private static final int MAX_TEXT_LENGTH = 128;

    private ApiInput() {}

    static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Field '" + field + "' must contain 1 to "
                    + MAX_TEXT_LENGTH + " characters and no control characters");
        }
        return text;
    }

    static int integer(JsonObject object, String field, int min, int max) {
        JsonElement value = object.get(field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int number = value.getAsBigDecimal().intValueExact();
                if (number >= min && number <= max) return number;
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Fractional and overflowing literals use the same validation error.
            }
        }
        throw new IllegalArgumentException("Field '" + field + "' must be an integer from " + min + " to " + max);
    }

    static boolean flag(JsonObject object, String field) {
        if (!object.has(field)) return false;
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a boolean");
        }
        return value.getAsBoolean();
    }

    static JsonArray array(JsonObject object, String field, int max) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > max) {
            throw new IllegalArgumentException("Field '" + field + "' must be an array with at most " + max + " entries");
        }
        return value.getAsJsonArray();
    }

    static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Field '" + field + "' must contain a JSON object");
        }
        return value.getAsJsonObject();
    }
}
