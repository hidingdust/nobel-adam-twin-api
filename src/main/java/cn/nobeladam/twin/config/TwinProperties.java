package cn.nobeladam.twin.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "twin")
public record TwinProperties(
        Path knowledgeDir,
        Ai ai,
        Retrieval retrieval,
        RateLimit rateLimit,
        Idempotency idempotency,
        Cors cors
) {

    public record Ai(
            String baseUrl,
            String apiKey,
            Chat chat,
            Embedding embedding,
            Rerank rerank
    ) {
        public record Chat(
                String model,
                double temperature,
                int maxTokens,
                boolean enableThinking
        ) {
        }

        public record Embedding(
                String model,
                int dimensions,
                int batchSize
        ) {
        }

        public record Rerank(
                boolean enabled,
                String baseUrl,
                String model,
                int maxDocs,
                int topN,
                boolean onlyWhenAmbiguous,
                double ambiguityGap
        ) {
        }
    }

    public record Retrieval(
            int topK,
            int candidateK,
            int maxPerProject,
            double similarityThreshold,
            double rerankThreshold,
            int maxContextChars,
            int maxChunkChars,
            int maxHistoryMessages,
            int maxQuestionChars
    ) {
    }

    public record RateLimit(
            int perMinute,
            int perDayPerIp,
            int globalPerDay
    ) {
    }

    public record Idempotency(Duration ttl) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
