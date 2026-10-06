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
mvn package

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
- `GET  /path1/api/redirect/absolute`: 302 to its own absolute URL (`http://localhost:9081/...`), built from the request
- `GET  /path1/api/redirect/relative`: 302 to `/path1/api/hello...`
- `GET  /path1/api/redirect/external`: 302 to `https://example.com/`
- `POST /path1/api/cookies`: sets `JSESSIONID` and `BACKEND_PREF` (with `Path=/`)
- `GET  /path1/api/cookies`: shows the `Cookie` header the backend received
- `WS   /path1/ws`: sends a welcome message with the handshake headers (including `Cookie`), then echoes text and binary frames

Each proxy also hosts a stand-in for its own business logic: `POST /main/cookies` sets `JSESSIONID` and
`MAIN_PREF`, and `GET /main/cookies` shows what it received.

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
  End-to-end headers (`Authorization`, …) are copied to the upstream handshake, and cookies are filtered
  (see [Cookie separation](#cookie-separation)). Hop-by-hop,
  handshake and `Origin` headers are dropped. `X-Forwarded-For/Host/Proto` are set fresh rather than
  trusted from the client.

Configuration lives in `mvc-proxy/src/main/resources/application.yml` (`proxy.target-uri`, `proxy.http-paths`,
`proxy.websocket-paths`, `proxy.cookies`).

## How the WebFlux proxy works

Spring Cloud Gateway WebFlux supports WebSockets out of the box. A single `Path=/path1/**` route with an
`http://` URI handles both: when the request carries `Upgrade: websocket` the gateway switches to its
WebSocket routing filter. The only custom code is the `ProxyCookies` route filter described below.

## Cookie separation

The browser sees one host, the proxy's, so it keeps the hosting application's cookies and the backend's in one
jar and sends them all on every request. Both proxies keep them apart with a prefix (`PX_`), applied by
`ProxyCookieRewriter`. It is plain Java, copied into both proxies (keep the two copies in sync):

| Direction | What the proxy does |
|---|---|
| Backend to browser (`Set-Cookie`) | Renames `JSESSIONID` to `PX_JSESSIONID`, drops `Domain`, and confines `Path` to `/path1`, so the browser never sends backend cookies to the hosting application. |
| Browser to backend (`Cookie`) | Forwards only `PX_` cookies, with the prefix removed. All other cookies are dropped; if none remain, the header is removed. |

The backend never sees the prefix, and same-named cookies (e.g. both apps' `JSESSIONID`) no longer collide.

Configuration:
- MVC: `proxy.cookies.prefix` and `proxy.cookies.path`. Applied to the HTTP route as a filter (`ProxyCookiesConfig`)
  and to the WebSocket handshake in `WebSocketProxyHandler`.
- WebFlux: the route filter `ProxyCookies=PX_, /path1` (`ProxyCookiesGatewayFilterFactory`). Being per route,
  each backend can get its own prefix and path.

Limitations:
- WebSocket handshakes only filter the request side. Cookies a backend sets in its handshake response don't reach
  the browser (in the MVC relay, the browser's handshake has already completed by then). Sessions are
  normally established over HTTP first, so this rarely matters.
- The prefix separates the two applications; it is not a security boundary between them. Code on the hosting
  application, including its JavaScript for cookies without `HttpOnly`, can still read or set `PX_` cookies.
- Browser-enforced name prefixes (`__Host-`, `__Secure-`) only work at the very start of the name, so renaming
  them to `PX___Host-...` loses their guarantee. Handle this when TLS is added.

## Configuration notes

- The active config of each proxy is `application.yml`. Next to it, `application.properties.example` holds the
  same settings in `.properties` format, for reference only; the `.example` extension keeps Spring Boot from loading it.
- `spring.cloud.gateway.server.{webmvc,webflux}.trusted-proxies`: recent Gateway versions only emit
  `X-Forwarded-*`/`Forwarded` headers when this is set. It is set to localhost here.
- WebSocket message size limits were raised to 1 MB everywhere. Defaults are 8 KB in Tomcat (`echo-server`
  and `mvc-proxy`) and 64 KB in the WebFlux gateway (`httpclient.websocket.max-frame-payload-length`).

## Redirects

A backend that builds absolute redirect URLs from the request it received uses its own address
(`Location: http://localhost:9081/...`). Passed through as-is, that sends the browser straight to the backend,
bypassing the proxy. Both proxies therefore rewrite `Location` with `UpstreamLocationRewriter` (plain Java, copied
into each proxy):

- A location with the upstream's own origin (same scheme, host and port) becomes relative (`/path1/...?...#...`). The
  browser resolves it against the proxy's address, so the proxy's public host and scheme don't need configuring.
  This also covers an `https://` backend behind an `http://` proxy.
- Relative locations and redirects to other sites (e.g. an identity provider) are left untouched.

Wiring: MVC adds an `.after(...)` step to the Gateway MVC route (`HttpProxyRoutes`). WebFlux uses the `ProxyRedirects`
route filter, which compares against the URL the gateway actually called.

The gateways' built-in `RewriteLocationResponseHeader` filter is not used: it replaces the host of *any* absolute
location with the incoming `Host` (breaking external redirects) and keeps the backend's scheme.

Only `Location` is rewritten. URLs inside response bodies (HTML links, JSON) are not; for those the backend should
produce relative URLs or honour the `X-Forwarded-*` headers the proxies send (Spring Boot:
`server.forward-headers-strategy=framework`).

## Upstream TLS (https:// / wss:// backends)

Point the proxy at an `https://` target and WebSockets automatically go to `wss://`. With a backend certificate
the JVM doesn't trust (self-signed, wrong host name), the handshake fails with `PKIX path building failed`.

**Dev only: skip verification**

- MVC: `proxy.insecure-tls=true` (`InsecureTlsConfig`). It has to cover two separate clients:
  - HTTP: Gateway MVC ignores an `HttpClient` bean you define yourself; it only uses a `ClientHttpRequestFactory`
    bean. The config builds one through Boot's `ClientHttpRequestFactoryBuilder`, replacing only TLS, so the
    gateway's other client settings (e.g. not following redirects) still apply.
  - WebSocket: the relay's `StandardWebSocketClient` gets the same trust-all `SSLContext` via `setSslContext`.
  - The trust manager must extend `X509ExtendedTrustManager`. A plain `X509TrustManager` is wrapped by the JDK,
    which still checks the host name. Tomcat's WebSocket client turns that check on, so it fails with
    `No name matching localhost found`.
- WebFlux: `spring.cloud.gateway.server.webflux.httpclient.ssl.use-insecure-trust-manager=true`. HTTP and WebSocket share
  the gateway's HTTP client, so one property covers both.

**Proper alternative:** trust the backend's certificate instead of trusting everything. Use an SSL bundle, or
`spring.cloud.gateway.server.webflux.httpclient.ssl.trusted-x509-certificates` on WebFlux.

Tested against the echo server on HTTPS with a self-signed certificate for `CN=echo.invalid`, reached as
`localhost`: both proxies fail by default and work over HTTP and WebSocket with the setting on.

```bash
keytool -genkeypair -alias echo -keyalg RSA -dname "CN=echo.invalid" -storetype PKCS12 -keystore echo-tls.p12 -storepass changeit
java -jar echo-server/target/echo-server-1.0-SNAPSHOT.jar --server.port=9443 \
  --server.ssl.key-store=file:echo-tls.p12 --server.ssl.key-store-password=changeit
java -jar mvc-proxy/target/mvc-proxy-1.0-SNAPSHOT.jar --proxy.target-uri=https://localhost:9443 --proxy.insecure-tls=true
```

## Verified behaviour

Tested with a Node WebSocket client, curl and a real browser (Chromium via Playwright) against all three ports:

- HTTP GET/POST proxied; backend sees `X-Forwarded-*` and `Forwarded` headers.
- WebSocket: welcome, text echo, binary echo, 200 KB message, query string passthrough, clean close (1000).
- Backend killed mid-session: both proxies close the browser socket (MVC: 1001, WebFlux: 1005).
- Backend down at connect time: MVC proxy accepts then closes with 1012 "Upstream unavailable".
  WebFlux accepts then closes with 1002. HTTP returns 500 from both.
- Cookies (HTTP and WebSocket handshake, both proxies): the backend receives only its own cookies, unprefixed. The
  hosting app receives only its own, and the two `JSESSIONID`s coexist. Unit tests: `ProxyCookieRewriterTest`.
- Redirects (curl and browser, both proxies, plain and HTTPS backend): the absolute redirect to the backend
  lands on the proxy; relative and external redirects are unchanged. Unit tests: `UpstreamLocationRewriterTest`.
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
