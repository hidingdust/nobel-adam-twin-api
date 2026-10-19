package cn.nobeladam.twin.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.nobeladam.twin.ai.AiClient;
import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.error.ServiceUnavailableException;
import cn.nobeladam.twin.knowledge.KnowledgeIndex;
import cn.nobeladam.twin.knowledge.ProjectCatalog;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final Pattern BOUNDARY = Pattern.compile(
            "公司名|哪家公司|公司叫什么|客户名|内网|薪资|工资|offer|到岗|身份证|住址|家庭成员|前公司|前同事",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERVIEW = Pattern.compile(
            "做过哪些项目|做过什么项目|几个项目|项目经历|介绍一下你自己|你做过什么|项目清单");

    private final KnowledgeIndex index;
    private final AiClient ai;
    private final TwinProperties.Retrieval properties;
    private final TwinProperties.Ai.Rerank rerankProperties;

    public RetrievalService(KnowledgeIndex index, AiClient ai, TwinProperties twinProperties) {
        this.index = index;
        this.ai = ai;
        this.properties = twinProperties.retrieval();
        this.rerankProperties = twinProperties.ai().rerank();
    }

    public RetrievalResult retrieve(String question) {
        if (!index.ready()) {
            throw new ServiceUnavailableException(index.status());
        }
        if (question != null && BOUNDARY.matcher(question).find()) {
            return new RetrievalResult(List.of(), List.of(), List.of(), false);
        }

        float[] query = ai.embed(question);
        normalize(query);
        String projectFilter = ProjectCatalog.detect(question);

        List<ScoredChunk> candidates = new ArrayList<>();
        for (KnowledgeIndex.Chunk chunk : index.chunks()) {
            if (projectFilter != null && !projectFilter.equals(chunk.project())) {
                continue;
            }
            double score = dot(query, chunk.embedding());
            candidates.add(new ScoredChunk(chunk, score));
        }

        candidates.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        candidates = candidates.stream().limit(Math.max(properties.candidateK(), properties.topK())).toList();

        boolean reranked = false;
        double threshold = properties.similarityThreshold();
        List<ScoredChunk> ordered = candidates;

        if (shouldRerank(candidates)) {
            try {
                List<String> documents = candidates.stream()
                        .limit(properties.topK() + 4)
                        .map(candidate -> truncate(candidate.chunk().text(), Math.min(properties.maxChunkChars(), 700)))
                        .toList();
                List<AiClient.RerankResult> rerank = ai.rerank(question, documents, properties.topK() + 2);
                List<ScoredChunk> rerankedChunks = new ArrayList<>();
                for (AiClient.RerankResult result : rerank) {
                    if (result.index() >= 0 && result.index() < candidates.size()) {
                        rerankedChunks.add(new ScoredChunk(candidates.get(result.index()).chunk(), result.score()));
                    }
                }
                if (!rerankedChunks.isEmpty()) {
                    reranked = true;
                    threshold = properties.rerankThreshold();
                    ordered = rerankedChunks;
                }
            } catch (Exception e) {
                log.warn("Rerank 失败，回退到向量排序：{}", e.toString());
            }
        }

        List<ScoredChunk> selected = select(ordered, threshold, projectFilter);
        List<String> tags = buildTags(selected, projectFilter, question);
        List<Source> sources = selected.stream()
                .map(item -> new Source(
                        item.chunk().project(),
                        item.chunk().docTitle(),
                        item.chunk().h2()))
                .toList();

        return new RetrievalResult(selected, tags, sources, reranked);
    }

    private boolean shouldRerank(List<ScoredChunk> candidates) {
        if (!ai.rerankEnabled() || candidates.size() < 2) {
            return false;
        }
        if (!rerankProperties.onlyWhenAmbiguous()) {
            return true;
        }
        double gap = candidates.get(0).score() - candidates.get(1).score();
        return gap < rerankProperties.ambiguityGap();
    }

    private List<ScoredChunk> select(List<ScoredChunk> ordered, double threshold, String projectFilter) {
        List<ScoredChunk> selected = new ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        for (ScoredChunk candidate : ordered) {
            if (candidate.score() < threshold) {
                continue;
            }
            String project = candidate.chunk().project();
            int count = counts.getOrDefault(project, 0);
            if (projectFilter == null && count >= properties.maxPerProject()) {
                continue;
            }
            selected.add(candidate);
            counts.put(project, count + 1);
            if (selected.size() >= properties.topK()) {
                break;
            }
        }
        return selected;
    }

    private List<String> buildTags(List<ScoredChunk> selected, String projectFilter, String question) {
        if (selected.isEmpty()) {
            return List.of();
        }
        if (question != null && OVERVIEW.matcher(question).find()) {
            return List.of("项目档案");
        }

        Set<String> tags = new LinkedHashSet<>();
        if (projectFilter != null) {
            tags.add(ProjectCatalog.displayName(projectFilter));
            String topic = topicTag(question);
            if (topic != null) {
                tags.add(topic);
            } else {
                String h2 = selected.get(0).chunk().h2();
                if (!h2.isBlank() && !GENERIC_TITLES.contains(h2)) {
                    tags.add(truncate(h2, 16));
                }
            }
            return List.copyOf(tags);
        }

        for (ScoredChunk item : selected) {
            tags.add(ProjectCatalog.displayName(item.chunk().project()));
            if (tags.size() >= 3) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    private static final Set<String> GENERIC_TITLES = Set.of(
            "我做了什么", "一句话", "项目卡", "项目概览", "这个项目是什么，不是什么"
    );

    private static String topicTag(String question) {
        if (question == null) {
            return null;
        }
        String q = question.toLowerCase(java.util.Locale.ROOT);
        if (q.contains("评分") || q.contains("分数") || q.contains("算法")) {
            return "评分算法";
        }
        if (q.contains("mq") || q.contains("消息") || q.contains("死信") || q.contains("幂等")) {
            return "消息链路";
        }
        if (q.contains("rag") || q.contains("检索") || q.contains("向量") || q.contains("召回")) {
            return "向量检索";
        }
        if (q.contains("架构") || q.contains("模块") || q.contains("链路")) {
            return "架构";
        }
        if (q.contains("难点") || q.contains("坑") || q.contains("排查")) {
            return "难点排查";
        }
        if (q.contains("负责") || q.contains("角色") || q.contains("哪部分")) {
            return "职责边界";
        }
        if (q.contains("部署") || q.contains("docker") || q.contains("服务器")) {
            return "部署";
        }
        if (q.contains("项目") || q.contains("做过") || q.contains("介绍") || q.contains("什么")) {
            return "项目概览";
        }
        return null;
    }

    private static double dot(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (double) left[i] * right[i];
        }
        return sum;
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        if (sum <= 0) {
            throw new ServiceUnavailableException("问题向量为空");
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public record ScoredChunk(KnowledgeIndex.Chunk chunk, double score) {
    }

    public record Source(String project, String docTitle, String h2) {
    }

    public record RetrievalResult(
            List<ScoredChunk> chunks,
            List<String> tags,
            List<Source> sources,
            boolean reranked
    ) {
    }
}
