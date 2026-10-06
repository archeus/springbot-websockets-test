package org.example.proxy.mvc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * @param targetUri      backend base URI (http://host:port); WebSockets use the same host/port with ws://
 * @param httpPaths      path patterns proxied as plain HTTP by Spring Cloud Gateway MVC
 * @param websocketPaths paths proxied as WebSockets by {@link WebSocketProxyHandler}
 * @param cookies        how the backend's cookies are kept apart from this application's own cookies
 * @param websocketOrigin {@code Origin} header sent on the upstream WebSocket handshake; unset: none. The browser's
 *                        {@code Origin} (this proxy's address) is never forwarded.
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(URI targetUri, List<String> httpPaths, List<String> websocketPaths, Cookies cookies,
                             String websocketOrigin) {

    /**
     * @param prefix prefix marking the backend's cookies in the browser; only these are forwarded to the backend
     * @param path   path the backend's cookies are confined to in the browser
     */
    public record Cookies(String prefix, String path) {
    }
}
