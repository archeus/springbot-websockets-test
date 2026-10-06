package org.example.proxy.mvc;

import jakarta.websocket.DeploymentException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketProxyHandshakeHandlerTest {

    private static int status(Throwable e) {
        return WebSocketProxyHandshakeHandler.browserStatusFor(e);
    }

    @Test
    void passesOnTheUpstreamsRefusalStatus() {
        var refused = new DeploymentException(
                "The HTTP response from the server [403] did not permit the HTTP upgrade to WebSocket");
        assertEquals(403, status(new ExecutionException(refused)));
        assertEquals(401, status(new ExecutionException(new DeploymentException("... server [401] did not permit ..."))));
        assertEquals(503, status(new DeploymentException("... server [503] did not permit ...")));
    }

    @Test
    void timeoutBecomes504() {
        assertEquals(504, status(new TimeoutException()));
    }

    @Test
    void unreachableOrUnknownBecomes502() {
        var connectFailed = new DeploymentException("The HTTP request to initiate the WebSocket connection to [ws://x] failed",
                new ConnectException("Connection refused"));
        assertEquals(502, status(new ExecutionException(connectFailed)));
        assertEquals(502, status(new IllegalStateException("boom")));
    }

    @Test
    void ignoresBracketedNumbersThatAreNotErrorStatuses() {
        assertEquals(502, status(new DeploymentException("unexpected [101] response")));
        assertEquals(502, status(new DeploymentException("connect to [127.0.0.1:9081] failed [123]")));
    }
}
