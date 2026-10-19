package com.offershield.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness of the API path through Caddy; also proves request-ID and AOP log lines (M1). */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("up");
    }
}
