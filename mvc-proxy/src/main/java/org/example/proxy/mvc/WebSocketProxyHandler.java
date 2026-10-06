package org.example.proxy.mvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket reverse proxy for the servlet stack.
 * <p>
 * For every browser ("downstream") session accepted by this proxy, it opens a matching "upstream"
 * session to the backend (same path + query string) and relays text/binary frames in both directions.
 * Closing either side closes the other one.
 */
public class WebSocketProxyHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketProxyHandler.class);

    private static final String UPSTREAM_ATTR = WebSocketProxyHandler.class.getName() + ".upstream";
    private static final long CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    /**
     * Headers not copied to the upstream handshake: hop-by-hop and handshake headers (the WebSocket client
     * generates its own), plus forwarding headers, which are set fresh so a browser cannot spoof them.
     */
    private static final Set<String> EXCLUDED_HEADERS = caseInsensitive(
            "Host", "Origin", "Connection", "Upgrade", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding",
            "Content-Length", "Proxy-Authorization", "Proxy-Connection",
            "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions",
            "Sec-WebSocket-Accept", "Sec-WebSocket-Protocol",
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
            "X-Forwarded-Prefix");

    private final WebSocketClient client;
    private final URI targetUri;

    public WebSocketProxyHandler(WebSocketClient client, URI targetUri) {
        this.client = client;
        this.targetUri = targetUri;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession downstream) throws Exception {
        WebSocketSession safeDownstream = new ConcurrentWebSocketSessionDecorator(
                downstream, SEND_TIME_LIMIT_MS, WebSocketProxyConfig.MAX_MESSAGE_BYTES);
        URI upstreamUri = upstreamUri(downstream.getUri());
        log.info("WS {} -> {}", downstream.getUri(), upstreamUri);
        try {
            // Blocks the container thread until the backend accepted the handshake; frames from the
            // browser are not dispatched before this method returns, so nothing is lost meanwhile.
            WebSocketSession upstream = client
                    .execute(new UpstreamHandler(safeDownstream), upstreamHeaders(downstream), upstreamUri)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            downstream.getAttributes().put(UPSTREAM_ATTR, new ConcurrentWebSocketSessionDecorator(
                    upstream, SEND_TIME_LIMIT_MS, WebSocketProxyConfig.MAX_MESSAGE_BYTES));
        } catch (Exception e) {
            log.warn("WS upstream connect to {} failed: {}", upstreamUri, e.toString());
            downstream.close(CloseStatus.SERVICE_RESTARTED.withReason("Upstream unavailable"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession downstream, WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage || message instanceof BinaryMessage) {
            WebSocketSession upstream = upstream(downstream);
            if (upstream != null && upstream.isOpen()) {
                upstream.sendMessage(message);
            }
        }
        // Ping/pong are hop-by-hop: each leg's container answers its own pings.
    }

    @Override
    public void handleTransportError(WebSocketSession downstream, Throwable exception) {
        log.warn("WS downstream transport error on {}: {}", downstream.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession downstream, CloseStatus status) {
        log.info("WS downstream {} closed: {}", downstream.getId(), status);
        closeQuietly(upstream(downstream), status);
    }

    private static WebSocketSession upstream(WebSocketSession downstream) {
        return (WebSocketSession) downstream.getAttributes().get(UPSTREAM_ATTR);
    }

    private URI upstreamUri(URI downstreamUri) {
        String scheme = "https".equalsIgnoreCase(targetUri.getScheme()) ? "wss" : "ws";
        return UriComponentsBuilder.fromUri(targetUri)
                .scheme(scheme)
                .replacePath(downstreamUri.getRawPath())
                .replaceQuery(downstreamUri.getRawQuery())
                .build(true)
                .toUri();
    }

    private static WebSocketHttpHeaders upstreamHeaders(WebSocketSession downstream) {
        HttpHeaders in = downstream.getHandshakeHeaders();
        WebSocketHttpHeaders out = new WebSocketHttpHeaders();
        in.forEach((name, values) -> {
            if (!EXCLUDED_HEADERS.contains(name)) {
                out.addAll(name, values);
            }
        });

        InetSocketAddress remote = downstream.getRemoteAddress();
        if (remote != null) {
            out.set("X-Forwarded-For", remote.getAddress().getHostAddress());
        }
        if (in.getFirst("Host") != null) {
            out.set("X-Forwarded-Host", in.getFirst("Host"));
        }
        URI uri = downstream.getUri();
        if (uri != null) {
            out.set("X-Forwarded-Proto", "wss".equals(uri.getScheme()) || "https".equals(uri.getScheme()) ? "https" : "http");
        }
        return out;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(sendable(status));
        } catch (IOException e) {
            log.debug("Ignoring error while closing {}", session.getId(), e);
        }
    }

    /** 1005/1006/1015 are reserved for local reporting and must not be sent in a close frame. */
    private static CloseStatus sendable(CloseStatus status) {
        int code = status.getCode();
        return (code == 1005 || code == 1006 || code == 1015) ? CloseStatus.GOING_AWAY : status;
    }

    private static Set<String> caseInsensitive(String... names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(Set.of(names));
        return set;
    }

    /** Receives frames from the backend and forwards them to the browser. */
    private static final class UpstreamHandler extends AbstractWebSocketHandler {

        private final WebSocketSession downstream;

        UpstreamHandler(WebSocketSession downstream) {
            this.downstream = downstream;
        }

        @Override
        public void handleMessage(WebSocketSession upstream, WebSocketMessage<?> message) throws Exception {
            if ((message instanceof TextMessage || message instanceof BinaryMessage) && downstream.isOpen()) {
                downstream.sendMessage(message);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession upstream, Throwable exception) {
            log.warn("WS upstream transport error on {}: {}", upstream.getId(), exception.toString());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession upstream, CloseStatus status) {
            log.info("WS upstream {} closed: {}", upstream.getId(), status);
            closeQuietly(downstream, status);
        }
    }
}
