package org.example.echo;

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
 * Backend cookies. They are set with {@code Path=/} on purpose: behind a proxy, the proxy renames them
 * ({@code PX_...}) and confines them to the proxied path.
 */
@RestController
@RequestMapping("/path1/api/cookies")
public class CookieController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> setCookies() {
        String session = "backend-" + UUID.randomUUID().toString().substring(0, 8);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        ResponseCookie.from("JSESSIONID", session).path("/").httpOnly(true).sameSite("Lax").build().toString(),
                        ResponseCookie.from("BACKEND_PREF", "compact").path("/").sameSite("Lax").build().toString())
                .body(Map.of("servedBy", "echo-server", "set", Map.of("JSESSIONID", session, "BACKEND_PREF", "compact")));
    }

    @GetMapping
    public Map<String, Object> receivedCookies(@RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return Map.of("servedBy", "echo-server", "receivedCookieHeader", cookie == null ? "(none)" : cookie);
    }
}
