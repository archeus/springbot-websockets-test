package org.example.echo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Redirect variants, to check that a proxy keeps the browser on the proxy where it should. */
@RestController
@RequestMapping("/path1/api/redirect")
public class RedirectController {

    /**
     * 302 to an absolute URL built from the incoming request, as many frameworks do. Behind a proxy this is the
     * backend's own address (e.g. http://localhost:9081/...), which the proxy must rewrite.
     */
    @GetMapping("/absolute")
    public ResponseEntity<Void> absolute() {
        URI target = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/path1/api/hello").queryParam("name", "absolute-redirect").build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    /** 302 to a relative URL: already proxy-safe, must pass through unchanged. */
    @GetMapping("/relative")
    public ResponseEntity<Void> relative() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/path1/api/hello?name=relative-redirect")).build();
    }

    /** 302 to another site (think: identity provider): must pass through unchanged. */
    @GetMapping("/external")
    public ResponseEntity<Void> external() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("https://example.com/")).build();
    }
}
