package cn.nobeladam.twin.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.nobeladam.twin.ai.AiClient;
import cn.nobeladam.twin.api.dto.ChatRequest;
import cn.nobeladam.twin.error.AiException;
import cn.nobeladam.twin.error.ConflictException;
import cn.nobeladam.twin.error.ServiceUnavailableException;
import cn.nobeladam.twin.idempotency.IdempotencyService;
import cn.nobeladam.twin.prompt.PromptService;
import cn.nobeladam.twin.ratelimit.RateLimitService;
import cn.nobeladam.twin.retrieval.RetrievalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = 90_000L;

    private final RateLimitService rateLimit;
    private final IdempotencyService idempotency;
    private final RetrievalService retrieval;
    private final PromptService promptService;
    private final AiClient aiClient;
    private final ExecutorService executor;

    public ChatController(
            RateLimitService rateLimit,
            IdempotencyService idempotency,
            RetrievalService retrieval,
            PromptService promptService,
            AiClient aiClient,
            @Qualifier("twinExecutor") ExecutorService executor
    ) {
        this.rateLimit = rateLimit;
        this.idempotency = idempotency;
        this.retrieval = retrieval;
        this.promptService = promptService;
        this.aiClient = aiClient;
        this.executor = executor;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        rateLimit.check(httpRequest.getRemoteAddr());

        IdempotencyService.BeginResult begin = idempotency.begin(request.requestId());
        if (begin.status() == IdempotencyService.BeginStatus.IN_PROGRESS) {
            throw new ConflictException("上一条还在回答，别急。");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onError(error -> log.debug("SSE 连接异常：{}", error.toString()));

        executor.execute(() -> {
            if (begin.status() == IdempotencyService.BeginStatus.COMPLETED) {
                replay(emitter, begin.cached());
                return;
            }
            process(emitter, request);
        });
        return emitter;
    }

    private void process(SseEmitter emitter, ChatRequest request) {
        try {
            RetrievalService.RetrievalResult result = retrieval.retrieve(request.message());
            send(emitter, "meta", Map.of(
                    "tags", result.tags(),
                    "sources", result.sources(),
                    "reranked", result.reranked()
            ));

            String systemPrompt = promptService.buildSystemPrompt(result);
            String userPrompt = promptService.buildUserPrompt(request.history(), request.message());
            StringBuilder answer = new StringBuilder();

            aiClient.streamChat(systemPrompt, userPrompt, delta -> {
                answer.append(delta);
                send(emitter, "delta", Map.of("text", delta));
            });

            idempotency.complete(request.requestId(), answer.toString(), result.tags(), result.sources());
            send(emitter, "done", Map.of("tags", result.tags(), "sources", result.sources()));
            emitter.complete();
        } catch (ClientGoneException e) {
            log.debug("客户端已断开：requestId={}", request.requestId());
        } catch (ServiceUnavailableException e) {
            idempotency.fail(request.requestId());
            log.warn("服务未就绪：requestId={}, reason={}", request.requestId(), e.getMessage());
            sendError(emitter, "NOT_READY", "分身还没准备好，稍后再试。");
            emitter.complete();
        } catch (AiException e) {
            idempotency.fail(request.requestId());
            log.warn("AI 调用失败：requestId={}, upstreamStatus={}, code={}",
                    request.requestId(), e.upstreamStatus(), e.upstreamCode());
            sendError(emitter, "AI_UNAVAILABLE", friendlyAiMessage(e));
            emitter.complete();
        } catch (Exception e) {
            idempotency.fail(request.requestId());
            log.error("对话处理失败：requestId={}", request.requestId(), e);
            sendError(emitter, "INTERNAL_ERROR", "分身刚才走神了，稍后再试。");
            emitter.complete();
        }
    }

    private void replay(SseEmitter emitter, IdempotencyService.CachedAnswer cached) {
        try {
            send(emitter, "meta", Map.of(
                    "tags", cached.tags(),
                    "sources", cached.sources(),
                    "replayed", true
            ));
            send(emitter, "delta", Map.of("text", cached.answer()));
            send(emitter, "done", Map.of("tags", cached.tags(), "sources", cached.sources()));
            emitter.complete();
        } catch (ClientGoneException e) {
            log.debug("回放时客户端已断开");
        }
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", Map.of("code", code, "message", message));
        } catch (ClientGoneException ignored) {
            // 客户端已经走了，不用再写
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }

    private static String friendlyAiMessage(AiException e) {
        if ("AI_KEY_MISSING".equals(e.upstreamCode())) {
            return "分身还没配置好模型，稍后再试。";
        }
        if ("AllocationQuota.FreeTierOnly".equals(e.upstreamCode())) {
            return "今天的模型额度用完了，晚点再来找我。";
        }
        return "分身暂时没接上模型，稍后再试。";
    }

    private static final class ClientGoneException extends RuntimeException {
        private ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
