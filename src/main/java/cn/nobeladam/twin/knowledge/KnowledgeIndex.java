package cn.nobeladam.twin.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.nobeladam.twin.config.TwinProperties;
import jakarta.annotation.PostConstruct;

@Component
public class KnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);

    private final Path knowledgeDir;
    private final TwinProperties.Ai ai;
    private final ObjectMapper mapper;

    private volatile boolean ready;
    private volatile String status = "尚未加载";
    private volatile List<Chunk> chunks = List.of();
    private volatile Manifest manifest;
    private volatile String persona = "";
    private volatile String indexDoc = "";

    public KnowledgeIndex(TwinProperties properties, ObjectMapper mapper) {
        this.knowledgeDir = properties.knowledgeDir();
        this.ai = properties.ai();
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        Path indexDir = knowledgeDir.resolve("index");
        Path promptDir = knowledgeDir.resolve("system-prompt");
        Path chunksFile = indexDir.resolve("chunks.jsonl");
        Path embeddingsFile = indexDir.resolve("embeddings.jsonl");
        Path manifestFile = indexDir.resolve("manifest.json");
        Path personaFile = promptDir.resolve("_persona.md");
        Path indexFile = promptDir.resolve("_index.md");

        try {
            if (!Files.isRegularFile(chunksFile) || !Files.isRegularFile(embeddingsFile)
                    || !Files.isRegularFile(manifestFile) || !Files.isRegularFile(personaFile)
                    || !Files.isRegularFile(indexFile)) {
                fail("知识库文件不完整，请检查 " + knowledgeDir.toAbsolutePath());
                return;
            }

            Manifest loadedManifest = readManifest(manifestFile);
            if (!ai.embedding().model().equals(loadedManifest.embeddingModel())) {
                fail("索引模型不一致：索引=" + loadedManifest.embeddingModel()
                        + "，配置=" + ai.embedding().model() + "，请重新生成索引");
                return;
            }
            if (loadedManifest.dimensions() != ai.embedding().dimensions()) {
                fail("索引维度不一致：索引=" + loadedManifest.dimensions()
                        + "，配置=" + ai.embedding().dimensions());
                return;
            }

            Map<String, float[]> vectors = readEmbeddings(embeddingsFile, loadedManifest.dimensions());
            List<Chunk> loaded = readChunks(chunksFile, vectors, loadedManifest.chunks());

            if (loaded.isEmpty()) {
                fail("chunks.jsonl 为空");
                return;
            }

            String loadedPersona = Files.readString(personaFile, StandardCharsets.UTF_8);
            String loadedIndex = Files.readString(indexFile, StandardCharsets.UTF_8);
            int guideStart = loadedIndex.indexOf("## 检索指引");
            if (guideStart > 0) {
                loadedIndex = loadedIndex.substring(0, guideStart).strip();
            }

            this.manifest = loadedManifest;
            this.chunks = List.copyOf(loaded);
            this.persona = loadedPersona;
            this.indexDoc = loadedIndex;
            this.ready = true;
            this.status = "ready";
            log.info("知识索引加载完成：chunks={}, dimensions={}, embeddingModel={}, rerank={}",
                    chunks.size(), loadedManifest.dimensions(), loadedManifest.embeddingModel(), ai.rerank().enabled());
        } catch (Exception e) {
            fail("知识索引加载失败：" + e.getMessage());
            log.error("知识索引加载失败", e);
        }
    }

    private Manifest readManifest(Path file) throws IOException {
        JsonNode node = mapper.readTree(file.toFile());
        String model = node.path("embedding_model").asText(node.path("model").asText(""));
        int dimensions = node.path("dimensions").asInt(0);
        int count = node.path("chunks").asInt(0);
        return new Manifest(model, dimensions, count);
    }

    private Map<String, float[]> readEmbeddings(Path file, int dimensions) throws IOException {
        Map<String, float[]> vectors = new HashMap<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode node = mapper.readTree(line);
                    String id = node.path("chunk_id").asText("");
                    JsonNode vectorNode = node.path("embedding");
                    if (!id.isEmpty() && vectorNode.isArray() && vectorNode.size() == dimensions) {
                        float[] vector = new float[dimensions];
                        for (int i = 0; i < dimensions; i++) {
                            vector[i] = (float) vectorNode.get(i).asDouble();
                        }
                        normalize(vector);
                        vectors.put(id, vector);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("解析 embedding 失败：" + e.getMessage(), e);
                }
            });
        }
        return vectors;
    }

    private List<Chunk> readChunks(Path file, Map<String, float[]> vectors, int expectedCount) throws IOException {
        List<Chunk> loaded = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode node = mapper.readTree(line);
                    String id = node.path("chunk_id").asText("");
                    float[] vector = vectors.get(id);
                    if (id.isEmpty() || vector == null) {
                        throw new IllegalStateException("chunk 缺少 embedding：" + id);
                    }
                    JsonNode meta = node.path("meta");
                    List<String> tags = new ArrayList<>();
                    meta.path("tags").forEach(tag -> tags.add(tag.asText("")));
                    loaded.add(new Chunk(
                            id,
                            node.path("text").asText(""),
                            node.path("context").asText(""),
                            meta.path("project").asText(""),
                            meta.path("doc_title").asText(""),
                            meta.path("h2").asText(""),
                            meta.path("h3").asText(""),
                            meta.path("layer").asText(""),
                            List.copyOf(tags),
                            vector
                    ));
                } catch (Exception e) {
                    throw new IllegalStateException("解析 chunk 失败：" + e.getMessage(), e);
                }
            });
        }
        if (expectedCount > 0 && loaded.size() != expectedCount) {
            throw new IllegalStateException("chunk 数量与 manifest 不一致：chunks=" + loaded.size()
                    + "，manifest=" + expectedCount);
        }
        return loaded;
    }

    private void fail(String message) {
        this.ready = false;
        this.status = message;
        log.warn(message);
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 0) {
            throw new IllegalStateException("embedding 是零向量");
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    public boolean ready() {
        return ready;
    }

    public String status() {
        return status;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public String persona() {
        return persona;
    }

    public String indexDoc() {
        return indexDoc;
    }

    public Manifest manifest() {
        return manifest;
    }

    public record Chunk(
            String id,
            String text,
            String context,
            String project,
            String docTitle,
            String h2,
            String h3,
            String layer,
            List<String> tags,
            float[] embedding
    ) {
    }

    public record Manifest(String embeddingModel, int dimensions, int chunks) {
    }
}
