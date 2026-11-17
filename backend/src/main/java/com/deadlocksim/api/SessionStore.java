package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

final class SessionStore {
    static final int MAX_SESSIONS = 16;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final int capacity;
    private final long idleNanos;
    private final LongSupplier clock;

    SessionStore() {
        this(MAX_SESSIONS, IDLE_TIMEOUT, System::nanoTime);
    }

    SessionStore(int capacity, Duration idleTimeout, LongSupplier clock) {
        this.capacity = capacity;
        this.idleNanos = idleTimeout.toNanos();
        this.clock = clock;
    }

    synchronized Session acquire(String token) {
        expireIdle();
        Session session = sessions.get(token);
        if (session != null) session.inFlight++;
        return session;
    }

    synchronized Session create(SimulationEngine engine) {
        expireIdle();
        if (sessions.size() >= capacity) return null;

        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (sessions.containsKey(token));
        Session session = new Session(token, engine);
        sessions.put(token, session);
        return session;
    }

    synchronized void release(Session session) {
        session.inFlight--;
        session.lastUsed = clock.getAsLong();
    }

    synchronized void expireIdle() {
        long now = clock.getAsLong();
        sessions.values().removeIf(session -> session.inFlight == 0 && now - session.lastUsed >= idleNanos);
    }

    synchronized void clear() {
        sessions.clear();
    }

    static final class Session {
        final String token;
        final SimulationEngine engine;
        // This public revision namespace is separate from the private cookie credential.
        final String viewId = UUID.randomUUID().toString();
        private int inFlight = 1;
        private long lastUsed;

        private Session(String token, SimulationEngine engine) {
            this.token = token;
            this.engine = engine;
        }
    }
}
