package org.example.echo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain HTTP endpoints living under the same /path1 prefix as the WebSocket endpoint (/path1/ws).
 */
@RestController
@RequestMapping("/path1/api")
public class EchoRestController {

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
        return Map.of("message", "Hello, " + name + "!", "servedBy", "echo-server", "time", Instant.now().toString());
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        return Map.of("echo", body, "servedBy", "echo-server", "time", Instant.now().toString());
    }

    /** Shows what the backend actually received - useful to see the proxy's effect (Host, X-Forwarded-*). */
    @GetMapping("/request-info")
    public Map<String, Object> requestInfo(HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("servedBy", "echo-server");
        info.put("method", request.getMethod());
        info.put("requestUrl", request.getRequestURL().toString());
        info.put("remoteAddr", request.getRemoteAddr());
        info.put("headers", headers.toSingleValueMap());
        return info;
    }
}
