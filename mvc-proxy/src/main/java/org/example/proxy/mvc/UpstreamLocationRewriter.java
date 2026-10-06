package org.example.proxy.mvc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Keeps redirects on the proxy: a {@code Location} header pointing at the upstream itself (same scheme, host and
 * port) is turned into a relative URL ({@code /path?query#fragment}), which the browser resolves against the
 * proxy's address. Relative locations and redirects to other sites are left untouched.
 * <p>
 * Unlike Spring Cloud Gateway's {@code RewriteLocationResponseHeader} filter, which rewrites the host of any absolute
 * location, this does not break redirects to external sites (e.g. an identity provider).
 * <p>
 * Plain Java, deliberately duplicated in webflux-proxy: keep both copies in sync.
 */
public final class UpstreamLocationRewriter {

    private UpstreamLocationRewriter() {
    }

    /**
     * @param location the {@code Location} header received from the upstream (may be {@code null})
     * @param upstream the URI the request was proxied to (only scheme, host and port are used)
     * @return the location to send to the browser
     */
    public static String toProxyLocation(String location, URI upstream) {
        if (location == null || upstream == null) {
            return location;
        }
        URI uri;
        try {
            uri = new URI(location.trim());
        } catch (URISyntaxException e) {
            return location;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || !sameOrigin(uri, upstream)) {
            return location;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        StringBuilder relative = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            relative.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            relative.append('#').append(uri.getRawFragment());
        }
        return relative.toString();
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(String.valueOf(b.getScheme()))
                && a.getHost().equalsIgnoreCase(String.valueOf(b.getHost()))
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return switch (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            default -> -1;
        };
    }
}
