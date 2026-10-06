package org.example.proxy.mvc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Plain HTTP proxying via Spring Cloud Gateway Server MVC (servlet stack, no WebFlux).
 * <p>
 * Gateway MVC does not handle WebSocket upgrades, and its router function mapping is consulted before the
 * WebSocket handler mapping. So the WebSocket paths are explicitly excluded here, letting the handshake fall
 * through to {@link WebSocketProxyHandler} (see {@link WebSocketProxyConfig}).
 */
@Configuration
public class HttpProxyRoutes {

    @Bean
    public RouterFunction<ServerResponse> backendHttpRoute(ProxyProperties props) {
        RequestPredicate httpPaths = anyPath(props.httpPaths());
        RequestPredicate websocketPaths = anyPath(props.websocketPaths());
        return route("echo-server-http")
                .route(httpPaths.and(websocketPaths.negate()), http())
                .before(uri(props.targetUri()))
                .build();
    }

    private static RequestPredicate anyPath(List<String> patterns) {
        return patterns.stream()
                .map(p -> path(p))
                .reduce(RequestPredicate::or)
                .orElseThrow();
    }
}
