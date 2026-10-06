package org.example.proxy.mvc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
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
    public RouterFunction<ServerResponse> backendHttpRoute(ProxyProperties props, ProxyCookieRewriter cookieRewriter) {
        RequestPredicate httpPaths = anyPath(props.httpPaths());
        RequestPredicate websocketPaths = anyPath(props.websocketPaths());
        return route("echo-server-http")
                .route(httpPaths.and(websocketPaths.negate()), http())
                .before(uri(props.targetUri()))
                .filter(ProxyCookiesConfig.proxyCookies(cookieRewriter))
                .after((request, response) -> keepRedirectOnProxy(response, props.targetUri()))
                .build();
    }

    /** Redirects to the upstream's own address would send the browser around the proxy; make them relative. */
    private static ServerResponse keepRedirectOnProxy(ServerResponse response, URI upstream) {
        String location = response.headers().getFirst(HttpHeaders.LOCATION);
        String rewritten = UpstreamLocationRewriter.toProxyLocation(location, upstream);
        if (location != null && !location.equals(rewritten)) {
            response.headers().set(HttpHeaders.LOCATION, rewritten);
        }
        return response;
    }

    private static RequestPredicate anyPath(List<String> patterns) {
        return patterns.stream()
                .map(p -> path(p))
                .reduce(RequestPredicate::or)
                .orElseThrow();
    }
}
