package org.example.proxy.mvc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

/**
 * Applies {@link ProxyCookieRewriter} to the HTTP route: only prefixed cookies go to the backend (prefix removed),
 * and the backend's {@code Set-Cookie} headers are prefixed and confined to the proxied path.
 */
@Configuration
public class ProxyCookiesConfig {

    @Bean
    public ProxyCookieRewriter proxyCookieRewriter(ProxyProperties props) {
        return new ProxyCookieRewriter(props.cookies().prefix(), props.cookies().path());
    }

    static HandlerFilterFunction<ServerResponse, ServerResponse> proxyCookies(ProxyCookieRewriter rewriter) {
        return (request, next) -> {
            ServerResponse response = next.handle(withBackendCookies(request, rewriter));
            List<String> setCookies = response.headers().get(HttpHeaders.SET_COOKIE);
            if (setCookies != null && !setCookies.isEmpty()) {
                List<String> rewritten = rewriter.toBrowserSetCookies(setCookies);
                response.headers().remove(HttpHeaders.SET_COOKIE);
                response.headers().addAll(HttpHeaders.SET_COOKIE, rewritten);
            }
            return response;
        };
    }

    private static ServerRequest withBackendCookies(ServerRequest request, ProxyCookieRewriter rewriter) {
        String backendCookies = rewriter.toBackendCookieHeader(request.headers().header(HttpHeaders.COOKIE));
        return ServerRequest.from(request)
                .headers(headers -> {
                    headers.remove(HttpHeaders.COOKIE);
                    if (backendCookies != null) {
                        headers.set(HttpHeaders.COOKIE, backendCookies);
                    }
                })
                .build();
    }
}
