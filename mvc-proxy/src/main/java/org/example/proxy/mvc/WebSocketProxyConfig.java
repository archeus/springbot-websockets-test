package org.example.proxy.mvc;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketProxyConfig implements WebSocketConfigurer {

    /** Max size of a single (non-fragmented) message, applied to both legs of the relay. */
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private final ProxyProperties props;

    public WebSocketProxyConfig(ProxyProperties props) {
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // No setAllowedOrigins(...): Spring then only accepts same-origin browser handshakes,
        // which is exactly the case for the page served by this proxy.
        registry.addHandler(webSocketProxyHandler(), props.websocketPaths().toArray(String[]::new));
    }

    @Bean
    public WebSocketProxyHandler webSocketProxyHandler() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setDefaultMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return new WebSocketProxyHandler(new StandardWebSocketClient(container), props.targetUri());
    }

    /** Buffer sizes for the browser-facing (server) side. */
    @Bean
    public ServletServerContainerFactoryBean webSocketServerContainer() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        factory.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return factory;
    }
}
