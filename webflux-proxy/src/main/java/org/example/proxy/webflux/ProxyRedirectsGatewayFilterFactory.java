package org.example.proxy.webflux;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Route filter {@code ProxyRedirects}: redirects to the upstream's own address would send the browser around the
 * proxy, so such {@code Location} headers are made relative (see {@link UpstreamLocationRewriter}).
 */
@Component
public class ProxyRedirectsGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    public ProxyRedirectsGatewayFilterFactory() {
        super(Object.class);
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            ServerHttpResponse response = exchange.getResponse();
            response.beforeCommit(() -> {
                // The URL the request was actually sent to; set by the gateway before routing.
                URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                String rewritten = UpstreamLocationRewriter.toProxyLocation(location, upstream);
                if (location != null && !location.equals(rewritten)) {
                    response.getHeaders().set(HttpHeaders.LOCATION, rewritten);
                }
                return Mono.empty();
            });
            return chain.filter(exchange);
        };
    }
}
