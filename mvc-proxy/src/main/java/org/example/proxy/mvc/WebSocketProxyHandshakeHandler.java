package org.example.proxy.mvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects to the upstream <em>during</em> the browser's WebSocket handshake, before answering it.
 * <ul>
 *     <li>Upstream refuses (e.g. 403): the browser gets that status instead of a 101, so the real failure shows up
 *     in the browser instead of "connected, then closed".</li>
 *     <li>Upstream unreachable / too slow: the browser gets 502 / 504.</li>
 *     <li>Upstream accepts: the browser's handshake completes with the subprotocol the upstream chose, and the open
 *     upstream session is handed to {@link WebSocketProxyHandler} for relaying.</li>
 * </ul>
 * Runs after the handshake interceptors, including Spring's same-origin check, so rejected browser handshakes never
 * reach the upstream.
 */
public class WebSocketProxyHandshakeHandler implements HandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketProxyHandshakeHandler.class);

    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    /** Tomcat's client reports a refused handshake as "The HTTP response from the server [403] did not permit...". */
    private static final Pattern UPSTREAM_STATUS = Pattern.compile("\\[(\\d{3})]");

    /**
     * Headers not copied to the upstream handshake: hop-by-hop and handshake headers (the WebSocket client
     * generates its own; the subprotocol is forwarded explicitly), forwarding headers, which are set fresh so a
     * browser cannot spoof them, and {@code Cookie}, which is filtered by {@link ProxyCookieRewriter}.
     */
    private static final Set<String> EXCLUDED_HEADERS = caseInsensitive(
            "Host", "Origin", "Connection", "Upgrade", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding",
            "Content-Length", "Proxy-Authorization", "Proxy-Connection",
            "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions",
            "Sec-WebSocket-Accept", "Sec-WebSocket-Protocol",
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
            "X-Forwarded-Prefix", "Cookie");

    /** Subprotocol the upstream accepted, handed to {@link #browserHandshake} on the same thread. */
    private static final ThreadLocal<String> UPSTREAM_PROTOCOL = new ThreadLocal<>();

    private final WebSocketClient client;
    private final URI targetUri;
    private final ProxyCookieRewriter cookieRewriter;
    private final String upstreamOrigin;

    /** Performs the actual upgrade of the browser's connection, answering with the upstream's subprotocol. */
    private final DefaultHandshakeHandler browserHandshake = new DefaultHandshakeHandler() {
        @Override
        protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
            return UPSTREAM_PROTOCOL.get();
        }
    };

    /**
     * @param upstreamOrigin {@code Origin} to send on the upstream handshake, or {@code null} to send none. For
     *                       upstreams that reject handshakes without one, or only accept specific origins.
     */
    public WebSocketProxyHandshakeHandler(WebSocketClient client, URI targetUri, ProxyCookieRewriter cookieRewriter,
                                          String upstreamOrigin) {
        this.client = client;
        this.targetUri = targetUri;
        this.cookieRewriter = cookieRewriter;
        this.upstreamOrigin = upstreamOrigin == null || upstreamOrigin.isBlank() ? null : upstreamOrigin;
    }

    @Override
    public boolean doHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Map<String, Object> attributes) throws HandshakeFailureException {
        if (!isWebSocketUpgrade(request)) {
            // Not a valid handshake: let Spring answer it as usual, without bothering the upstream.
            return browserHandshake.doHandshake(request, response, wsHandler, attributes);
        }

        URI upstreamUri = upstreamUri(request.getURI());
        WebSocketHttpHeaders upstreamHeaders = upstreamHeaders(request);
        log.info("WS {} -> {}", request.getURI(), upstreamUri);
        if (log.isDebugEnabled()) {
            // Names only: values may be credentials.
            log.debug("WS upstream handshake to {}: headers={} cookies={}", upstreamUri,
                    upstreamHeaders.headerNames(), cookieNames(upstreamHeaders.getFirst(HttpHeaders.COOKIE)));
        }

        WebSocketProxyHandler.UpstreamRelay relay = new WebSocketProxyHandler.UpstreamRelay();
        WebSocketSession upstream;
        try {
            upstream = client.execute(relay, upstreamHeaders, upstreamUri).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            int status = browserStatusFor(e);
            log.warn("WS upstream handshake to {} failed, answering the browser with {}: {}",
                    upstreamUri, status, rootCause(e).toString());
            reject(response, status);
            return false;
        }

        relay.setUpstream(upstream);
        attributes.put(WebSocketProxyHandler.RELAY_ATTR, relay);
        boolean upgraded = false;
        UPSTREAM_PROTOCOL.set(upstream.getAcceptedProtocol());
        try {
            upgraded = browserHandshake.doHandshake(request, response, wsHandler, attributes);
            return upgraded;
        } finally {
            UPSTREAM_PROTOCOL.remove();
            if (upgraded) {
                // Safety net: if the browser's side never opens, don't keep the upstream connection forever.
                CompletableFuture.delayedExecutor(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .execute(relay::closeIfNeverAttached);
            } else {
                WebSocketProxyHandler.closeQuietly(upstream, CloseStatus.GOING_AWAY);
            }
        }
    }

    private static boolean isWebSocketUpgrade(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
                && "websocket".equalsIgnoreCase(request.getHeaders().getUpgrade());
    }

    /** The upstream's own 4xx/5xx if it answered one, 504 if it was too slow, 502 if it could not be reached. */
    static int browserStatusFor(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return 504;
            }
            if (t.getMessage() != null) {
                Matcher matcher = UPSTREAM_STATUS.matcher(t.getMessage());
                if (matcher.find()) {
                    int status = Integer.parseInt(matcher.group(1));
                    if (status >= 400 && status <= 599) {
                        return status;
                    }
                }
            }
        }
        return 502;
    }

    private static void reject(ServerHttpResponse response, int status) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        try {
            response.getBody().write(("Upstream refused or failed the WebSocket handshake (" + status + ")\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Could not write handshake rejection body", e);
        }
    }

    private URI upstreamUri(URI browserUri) {
        String scheme = "https".equalsIgnoreCase(targetUri.getScheme()) ? "wss" : "ws";
        return UriComponentsBuilder.fromUri(targetUri)
                .scheme(scheme)
                .replacePath(browserUri.getRawPath())
                .replaceQuery(browserUri.getRawQuery())
                .build(true)
                .toUri();
    }

    private WebSocketHttpHeaders upstreamHeaders(ServerHttpRequest request) {
        HttpHeaders in = request.getHeaders();
        WebSocketHttpHeaders out = new WebSocketHttpHeaders();
        in.forEach((name, values) -> {
            if (!EXCLUDED_HEADERS.contains(name)) {
                out.addAll(name, values);
            }
        });

        // Offer the upstream the same subprotocols the browser asked for; its choice goes back to the browser.
        List<String> protocols = in.getValuesAsList(WebSocketHttpHeaders.SEC_WEBSOCKET_PROTOCOL);
        if (!protocols.isEmpty()) {
            out.setSecWebSocketProtocol(protocols);
        }

        // Only the backend's (prefixed) cookies are forwarded. Cookies the backend sets in its handshake response
        // do not reach the browser.
        String backendCookies = cookieRewriter.toBackendCookieHeader(in.getOrEmpty(HttpHeaders.COOKIE));
        if (backendCookies != null) {
            out.set(HttpHeaders.COOKIE, backendCookies);
        }

        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            out.set("X-Forwarded-For", remote.getAddress().getHostAddress());
        }
        if (in.getFirst(HttpHeaders.HOST) != null) {
            out.set("X-Forwarded-Host", in.getFirst(HttpHeaders.HOST));
        }
        out.set("X-Forwarded-Proto", "https".equalsIgnoreCase(request.getURI().getScheme()) ? "https" : "http");
        if (upstreamOrigin != null) {
            out.setOrigin(upstreamOrigin);
        }
        return out;
    }

    private static List<String> cookieNames(String cookieHeader) {
        if (cookieHeader == null) {
            return List.of();
        }
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .map(cookie -> cookie.contains("=") ? cookie.substring(0, cookie.indexOf('=')) : cookie)
                .toList();
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static Set<String> caseInsensitive(String... names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(Set.of(names));
        return set;
    }
}
