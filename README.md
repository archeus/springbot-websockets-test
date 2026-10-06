# Spring Boot reverse proxy for HTTP + WebSockets (POC)

Three Spring Boot 4 apps (Java 17+, plain `ws://`/`http://`, no TLS):

| Module          | Port | Stack                                          | What it does                                                  |
|-----------------|------|------------------------------------------------|---------------------------------------------------------------|
| `echo-server`   | 9081 | Spring MVC + Spring WebSocket                  | Backend: REST under `/path1/api/**`, WebSocket echo at `/path1/ws` |
| `mvc-proxy`     | 9080 | Spring MVC + Spring Cloud Gateway Server MVC (no WebFlux) | Reverse proxy for `/path1/**` (HTTP **and** WebSocket)     |
| `webflux-proxy` | 9082 | Spring Cloud Gateway Server WebFlux            | Same, reactive stack                                           |

Each app serves the same demo page at `/`. The page only uses relative URLs (`/path1/api/...`,
`ws://<page host>/path1/ws`), so on a proxy's port all traffic goes through that proxy.

## Run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn package -DskipTests

java -jar echo-server/target/echo-server-1.0-SNAPSHOT.jar
java -jar mvc-proxy/target/mvc-proxy-1.0-SNAPSHOT.jar
java -jar webflux-proxy/target/webflux-proxy-1.0-SNAPSHOT.jar
```

Open http://localhost:9081 (direct), http://localhost:9080 (MVC proxy), http://localhost:9082 (WebFlux proxy).
`GET /path1/api/request-info` and the WebSocket welcome message show the headers the backend received
(`Host`, `X-Forwarded-*`), which tells you whether the request went through a proxy.

## Backend endpoints (`echo-server`)

- `GET  /path1/api/hello?name=x`
- `POST /path1/api/echo` (JSON body, echoed back)
- `GET  /path1/api/request-info` (method, URL, remote address, headers as seen by the backend)
- `WS   /path1/ws`: sends a welcome message with the handshake headers, then echoes text and binary frames

## How the MVC proxy works

Spring Cloud Gateway Server MVC proxies plain HTTP but **does not support WebSockets**, so `mvc-proxy`
splits the work:

- **HTTP**: `HttpProxyRoutes` defines a Gateway MVC route for `proxy.http-paths` minus `proxy.websocket-paths`.
  The WebSocket paths must be excluded explicitly, because Gateway's router-function mapping is consulted
  before the WebSocket handler mapping and would otherwise forward the handshake as plain HTTP (dropping
  the hop-by-hop `Upgrade` header). Apache HttpClient 5 is on the classpath so the gateway talks HTTP/1.1
  to the backend; the JDK client it would otherwise use attempts an `h2c` upgrade on every request.
- **WebSocket**: `WebSocketProxyHandler` is a regular Spring `WebSocketHandler` registered on
  `proxy.websocket-paths`. For each browser session it opens an upstream session to the backend
  (same path and query string), then relays text/binary frames both ways. Closing either side closes the other.
  End-to-end headers (cookies, `Authorization`, …) are copied to the upstream handshake. Hop-by-hop,
  handshake and `Origin` headers are dropped. `X-Forwarded-For/Host/Proto` are set fresh rather than
  trusted from the client.

Configuration lives in `mvc-proxy/src/main/resources/application.yml` (`proxy.target-uri`, `proxy.http-paths`,
`proxy.websocket-paths`).

## How the WebFlux proxy works

Spring Cloud Gateway WebFlux supports WebSockets out of the box. A single `Path=/path1/**` route with an
`http://` URI handles both: when the request carries `Upgrade: websocket` the gateway switches to its
WebSocket routing filter. No code beyond the main class and `application.yml` is needed.

## Configuration notes

- `spring.cloud.gateway.server.{webmvc,webflux}.trusted-proxies`: recent Gateway versions only emit
  `X-Forwarded-*`/`Forwarded` headers when this is set. It is set to localhost here.
- WebSocket message size limits were raised to 1 MB everywhere. Defaults are 8 KB in Tomcat (`echo-server`
  and `mvc-proxy`) and 64 KB in the WebFlux gateway (`httpclient.websocket.max-frame-payload-length`).

## Verified behaviour

Tested with a Node WebSocket client, curl and a real browser (Chromium via Playwright) against all three ports:

- HTTP GET/POST proxied; backend sees `X-Forwarded-*` and `Forwarded` headers.
- WebSocket: welcome, text echo, binary echo, 200 KB message, query string passthrough, clean close (1000).
- Backend killed mid-session: both proxies close the browser socket (MVC: 1001, WebFlux: 1005).
- Backend down at connect time: MVC proxy accepts then closes with 1012 "Upstream unavailable".
  WebFlux accepts then closes with 1002. HTTP returns 500 from both.
- Cross-origin handshake (`Origin: http://evil.example`): MVC proxy rejects it with **403** (Spring's default
  same-origin check). The WebFlux gateway does no origin check and forwards it. The echo server allows `*`, so it is
  accepted (101). Restrict `echo.websocket.allowed-origin-patterns` on the backend or add an origin check to
  the gateway if that matters.

## Known limitations of the MVC WebSocket relay (POC scope)

- WebSocket sub-protocols (`Sec-WebSocket-Protocol`) are not negotiated through the proxy.
- The browser handshake completes before the upstream connection is attempted, so a down backend shows up as
  "open, then closed 1012" rather than a failed handshake.
- Each new connection blocks a servlet thread while connecting upstream (up to 10 s).
- Ping/pong is per hop and not relayed.
- Fragmented messages are reassembled (up to 1 MB) rather than streamed frame by frame.
