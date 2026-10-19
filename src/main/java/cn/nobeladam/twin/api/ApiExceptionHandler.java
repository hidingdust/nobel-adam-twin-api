package cn.nobeladam.twin.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.nobeladam.twin.error.AiException;
import cn.nobeladam.twin.error.ConflictException;
import cn.nobeladam.twin.error.RateLimitException;
import cn.nobeladam.twin.error.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 直接写 HttpServletResponse，避免 SSE 请求（Accept: text/event-stream）
 * 在异常时被内容协商卡住，导致 429/409 之类的 JSON 错误发不出去。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(RateLimitException.class)
    public void handleRateLimit(RateLimitException e, HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", String.valueOf(e.retryAfterSeconds()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RATE_LIMITED");
        body.put("message", e.getMessage());
        body.put("retryAfter", e.retryAfterSeconds());
        body.put("scope", e.scope());
        write(response, HttpStatus.TOO_MANY_REQUESTS.value(), body);
    }

    @ExceptionHandler(ConflictException.class)
    public void handleConflict(ConflictException e, HttpServletResponse response) throws IOException {
        write(response, HttpStatus.CONFLICT.value(),
                Map.of("code", "IN_PROGRESS", "message", e.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public void handleUnavailable(ServiceUnavailableException e, HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "NOT_READY");
        body.put("message", "分身还没准备好，稍后再试。");
        body.put("detail", e.getMessage());
        write(response, HttpStatus.SERVICE_UNAVAILABLE.value(), body);
    }

    @ExceptionHandler(AiException.class)
    public void handleAi(AiException e, HttpServletResponse response) throws IOException {
        log.warn("AI 上游错误：status={}, code={}", e.upstreamStatus(), e.upstreamCode());
        write(response, HttpStatus.BAD_GATEWAY.value(),
                Map.of("code", "AI_UNAVAILABLE", "message", "分身暂时没接上模型，稍后再试。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) throws IOException {
        FieldError fieldError = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        write(response, HttpStatus.BAD_REQUEST.value(),
                Map.of("code", "BAD_REQUEST", "message", message == null ? "请求参数不合法" : message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleUnreadable(HttpMessageNotReadableException e, HttpServletResponse response) throws IOException {
        write(response, HttpStatus.BAD_REQUEST.value(),
                Map.of("code", "BAD_REQUEST", "message", "请求体不是合法的 JSON"));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnected(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开：{}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception e, HttpServletResponse response) throws IOException {
        log.error("未处理异常", e);
        if (response.isCommitted()) {
            return;
        }
        write(response, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Map.of("code", "INTERNAL_ERROR", "message", "服务器刚才走神了，稍后再试。"));
    }

    private void write(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
