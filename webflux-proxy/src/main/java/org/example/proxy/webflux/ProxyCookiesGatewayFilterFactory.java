package org.example.proxy.webflux;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Route filter {@code ProxyCookies=<prefix>, <path>} applying {@link ProxyCookieRewriter}: only prefixed cookies are
 * forwarded to the backend (prefix removed), and the backend's {@code Set-Cookie} headers are prefixed and confined
 * to {@code path}. Also applies to WebSocket handshakes on the route (request side only).
 */
@Component
public class ProxyCookiesGatewayFilterFactory extends AbstractGatewayFilterFactory<ProxyCookiesGatewayFilterFactory.Config> {

    public ProxyCookiesGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("prefix", "path");
    }

    @Override
    public GatewayFilter apply(Config config) {
        ProxyCookieRewriter rewriter = new ProxyCookieRewriter(config.getPrefix(), config.getPath());
        return (exchange, chain) -> {
            String backendCookies = rewriter.toBackendCookieHeader(
                    exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.COOKIE));
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(HttpHeaders.COOKIE);
                        if (backendCookies != null) {
                            headers.set(HttpHeaders.COOKIE, backendCookies);
                        }
                    })
                    .build();

            // The backend's headers are copied into the response before the body is written, so rewrite them
            // just before the response is committed.
            ServerHttpResponse response = exchange.getResponse();
            response.beforeCommit(() -> {
                List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
                if (setCookies != null && !setCookies.isEmpty()) {
                    List<String> rewritten = rewriter.toBrowserSetCookies(setCookies);
                    response.getHeaders().remove(HttpHeaders.SET_COOKIE);
                    response.getHeaders().addAll(HttpHeaders.SET_COOKIE, rewritten);
                }
                return Mono.empty();
            });

            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    public static class Config {

        private String prefix;
        private String path;

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
