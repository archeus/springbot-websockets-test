package org.example.proxy.webflux;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpstreamLocationRewriterTest {

    private static final URI UPSTREAM = URI.create("http://localhost:9081");

    private static String rewrite(String location) {
        return UpstreamLocationRewriter.toProxyLocation(location, UPSTREAM);
    }

    @Test
    void upstreamAbsoluteLocationBecomesRelative() {
        assertEquals("/path1/api/hello?name=x", rewrite("http://localhost:9081/path1/api/hello?name=x"));
    }

    @Test
    void keepsEncodedPathQueryAndFragment() {
        assertEquals("/path1/a%20b?q=%2F&x=1#top", rewrite("http://localhost:9081/path1/a%20b?q=%2F&x=1#top"));
    }

    @Test
    void emptyPathBecomesRoot() {
        assertEquals("/", rewrite("http://localhost:9081"));
        assertEquals("/?a=1", rewrite("http://localhost:9081?a=1"));
    }

    @Test
    void schemeAndHostAreCaseInsensitive() {
        assertEquals("/x", rewrite("HTTP://LocalHost:9081/x"));
    }

    @Test
    void defaultPortsMatchExplicitPorts() {
        URI https = URI.create("https://backend.local");
        assertEquals("/x", UpstreamLocationRewriter.toProxyLocation("https://backend.local:443/x", https));
        assertEquals("/x", UpstreamLocationRewriter.toProxyLocation("https://backend.local/x",
                URI.create("https://backend.local:443")));
    }

    @Test
    void leavesRelativeLocationsAlone() {
        assertEquals("/path1/api/hello", rewrite("/path1/api/hello"));
        assertEquals("hello?x=1", rewrite("hello?x=1"));
        assertEquals("//other.example/x", rewrite("//other.example/x"));
    }

    @Test
    void leavesOtherOriginsAlone() {
        assertEquals("https://example.com/login", rewrite("https://example.com/login"));
        assertEquals("http://localhost:9999/x", rewrite("http://localhost:9999/x"));
        assertEquals("https://localhost:9081/x", rewrite("https://localhost:9081/x"));
        assertEquals("http://127.0.0.1:9081/x", rewrite("http://127.0.0.1:9081/x"));
    }

    @Test
    void leavesMalformedAndNonHttpLocationsAlone() {
        assertEquals("http://localhost:9081/a b", rewrite("http://localhost:9081/a b"));
        assertEquals("mailto:someone@example.com", rewrite("mailto:someone@example.com"));
    }

    @Test
    void handlesNulls() {
        assertNull(rewrite(null));
        assertEquals("http://localhost:9081/x", UpstreamLocationRewriter.toProxyLocation("http://localhost:9081/x", null));
    }
}
