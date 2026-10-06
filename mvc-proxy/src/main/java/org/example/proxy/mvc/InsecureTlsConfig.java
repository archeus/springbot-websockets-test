package org.example.proxy.mvc;

import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/**
 * DEV ONLY: accept any upstream TLS certificate (self-signed, expired, wrong host name) for both HTTP and WebSocket.
 * Enabled with {@code proxy.insecure-tls=true}.
 * <p>
 * A custom {@code HttpClient} bean is not enough: Gateway MVC only picks up a {@link ClientHttpRequestFactory}
 * bean (otherwise it builds its own client), and the WebSocket relay uses a separate client entirely.
 */
@Configuration
@ConditionalOnBooleanProperty("proxy.insecure-tls")
public class InsecureTlsConfig {

    private static final Logger log = LoggerFactory.getLogger(InsecureTlsConfig.class);

    /** Used by the WebSocket relay (see {@link WebSocketProxyConfig}). */
    @Bean
    public SSLContext insecureUpstreamSslContext() throws GeneralSecurityException {
        log.warn("proxy.insecure-tls=true: upstream TLS certificates and host names are NOT verified");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] {new TrustAllTrustManager()}, null);
        return sslContext;
    }

    /**
     * Picked up by Gateway MVC for HTTP proxying. Built through Boot's builder so the gateway's other client settings
     * (notably: do not follow redirects, pass them to the browser) still apply; only TLS is replaced.
     */
    @Bean
    public ClientHttpRequestFactory insecureGatewayClientHttpRequestFactory(
            SSLContext insecureUpstreamSslContext, ObjectProvider<HttpClientSettings> settings) {
        var tlsStrategy = new DefaultClientTlsStrategy(
                insecureUpstreamSslContext, HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withTlsSocketStrategyFactory(sslBundle -> tlsStrategy)
                .build(settings.getIfAvailable(HttpClientSettings::defaults));
    }

    /**
     * Accepts every certificate. It must extend {@link X509ExtendedTrustManager}: a plain {@code X509TrustManager}
     * gets wrapped by the JDK, which then still checks the host name and fails on a mismatch.
     */
    private static final class TrustAllTrustManager extends X509ExtendedTrustManager {

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
