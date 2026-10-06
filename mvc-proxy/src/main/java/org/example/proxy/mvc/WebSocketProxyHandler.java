package org.example.proxy.mvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket reverse proxy for the servlet stack: relays text/binary frames between the browser ("downstream")
 * session and the backend ("upstream") session. Closing either side closes the other one.
 * <p>
 * The upstream session is opened during the browser's handshake by {@link WebSocketProxyHandshakeHandler}, which
 * passes it here through the session attributes.
 */
public class WebSocketProxyHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketProxyHandler.class);

    static final String RELAY_ATTR = WebSocketProxyHandler.class.getName() + ".relay";
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    @Override
    public void afterConnectionEstablished(WebSocketSession downstream) throws Exception {
        UpstreamRelay relay = relay(downstream);
        if (relay == null) {
            log.warn("WS downstream {} has no upstream connection", downstream.getId());
            downstream.close(CloseStatus.SERVER_ERROR);
            return;
        }
        relay.attach(new ConcurrentWebSocketSessionDecorator(
                downstream, SEND_TIME_LIMIT_MS, WebSocketProxyConfig.MAX_MESSAGE_BYTES));
    }

    @Override
    public void handleMessage(WebSocketSession downstream, WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage || message instanceof BinaryMessage) {
            UpstreamRelay relay = relay(downstream);
            WebSocketSession upstream = relay == null ? null : relay.upstream();
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
        UpstreamRelay relay = relay(downstream);
        if (relay != null) {
            closeQuietly(relay.upstream(), status);
        }
    }

    private static UpstreamRelay relay(WebSocketSession downstream) {
        return (UpstreamRelay) downstream.getAttributes().get(RELAY_ATTR);
    }

    static void closeQuietly(WebSocketSession session, CloseStatus status) {
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

    /**
     * Upstream side of the relay: receives frames from the backend and forwards them to the browser.
     * <p>
     * The upstream connects before the browser's session exists, so frames (and a close) arriving in between are
     * held until {@link #attach} and then delivered in order.
     */
    static final class UpstreamRelay extends AbstractWebSocketHandler {

        private final List<WebSocketMessage<?>> pending = new ArrayList<>();
        private WebSocketSession downstream;      // guarded by this
        private CloseStatus closedBeforeAttach;   // guarded by this
        private volatile WebSocketSession upstream;

        void setUpstream(WebSocketSession upstream) {
            this.upstream = new ConcurrentWebSocketSessionDecorator(
                    upstream, SEND_TIME_LIMIT_MS, WebSocketProxyConfig.MAX_MESSAGE_BYTES);
        }

        WebSocketSession upstream() {
            return upstream;
        }

        void attach(WebSocketSession downstream) throws IOException {
            CloseStatus closed;
            synchronized (this) {
                for (WebSocketMessage<?> message : pending) {
                    downstream.sendMessage(message);
                }
                pending.clear();
                this.downstream = downstream;
                closed = closedBeforeAttach;
            }
            if (closed != null) {
                closeQuietly(downstream, closed);
            }
        }

        void closeIfNeverAttached() {
            boolean attached;
            synchronized (this) {
                attached = downstream != null;
                pending.clear();
            }
            if (!attached) {
                log.warn("WS browser session never opened; closing upstream {}", upstream == null ? null : upstream.getId());
                closeQuietly(upstream, CloseStatus.GOING_AWAY);
            }
        }

        @Override
        public void handleMessage(WebSocketSession upstreamSession, WebSocketMessage<?> message) throws Exception {
            if (!(message instanceof TextMessage || message instanceof BinaryMessage)) {
                return;
            }
            WebSocketSession target;
            synchronized (this) {
                if (downstream == null) {
                    pending.add(message);
                    return;
                }
                target = downstream;
            }
            if (target.isOpen()) {
                target.sendMessage(message);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession upstreamSession, Throwable exception) {
            log.warn("WS upstream transport error on {}: {}", upstreamSession.getId(), exception.toString());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession upstreamSession, CloseStatus status) {
            log.info("WS upstream {} closed: {}", upstreamSession.getId(), status);
            WebSocketSession target;
            synchronized (this) {
                target = downstream;
                if (target == null) {
                    closedBeforeAttach = status;
                }
            }
            if (target != null) {
                closeQuietly(target, status);
            }
        }
    }
}
