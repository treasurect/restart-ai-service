package com.treasure.restart.ai.service;

import com.treasure.common.exception.BusinessException;
import com.treasure.restart.ai.dto.AiModelResponse;
import com.treasure.common.helper.LogHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmHttpService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public LlmHttpService(
            ObjectMapper objectMapper,
            @Value("${ai.service.base-url}") String baseUrl,
            @Value("${ai.service.timeout}") Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public String chat(List<Map<String, Object>> messages, Map<String, Object> options, String modelId, String authorization) {
        JsonNode response = webClient.post()
                .uri("/api/llm/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> setAuthorization(headers, authorization))
                .bodyValue(requestBody(messages, options, modelId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, result -> result.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(error -> new BusinessException("LLM 服务请求失败：" + extractError(error, result.statusCode().value()))))
                .bodyToMono(JsonNode.class)
                .blockOptional(timeout)
                .orElseThrow(() -> new BusinessException("LLM 服务返回空响应"));

        JsonNode content = response.get("content");
        if (content == null || content.isNull()) {
            throw new BusinessException("LLM 服务响应中缺少 content");
        }
        return content.asString();
    }

    public Flux<JsonNode> chatStream(List<Map<String, Object>> messages, Map<String, Object> options, String modelId, String authorization) {
        return webClient.post()
                .uri("/api/llm/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> setAuthorization(headers, authorization))
                .bodyValue(requestBody(messages, options, modelId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, result -> result.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(error -> new BusinessException("LLM 流式请求失败：" + extractError(error, result.statusCode().value()))))
                .bodyToFlux(String.class)
                .mapNotNull(this::parseEvent);
    }

    public List<AiModelResponse> models() {
        return webClient.get()
                .uri("/api/llm/models")
                .retrieve()
                .onStatus(HttpStatusCode::isError, result -> result.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(error -> new BusinessException("LLM 模型列表获取失败：" + extractError(error, result.statusCode().value()))))
                .bodyToMono(new ParameterizedTypeReference<List<AiModelResponse>>() {
                })
                .blockOptional(timeout)
                .orElseThrow(() -> new BusinessException("LLM 模型列表返回空响应"));
    }

    public Map<String, Object> textMessage(String role, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "TEXT");
        content.put("text", text);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("contents", List.of(content));
        return message;
    }

    private Map<String, Object> requestBody(List<Map<String, Object>> messages, Map<String, Object> options, String modelId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", modelId);
        body.put("messages", messages);
        if (options != null && !options.isEmpty()) {
            body.put("options", options);
        }
        return body;
    }

    private JsonNode parseEvent(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        String json = data.startsWith("data:") ? data.substring(5).trim() : data.trim();
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            LogHelper.error("解析 LLM SSE 数据失败：" + e.getMessage());
            return null;
        }
    }

    private void setAuthorization(HttpHeaders headers, String authorization) {
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private String extractError(String body, int status) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode msg = node.get("msg");
            if (msg != null && msg.isTextual() && !msg.asString().isBlank()) {
                return "HTTP " + status + "，" + msg.asString();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，直接展示原始内容
        }
        return "HTTP " + status + "，" + (body == null || body.isBlank() ? "未知错误" : body);
    }
}
