package org.example.proxy.mvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Stands in for the business logic hosted next to the proxy. It sets its own cookies, including a
 * {@code JSESSIONID} that would collide with the backend's without the {@code PX_} prefix.
 * None of these cookies are forwarded to the backend.
 */
@RestController
@RequestMapping("/main/cookies")
public class MainAppCookieController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> setCookies() {
        String session = "main-" + UUID.randomUUID().toString().substring(0, 8);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        ResponseCookie.from("JSESSIONID", session).path("/").httpOnly(true).sameSite("Lax").build().toString(),
                        ResponseCookie.from("MAIN_PREF", "dark").path("/").sameSite("Lax").build().toString())
                .body(Map.of("servedBy", "mvc-proxy (main app)", "set", Map.of("JSESSIONID", session, "MAIN_PREF", "dark")));
    }

    @GetMapping
    public Map<String, Object> receivedCookies(@RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return Map.of("servedBy", "mvc-proxy (main app)", "receivedCookieHeader", cookie == null ? "(none)" : cookie);
    }
}
