package io.mosip.liveness.api;

import io.mosip.liveness.app.config.AppConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> healthCheck() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "MOSIP Face Liveness & PAD Service");
        // Frame-processing endpoints return 503 when the native library is unavailable.
        body.put("engine", AppConfig.isOpenCvAvailable() ? "available" : "unavailable");
        return body;
    }
}
