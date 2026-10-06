package org.example.proxy.mvc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyCookieRewriterTest {

    private final ProxyCookieRewriter rewriter = new ProxyCookieRewriter("PX_", "/path1");

    // --- browser -> backend ---

    @Test
    void forwardsOnlyPrefixedCookiesWithPrefixRemoved() {
        String header = "JSESSIONID=main-1; PX_JSESSIONID=backend-1; MAIN_PREF=dark; PX_BACKEND_PREF=compact";
        assertEquals("JSESSIONID=backend-1; BACKEND_PREF=compact", rewriter.toBackendCookieHeader(List.of(header)));
    }

    @Test
    void mergesMultipleCookieHeaders() {
        assertEquals("a=1; b=2", rewriter.toBackendCookieHeader(List.of("PX_a=1; x=9", "PX_b=2")));
    }

    @Test
    void returnsNullWhenNoBackendCookies() {
        assertNull(rewriter.toBackendCookieHeader(List.of("JSESSIONID=main-1; MAIN_PREF=dark")));
        assertNull(rewriter.toBackendCookieHeader(List.of()));
    }

    @Test
    void keepsValuesContainingEqualsSigns() {
        assertEquals("token=abc==", rewriter.toBackendCookieHeader(List.of("PX_token=abc==")));
    }

    @Test
    void ignoresCookiesThatAreOnlyThePrefix() {
        assertNull(rewriter.toBackendCookieHeader(List.of("PX_=x; PX_")));
    }

    @Test
    void prefixMatchIsCaseSensitive() {
        assertNull(rewriter.toBackendCookieHeader(List.of("px_a=1")));
    }

    // --- backend -> browser ---

    @Test
    void prefixesNameAndConfinesRootPath() {
        assertEquals("PX_JSESSIONID=abc; Path=/path1; HttpOnly; SameSite=Lax",
                rewriter.toBrowserSetCookie("JSESSIONID=abc; Path=/; HttpOnly; SameSite=Lax"));
    }

    @Test
    void keepsPathAlreadyInsideCookiePath() {
        assertEquals("PX_a=1; Path=/path1/api", rewriter.toBrowserSetCookie("a=1; Path=/path1/api"));
        assertEquals("PX_a=1; Path=/path1", rewriter.toBrowserSetCookie("a=1; Path=/path1"));
    }

    @Test
    void rewritesSiblingPathThatOnlySharesTheTextPrefix() {
        assertEquals("PX_a=1; Path=/path1", rewriter.toBrowserSetCookie("a=1; Path=/path10"));
    }

    @Test
    void addsPathWhenMissing() {
        assertEquals("PX_a=1; Max-Age=60; Path=/path1", rewriter.toBrowserSetCookie("a=1; Max-Age=60"));
    }

    @Test
    void dropsDomainCaseInsensitively() {
        assertEquals("PX_a=1; Path=/path1; Secure", rewriter.toBrowserSetCookie("a=1; domain=backend.local; Path=/; Secure"));
    }

    @Test
    void preservesExpiresWithComma() {
        assertEquals("PX_a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/path1",
                rewriter.toBrowserSetCookie("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"));
    }

    @Test
    void preservesDeletionCookie() {
        assertEquals("PX_a=; Max-Age=0; Path=/path1", rewriter.toBrowserSetCookie("a=; Max-Age=0; Path=/"));
    }

    @Test
    void dropsMalformedSetCookie() {
        assertNull(rewriter.toBrowserSetCookie("no-equals-sign; Path=/"));
        assertNull(rewriter.toBrowserSetCookie("=value"));
        assertEquals(List.of("PX_a=1; Path=/path1"), rewriter.toBrowserSetCookies(List.of("bad", "a=1")));
    }

    @Test
    void roundTripRestoresOriginalNameAndValue() {
        String toBrowser = rewriter.toBrowserSetCookie("SESSION=xyz; Path=/; HttpOnly");
        String browserSends = toBrowser.substring(0, toBrowser.indexOf(';'));
        assertEquals("SESSION=xyz", rewriter.toBackendCookieHeader(List.of("OTHER=1; " + browserSends)));
    }

    @Test
    void normalisesTrailingSlashInCookiePath() {
        assertEquals("/path1", new ProxyCookieRewriter("PX_", "/path1/").cookiePath());
        assertEquals("/", new ProxyCookieRewriter("PX_", "/").cookiePath());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ProxyCookieRewriter(" ", "/path1"));
        assertThrows(IllegalArgumentException.class, () -> new ProxyCookieRewriter("PX_", "path1"));
    }
}
