package org.example.proxy.mvc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * @param targetUri      backend base URI (http://host:port); WebSockets use the same host/port with ws://
 * @param httpPaths      path patterns proxied as plain HTTP by Spring Cloud Gateway MVC
 * @param websocketPaths paths proxied as WebSockets by {@link WebSocketProxyHandler}
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(URI targetUri, List<String> httpPaths, List<String> websocketPaths) {
}
