package cn.nobeladam.twin.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.nobeladam.twin.ai.AiClient;
import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.knowledge.KnowledgeIndex;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final KnowledgeIndex index;
    private final AiClient ai;
    private final TwinProperties properties;

    public HealthController(KnowledgeIndex index, AiClient ai, TwinProperties properties) {
        this.index = index;
        this.ai = ai;
        this.properties = properties;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(payload(false));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean ready = isReady();
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(payload(!ready));
    }

    private Map<String, Object> payload(boolean includeStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", isReady() ? "ok" : "degraded");
        body.put("service", "nobel-adam-twin-api");

        Map<String, Object> indexInfo = new LinkedHashMap<>();
        indexInfo.put("ready", index.ready());
        indexInfo.put("chunks", index.manifest() == null ? 0 : index.manifest().chunks());
        indexInfo.put("dimensions", index.manifest() == null ? 0 : index.manifest().dimensions());
        indexInfo.put("embeddingModel", index.manifest() == null ? null : index.manifest().embeddingModel());
        indexInfo.put("promptsLoaded", !index.persona().isBlank() && !index.indexDoc().isBlank());
        indexInfo.put("detail", index.status());
        body.put("index", indexInfo);

        Map<String, Object> aiInfo = new LinkedHashMap<>();
        aiInfo.put("configured", ai.configured());
        aiInfo.put("chatModel", ai.chatModel());
        aiInfo.put("embeddingModel", ai.embeddingModel());
        aiInfo.put("embeddingDimensions", ai.embeddingDimensions());
        aiInfo.put("rerankEnabled", ai.rerankEnabled());
        body.put("ai", aiInfo);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("perMinute", properties.rateLimit().perMinute());
        limits.put("perDayPerIp", properties.rateLimit().perDayPerIp());
        limits.put("globalPerDay", properties.rateLimit().globalPerDay());
        body.put("limits", limits);

        if (includeStatus) {
            body.put("detail", index.status());
        }
        return body;
    }

    private boolean isReady() {
        return index.ready() && ai.configured();
    }
}
