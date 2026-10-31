package org.creatordesk.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class HealthController {
    @GetMapping("/healthz")
    public Map<String, Object> health(HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        return Map.of("status", "alive", "scope", "process_only");
    }
}
