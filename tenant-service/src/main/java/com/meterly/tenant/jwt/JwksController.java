package com.meterly.tenant.jwt;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public verification keys; validators cache the document for five minutes. */
@RestController
public class JwksController {

    static final Duration CACHE_MAX_AGE = Duration.ofMinutes(5);

    private final JwtKeyMaterial keys;

    public JwksController(JwtKeyMaterial keys) {
        this.keys = keys;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE))
                .body(keys.jwksDocument());
    }
}
