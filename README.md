# Deadlock Playground

## Build and run

Use a Java 17 or newer JDK and Maven. From the repository root:

```sh
mvn -f backend/pom.xml clean package
java -jar backend/target/deadlock-playground-1.0.jar
```

Open [Deadlock Playground on localhost](http://127.0.0.1:8080/). The executable JAR contains Gson and the frontend, including the graph libraries. It can be launched from any directory using its absolute path. To serve edited frontend files during development, set `FRONTEND_DIR` to the absolute path of the frontend directory.

| Setting | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Listening port. Use `0` to choose a free port for testing. |
| `BIND_ADDRESS` | `127.0.0.1` | Address to listen on. |
| `PUBLIC_ORIGIN` | Unset | Canonical browser origin for HTTPS deployments, for example `https://simulator.example`. |
| `FRONTEND_DIR` | Packaged frontend | Optional asset directory override. |

The first two optional command-line arguments override the port and asset directory.

## Public hosting

Terminate HTTPS at a trusted reverse proxy and forward the canonical public Host header. Set `PUBLIC_ORIGIN` to the same HTTPS origin and bind to the interface required by the platform. Keep the backend HTTP listener private. The app validates Host and supplied Origin and Fetch Metadata headers and accepts JSON for mutation requests. It uses a host-only Secure session cookie in public mode. Local loopback operation uses an HTTP-compatible cookie.

Simulations are in memory. Tabs sharing the same site cookie share a simulation. A new browser profile gets its own simulation after configuring a scenario. The server allows 16 concurrent simulations, expires idle sessions after 30 minutes, and applies a history memory budget based on scenario size. Reset clears the requesting session's data. Restarting the process clears all sessions.

The limits bound memory use. Anonymous clients can still occupy the available slots, so a public installation needs rate limiting at the proxy. Use one application instance unless traffic can be kept on the instance that issued its session cookie. The cookie is a bearer credential, and anyone who obtains a valid cookie can act as that session.

## Verification

The Maven build runs the backend and HTTP tests. To exercise the packaged frontend in Chrome or Chromium with Node 22 or newer:

```sh
node tests/browser/history-sync.cjs /path/to/chrome
```
