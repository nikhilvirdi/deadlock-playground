package com.deadlocksim;

import com.deadlocksim.api.ApiServer;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args.length > 0 && !args[0].isBlank()
                ? args[0] : environment("PORT", "8080"));
        InetAddress bind = InetAddress.getByName(environment("BIND_ADDRESS", "127.0.0.1"));
        String frontend = args.length > 1 && !args[1].isBlank() ? args[1] : environment("FRONTEND_DIR", null);
        ApiServer server = new ApiServer(bind, port, frontend == null ? null : Path.of(frontend), environment("PUBLIC_ORIGIN", null));
        int boundPort = server.start();
        String host = bind.getHostAddress();
        if (host.contains(":")) host = "[" + host + "]";
        System.out.println("Simulator listening on http://" + host + ":" + boundPort);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
