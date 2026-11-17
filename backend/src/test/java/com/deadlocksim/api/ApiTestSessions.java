package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

final class ApiTestSessions {
    private ApiTestSessions() {}

    static ApiServer server() {
        return new ApiServer(InetAddress.getLoopbackAddress(), 0, null, null);
    }

    static HttpClient client(ApiServer server, int port, SimulationEngine engine) {
        SessionStore.Session session = server.sessions.create(engine);
        server.sessions.release(session);
        CookieManager cookies = new CookieManager();
        HttpCookie cookie = new HttpCookie("deadlock-session", session.token);
        cookie.setVersion(0);
        cookie.setPath("/");
        cookies.getCookieStore().add(URI.create("http://localhost:" + port), cookie);
        return HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
    }
}
