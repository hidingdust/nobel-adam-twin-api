package cn.nobeladam.twin.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.error.AiException;

@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private static final String PLACEHOLDER_KEY = "sk-not-configured";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final TwinProperties.Ai properties;

    public AiClient(HttpClient httpClient, ObjectMapper mapper, TwinProperties twinProperties) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.properties = twinProperties.ai();
    }

    public boolean configured() {
        String key = properties.apiKey();
        return key != null && !key.isBlank() && !PLACEHOLDER_KEY.equals(key);
    }

    public String chatModel() {
        return properties.chat().model();
    }

    public String embeddingModel() {
        return properties.embedding().model();
    }

    public int embeddingDimensions() {
        return properties.embedding().dimensions();
    }

    public boolean rerankEnabled() {
        return properties.rerank() != null && properties.rerank().enabled();
    }

    public float[] embed(String text) {
        List<float[]> vectors = embedBatch(List.of(text));
        return vectors.getFirst();
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (!configured()) {
            throw new AiException("AI Key 未配置", 503, "AI_KEY_MISSING");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", properties.embedding().model());
        ArrayNode input = payload.putArray("input");
        texts.forEach(input::add);
        payload.put("dimensions", properties.embedding().dimensions());
        payload.put("encoding_format", "float");

        JsonNode response = postJson(properties.baseUrl() + "/embeddings", payload, false);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new AiException("Embedding 返回为空", 502, "EMBEDDING_EMPTY");
        }

        List<float[]> result = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            result.add(null);
        }

        for (int itemIndex = 0; itemIndex < data.size(); itemIndex++) {
            JsonNode item = data.get(itemIndex);
            int index = item.path("index").asInt(itemIndex);
            JsonNode vectorNode = item.path("embedding");
            if (!vectorNode.isArray() || vectorNode.size() != properties.embedding().dimensions()) {
                throw new AiException("Embedding 维度与配置不一致", 502, "EMBEDDING_DIMENSION_MISMATCH");
            }
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            if (index >= 0 && index < result.size()) {
                result.set(index, vector);
            }
        }

        if (result.stream().anyMatch(vector -> vector == null)) {
            throw new AiException("Embedding 返回不完整", 502, "EMBEDDING_INCOMPLETE");
        }
        return result;
    }

    /**
     * 调用 OpenAI 兼容的流式接口，把每个文本增量交给 onDelta。
     *
     * @return 完整回答
     */
    public String streamChat(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (!configured()) {
            throw new AiException("AI Key 未配置", 503, "AI_KEY_MISSING");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", properties.chat().model());
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        payload.put("temperature", properties.chat().temperature());
        payload.put("max_tokens", properties.chat().maxTokens());
        payload.put("stream", true);
        if (!properties.chat().enableThinking()) {
            payload.put("enable_thinking", false);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(100))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(payload)))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                if (response.statusCode() != 200) {
                    throw fromError(response.statusCode(), readAll(reader));
                }

                StringBuilder answer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode event = mapper.readTree(data);
                    JsonNode choices = event.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = choices.get(0).path("delta");
                    String text = delta.path("content").asText("");
                    if (text.isEmpty() && choices.get(0).has("message")) {
                        text = choices.get(0).path("message").path("content").asText("");
                    }
                    if (!text.isEmpty()) {
                        answer.append(text);
                        onDelta.accept(text);
                    }
                }

                if (answer.isEmpty()) {
                    throw new AiException("模型没有返回内容", 502, "CHAT_EMPTY");
                }
                return answer.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("模型调用被中断", 502, "CHAT_INTERRUPTED");
        } catch (IOException e) {
            log.warn("Chat 请求失败：{}", e.toString());
            throw new AiException("模型服务连接失败", 502, "CHAT_IO_ERROR");
        }
    }

    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (!configured()) {
            throw new AiException("AI Key 未配置", 503, "AI_KEY_MISSING");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", properties.rerank().model());
        ObjectNode input = payload.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        documents.forEach(docs::add);
        ObjectNode parameters = payload.putObject("parameters");
        parameters.put("return_documents", false);
        parameters.put("top_n", topN);

        String baseUrl = trimTrailingSlash(properties.rerank().baseUrl());
        JsonNode response = postJson(baseUrl + "/services/rerank/text-rerank/text-rerank", payload, false);
        JsonNode results = response.path("output").path("results");
        if (!results.isArray()) {
            throw new AiException("Rerank 返回为空", 502, "RERANK_EMPTY");
        }

        List<RerankResult> ranked = new ArrayList<>();
        for (JsonNode item : results) {
            ranked.add(new RerankResult(item.path("index").asInt(-1), item.path("relevance_score").asDouble(0)));
        }
        return ranked;
    }

    private JsonNode postJson(String url, ObjectNode payload, boolean stream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json");

        if (stream) {
            builder.header("Accept", "text/event-stream");
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(payload))).build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw fromError(response.statusCode(), response.body());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("模型调用被中断", 502, "AI_INTERRUPTED");
        } catch (IOException e) {
            log.warn("AI 请求失败：{}", e.toString());
            throw new AiException("模型服务连接失败", 502, "AI_IO_ERROR");
        }
    }

    private AiException fromError(int status, String body) {
        String code = "AI_UPSTREAM_ERROR";
        String message = "模型服务暂时不可用";
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode error = node.path("error");
            code = error.path("code").asText(node.path("code").asText(code));
            message = error.path("message").asText(node.path("message").asText(message));
        } catch (Exception ignored) {
            // 保留默认文案
        }
        log.warn("AI 上游错误：status={}, code={}, message={}", status, code, message);
        return new AiException(message, status, code);
    }

    private byte[] toBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static String readAll(BufferedReader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        return builder.toString();
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record RerankResult(int index, double score) {
    }
}
