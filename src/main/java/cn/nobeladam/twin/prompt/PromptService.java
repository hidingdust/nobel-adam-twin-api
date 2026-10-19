package cn.nobeladam.twin.prompt;

import java.util.List;

import org.springframework.stereotype.Service;

import cn.nobeladam.twin.api.dto.ChatTurn;
import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.knowledge.KnowledgeIndex;
import cn.nobeladam.twin.knowledge.ProjectCatalog;
import cn.nobeladam.twin.retrieval.RetrievalService;

@Service
public class PromptService {

    private final KnowledgeIndex index;
    private final TwinProperties.Retrieval properties;

    public PromptService(KnowledgeIndex index, TwinProperties twinProperties) {
        this.index = index;
        this.properties = twinProperties.retrieval();
    }

    public String buildSystemPrompt(RetrievalService.RetrievalResult retrieval) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(index.persona()).append("\n\n");
        prompt.append(index.indexDoc()).append("\n\n");
        prompt.append("""
                下面是这次从我的项目笔记里检索到的资料。回答时优先使用这些资料；
                资料没有覆盖的内容，按上面的边界处理，不要编造。
                不要提到“资料”“文档”“知识库”“检索”这些词，用第一人称直接回答。
                默认 3~5 句，先给结论；只有对方明确要求“详细讲讲”才展开。
                """);

        if (retrieval.chunks().isEmpty()) {
            prompt.append("\n这次没有检索到相关资料。直接说这块没整理过，或请对方换个问法，不要硬答。\n");
            return prompt.toString();
        }

        prompt.append("\n【检索资料】\n");
        int remaining = properties.maxContextChars();
        int number = 1;
        for (RetrievalService.ScoredChunk scored : retrieval.chunks()) {
            KnowledgeIndex.Chunk chunk = scored.chunk();
            String header = "【" + ProjectCatalog.displayName(chunk.project())
                    + " · " + chunk.docTitle()
                    + (chunk.h2().isBlank() ? "" : " · " + chunk.h2())
                    + (chunk.h3().isBlank() ? "" : " · " + chunk.h3())
                    + "】\n";
            int allowed = Math.min(properties.maxChunkChars(), remaining);
            if (allowed <= 0) {
                break;
            }
            String body = truncate(chunk.text(), allowed);
            if (body.isBlank()) {
                break;
            }
            prompt.append("\n资料 ").append(number++).append("\n").append(header).append(body).append("\n");
            remaining -= body.length();
            if (remaining <= 0) {
                break;
            }
        }
        return prompt.toString();
    }

    public String buildUserPrompt(List<ChatTurn> history, String message) {
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            List<ChatTurn> recent = history.size() > properties.maxHistoryMessages()
                    ? history.subList(history.size() - properties.maxHistoryMessages(), history.size())
                    : history;
            prompt.append("最近对话：\n");
            for (ChatTurn turn : recent) {
                String role = "assistant".equals(turn.role()) ? "我" : "访客";
                prompt.append(role).append("：").append(truncate(turn.content(), 1000)).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("当前问题：").append(message == null ? "" : message.strip());
        return prompt.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }
}
