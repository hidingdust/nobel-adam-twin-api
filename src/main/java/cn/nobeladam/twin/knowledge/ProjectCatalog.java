package cn.nobeladam.twin.knowledge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProjectCatalog {

    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        DISPLAY_NAMES.put("medireport", "医疗体检");
        DISPLAY_NAMES.put("ai-education", "AI 教育");
        DISPLAY_NAMES.put("rag-poc", "RAG POC");
        DISPLAY_NAMES.put("daily-news", "Daily News");
        DISPLAY_NAMES.put("sunset-hunter", "晚霞猎人");
        DISPLAY_NAMES.put("pulse-design", "PULSE");
        DISPLAY_NAMES.put("writing", "技术写作");

        ALIASES.put("medireport", List.of("体检", "medireport", "医疗", "报告解读", "mq", "死信", "幂等", "消息链路"));
        ALIASES.put("ai-education", List.of("课件", "教育", "动画", "多智能体", "agent", "ppt", "ai-education"));
        ALIASES.put("rag-poc", List.of("rag", "检索", "向量", "embedding", "召回", "knn", "elasticsearch", "rag-poc"));
        ALIASES.put("daily-news", List.of("日报", "资讯", "daily", "自动化", "推送", "微信", "daily-news"));
        ALIASES.put("sunset-hunter", List.of("晚霞", "sunset", "霞光", "评分", "分数", "天气", "sunset-hunter"));
        ALIASES.put("pulse-design", List.of("pulse", "php", "生命体", "3d", "小球", "服务器指标", "pulse-design"));
        ALIASES.put("writing", List.of("文章", "写作", "博客", "xxl", "笔记", "writing"));
    }

    private ProjectCatalog() {
    }

    public static String displayName(String project) {
        return DISPLAY_NAMES.getOrDefault(project, project == null ? "项目" : project);
    }

    /**
     * 命中一个项目时只检索该项目；同时命中多个项目时返回 null，走全库检索。
     */
    public static String detect(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalized.contains(alias)) {
                    matched.add(entry.getKey());
                    break;
                }
            }
        }

        return matched.size() == 1 ? matched.iterator().next() : null;
    }
}
