package org.example.proxy.webflux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the backend's cookies separate from the cookies of the application hosting the proxy.
 * <p>
 * The browser sees a single host (the proxy), so it stores both applications' cookies in one jar. To keep them apart:
 * <ul>
 *     <li><b>Backend to browser</b> ({@code Set-Cookie}): the cookie is renamed to {@code prefix + name}, its
 *     {@code Domain} is dropped and its {@code Path} is confined to {@code cookiePath}, so the browser only sends
 *     it back on proxied requests.</li>
 *     <li><b>Browser to backend</b> ({@code Cookie}): only cookies carrying the prefix are forwarded, with the prefix
 *     removed. All other cookies (the hosting application's) are dropped.</li>
 * </ul>
 * The backend therefore never sees the prefix, and same-named cookies (e.g. two {@code JSESSIONID}s) no longer collide.
 * <p>
 * Plain Java, deliberately duplicated in mvc-proxy: keep both copies in sync.
 */
public final class ProxyCookieRewriter {

    private final String prefix;
    private final String cookiePath;

    /**
     * @param prefix     prefix marking the backend's cookies in the browser, e.g. {@code PX_}
     * @param cookiePath path the backend's cookies are confined to, e.g. {@code /path1}
     */
    public ProxyCookieRewriter(String prefix, String cookiePath) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Cookie prefix must not be blank");
        }
        if (cookiePath == null || !cookiePath.startsWith("/")) {
            throw new IllegalArgumentException("Cookie path must start with '/': " + cookiePath);
        }
        this.prefix = prefix;
        this.cookiePath = cookiePath.length() > 1 && cookiePath.endsWith("/")
                ? cookiePath.substring(0, cookiePath.length() - 1)
                : cookiePath;
    }

    public String prefix() {
        return prefix;
    }

    public String cookiePath() {
        return cookiePath;
    }

    /**
     * Builds the {@code Cookie} header to send to the backend from the browser's {@code Cookie} header(s).
     *
     * @return the filtered header value, or {@code null} if no backend cookie is present (omit the header)
     */
    public String toBackendCookieHeader(List<String> browserCookieHeaders) {
        List<String> forwarded = new ArrayList<>();
        for (String header : browserCookieHeaders) {
            for (String pair : header.split(";")) {
                String cookie = pair.trim();
                if (cookie.startsWith(prefix) && cookie.indexOf('=') > prefix.length()) {
                    forwarded.add(cookie.substring(prefix.length()));
                }
            }
        }
        return forwarded.isEmpty() ? null : String.join("; ", forwarded);
    }

    /** Rewrites all {@code Set-Cookie} values received from the backend; malformed ones are dropped. */
    public List<String> toBrowserSetCookies(List<String> backendSetCookies) {
        List<String> rewritten = new ArrayList<>(backendSetCookies.size());
        for (String setCookie : backendSetCookies) {
            String value = toBrowserSetCookie(setCookie);
            if (value != null) {
                rewritten.add(value);
            }
        }
        return rewritten;
    }

    /**
     * Rewrites one {@code Set-Cookie} value received from the backend.
     *
     * @return the value to send to the browser, or {@code null} if it is malformed and should be dropped
     */
    public String toBrowserSetCookie(String backendSetCookie) {
        String[] parts = backendSetCookie.split(";");
        String nameValue = parts[0].trim();
        if (nameValue.indexOf('=') <= 0) {
            return null;
        }
        StringBuilder result = new StringBuilder(prefix).append(nameValue);
        boolean hasPath = false;
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim();
            if (attribute.isEmpty()) {
                continue;
            }
            int eq = attribute.indexOf('=');
            String attributeName = (eq < 0 ? attribute : attribute.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            if (attributeName.equals("domain")) {
                // The backend's domain is meaningless to the browser; the cookie belongs to the proxy's host.
                continue;
            }
            if (attributeName.equals("path")) {
                hasPath = true;
                String path = eq < 0 ? "" : attribute.substring(eq + 1).trim();
                if (!isWithinCookiePath(path)) {
                    attribute = "Path=" + cookiePath;
                }
            }
            result.append("; ").append(attribute);
        }
        if (!hasPath) {
            result.append("; Path=").append(cookiePath);
        }
        return result.toString();
    }

    private boolean isWithinCookiePath(String path) {
        return path.equals(cookiePath)
                || path.startsWith(cookiePath.equals("/") ? "/" : cookiePath + "/");
    }
}
