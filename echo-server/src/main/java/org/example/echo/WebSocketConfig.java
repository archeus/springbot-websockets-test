package org.example.echo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the raw WebSocket endpoint under /path1, next to the REST controllers in {@link EchoRestController}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Value("${echo.websocket.allowed-origin-patterns:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new EchoWebSocketHandler(), "/path1/ws")
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }

    /** Raise Tomcat's 8 KB default so larger (non-fragmented) messages can be echoed. */
    @Bean
    public ServletServerContainerFactoryBean webSocketServerContainer() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(1024 * 1024);
        factory.setMaxBinaryMessageBufferSize(1024 * 1024);
        return factory;
    }
}
