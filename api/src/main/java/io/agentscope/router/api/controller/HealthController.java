package io.agentscope.router.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Liveness endpoint — does NOT touch the LLM providers. Used for K8s/load-balancer
 * health probes and the M1 smoke test.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "agentscope-router",
                "timestamp", Instant.now().toString()
        );
    }
}
