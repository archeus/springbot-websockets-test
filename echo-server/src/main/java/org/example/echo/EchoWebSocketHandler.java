package org.example.echo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.time.Instant;

/**
 * Echoes every text/binary frame back to the sender. On connect it sends a greeting containing
 * the handshake headers it saw, so you can tell whether the connection came through a proxy.
 */
public class EchoWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EchoWebSocketHandler.class);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WS connected: id={} remote={} uri={}", session.getId(), session.getRemoteAddress(), session.getUri());
        var headers = session.getHandshakeHeaders();
        session.sendMessage(new TextMessage("welcome from echo-server, session " + session.getId()
                + " | Host=" + headers.getFirst("Host")
                + " | X-Forwarded-For=" + headers.getFirst("X-Forwarded-For")
                + " | X-Forwarded-Host=" + headers.getFirst("X-Forwarded-Host")
                + " | Cookie=" + headers.getFirst("Cookie")
                + " | uri=" + session.getUri()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(new TextMessage("echo [" + Instant.now() + "]: " + message.getPayload()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        session.sendMessage(new BinaryMessage(message.getPayload()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WS closed: id={} status={}", session.getId(), status);
    }
}
