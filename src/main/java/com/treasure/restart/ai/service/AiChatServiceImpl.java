package com.treasure.restart.ai.service;

import com.treasure.common.UserContext;
import com.treasure.common.exception.BusinessException;
import com.treasure.common.helper.LogHelper;
import com.treasure.restart.ai.config.AiMessageStatus;
import com.treasure.restart.ai.context.AiContextBuilder;
import com.treasure.restart.ai.context.AiStreamContextManager;
import com.treasure.restart.ai.dto.AiChatRequest;
import com.treasure.restart.ai.dto.AiChatResponse;
import com.treasure.restart.ai.dto.AiStreamEvent;
import com.treasure.restart.ai.entity.AiConversation;
import com.treasure.restart.ai.entity.AiMessage;
import com.treasure.restart.ai.mapper.AiConversationMapper;
import com.treasure.restart.ai.mapper.AiMessageMapper;
import jakarta.annotation.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiChatServiceImpl implements AiChatService {
    @Resource
    private AiConversationMapper aiConversationMapper;
    @Resource
    private AiMessageMapper aiMessageMapper;
    @Resource
    private AiContextBuilder aiContextBuilder;
    @Resource
    private AiSummaryService aiSummaryService;
    @Resource(name = "aiStreamExecutor")
    private ExecutorService executor;
    @Resource
    private AiMessageService aiMessageService;
    @Resource
    private LlmHttpService llmHttpService;

    private static final String SYSTEM_PROMPT = "你是一个友好、准确的 AI 助手，请使用清晰、实用的中文回答。";

    @Transactional
    public AiChatResponse chat(AiChatRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            if (request.conversationId() != null) throw new BusinessException("游客不能访问登录用户的会话");
            return chatAsGuest(request);
        } else {
            return chatAsUser(request);
        }
    }

    @Override
    @Transactional
    public AiChatResponse chatAsGuest(AiChatRequest request) {
        List<Map<String, Object>> maps = new ArrayList<>();
        maps.add(llmHttpService.textMessage("SYSTEM", SYSTEM_PROMPT));
        List<AiChatRequest.HistoryMsgItem> history = request.history();
        if (history != null && !history.isEmpty()) {
            if (history.size() >= 20) throw new BusinessException("游客历史消息不能超过20条");
            for (AiChatRequest.HistoryMsgItem item : history) {
                if (item == null || item.role() == null || item.content() == null || item.content().isBlank()) {
                    throw new BusinessException("历史消息格式不正确");
                }
                if ("user".equals(item.role())){
                    maps.add(llmHttpService.textMessage("USER", item.content()));
                } else if ("assistant".equals(item.role())){
                    maps.add(llmHttpService.textMessage("ASSISTANT", item.content()));
                } else throw new BusinessException("历史消息角色不合法");
            }
        }
        maps.add(llmHttpService.textMessage("USER", request.message()));
        String reply = llmHttpService.chat(maps, null, request.modelId(), currentAuthorization());
        return new AiChatResponse(null, reply);
    }

    @Override
    @Transactional
    public AiChatResponse chatAsUser(AiChatRequest request) {
        Long userId = UserContext.getUserId();
        AiConversation conversation;
        List<AiMessage> historyMessages = List.of();
        if (request.conversationId() == null) {
            conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setTitle(request.message());
            aiConversationMapper.insertConversation(conversation);
        } else {
            conversation = aiConversationMapper.getCurrentConversation(request.conversationId(), userId);
            if (conversation == null) throw new BusinessException("会话不存在或无权访问");
            historyMessages = aiMessageMapper.loadLatestCompletedMessages(conversation.getId(), 20);
        }
        
        List<Map<String, Object>> messages = aiContextBuilder.build(SYSTEM_PROMPT, historyMessages, request.message(), 6000);
        String reply = llmHttpService.chat(messages, null, request.modelId(), currentAuthorization());

        aiMessageService.saveMessage(UserContext.getUserId(), "user", conversation.getId(), request.message(), AiMessageStatus.COMPLETED);
        aiMessageService.saveMessage(UserContext.getUserId(), "assistant", conversation.getId(), reply, AiMessageStatus.COMPLETED);
        return new AiChatResponse(conversation.getId(), reply);
    }

    @Override
    public List<AiConversation> loadConversations() {
        return aiConversationMapper.loadConversionsByUid(UserContext.getUserId());
    }

    @Override
    public List<AiMessage> loadMessagesByCid(Long conversationId) {
        checkUidAndCid(conversationId);
        return aiMessageMapper.loadMessagesByCid(conversationId);
    }

    private void checkUidAndCid(Long conversationId) {
        if (UserContext.getUserId() == null) throw new BusinessException("用户未登录");
        AiConversation conversation = aiConversationMapper.getCurrentConversation(conversationId, UserContext.getUserId());
        if (conversation == null) throw new BusinessException("会话不存在或无权访问");
    }

    @Override
    public void updateConversationTitle(Long conversationId, String title) {
        checkUidAndCid(conversationId);
        aiConversationMapper.updateTitle(conversationId, UserContext.getUserId(), title);
    }

    @Override
    @Transactional
    public void deleteConversation(Long conversationId) {
        checkUidAndCid(conversationId);
        // 先删除消息
        aiMessageMapper.deleteByCid(conversationId);
        // 再删除会话
        aiConversationMapper.deleteById(conversationId, UserContext.getUserId());
    }

    @Override
    public void chatStream(AiChatRequest request, SseEmitter emitter) {
        // 必须在当前请求线程获取
        Long userId = UserContext.getUserId();
        String authorization = currentAuthorization();
        final String modelId = request.modelId();
        if (userId == null) {
            emitter.completeWithError(new BusinessException("流式聊天需要登录用户"));
            return;
        }
        if (authorization == null || authorization.isBlank()) {
            emitter.completeWithError(new BusinessException("缺少 LLM 鉴权信息"));
            return;
        }

        AiStreamContextManager context = new AiStreamContextManager();

        emitter.onTimeout(() -> {
            context.timeout();
            LogHelper.info("AI SSE 请求超时");
        });

        emitter.onError(error -> {
            context.cancel();
            LogHelper.info("AI SSE 发生异常：" + error.getMessage());
        });

        emitter.onCompletion(() -> {
            if (context.isGenerating()) {
                context.cancel();
                LogHelper.info("AI SSE 提前结束");
            } else {
                LogHelper.info("AI SSE 请求结束 ========== SSE CLIENT DISCONNECTED ==========");
            }
        });

        executor.execute(() -> {
            Long conversationId = request.conversationId();
            AtomicBoolean assistantSaved = new AtomicBoolean(false);
            try {
                AiConversation conversation;
                List<AiMessage> historyMessages = null;
                if (conversationId == null) {
                    conversation = new AiConversation();
                    conversation.setUserId(userId);
                    conversation.setTitle(request.message() == null || request.message().isBlank() ? "图片消息" : request.message());
                    aiConversationMapper.insertConversation(conversation);
                    conversationId = conversation.getId();
                } else {
                    conversation = aiConversationMapper.getCurrentConversation(conversationId, userId);
                    if (conversation == null) throw new BusinessException("会话不存在");
                    if (!context.isGenerating()) return;
                    try {
                        aiSummaryService.toCheckOrUpdateSummary(conversationId, userId, authorization, modelId);
                    } catch (Exception e) {
                        LogHelper.error("AI 历史摘要生成失败，继续使用现有摘要。conversationId={" + conversationId + "}, error=" + e.getMessage());
                    }
                    conversation = aiConversationMapper.getCurrentConversation(conversationId, userId);
                    long summaryUptoMsgId = conversation.getSummaryUptoMessageId() == null ? 0 : conversation.getSummaryUptoMessageId();
                    historyMessages = aiMessageMapper.loadCompletedMessagesAfterId(conversationId, summaryUptoMsgId);
                }

                List<Map<String, Object>> messages = aiContextBuilder.build(
                        SYSTEM_PROMPT,
                        conversation.getSummary(),
                        historyMessages,
                        request.message(),
                        request.imageUrls(),
                        6000
                );

                aiMessageService.saveMessage(userId, "user", conversationId, request.message(), request.imageUrls(), AiMessageStatus.COMPLETED);

                if (!context.isGenerating()) return;

                LogHelper.info("AI SSE 开始生成，conversationId=" + conversationId);
                final Long conversationIdFinal = conversationId;
                emitter.send(SseEmitter.event().name("start").data(new AiStreamEvent("start", "", conversationId, null)));
                Disposable disposable = llmHttpService.chatStream(messages, null, modelId, authorization).subscribe(
                        event -> {
                            if (event == null || !event.isObject()) {
                                return;
                            }

                            String type = textValue(event, "type");
                            if (type == null) {
                                return;
                            }

                            if ("CONTENT".equals(type)) {
                                if (!context.isGenerating()) {
                                    return;
                                }

                                String token = textValue(event, "content");
                                if (token == null || token.isEmpty()) {
                                    return;
                                }

                                context.append(token);

                                try {
                                    emitter.send(SseEmitter.event().name("token").data(new AiStreamEvent("token", token, conversationIdFinal, null)));
                                } catch (IOException e) {
                                    context.cancel();
                                    LogHelper.info("发送 SSE token 失败，客户端可能已断开");
                                }
                                return;
                            }

                            if ("COMPLETE".equals(type)) {
                                if (!context.isGenerating()) {
                                    return;
                                }

                                context.complete();
                                AiMessage message = aiMessageService.saveMessage(userId, "assistant", conversationIdFinal, context.getContent(), AiMessageStatus.COMPLETED);
                                assistantSaved.set(true);

                                try {
                                    emitter.send(SseEmitter.event().name("complete").data(new AiStreamEvent("complete", "", conversationIdFinal, message.getId())));
                                    emitter.complete();
                                } catch (IOException ignored) {
                                    // 客户端可能已经断开
                                }
                            }
                        },
                        error -> {
                            if (context.isGenerating()) {
                                context.fail();
                            }

                            LogHelper.error("AI 流式聊天失败，conversationId={" + conversationIdFinal + "}, error=" + error.getMessage());

                            if (!assistantSaved.get() && conversationIdFinal != null && !context.getContent().isBlank()) {
                                aiMessageService.saveMessage(userId, "assistant", conversationIdFinal, context.getContent(), context.getStatus());
                            }

                            try {
                                String errorMessage = error.getMessage() == null || error.getMessage().isBlank()
                                        ? "AI 回复生成失败"
                                        : error.getMessage();
                                emitter.send(SseEmitter.event().name("error").data(new AiStreamEvent("error", errorMessage, conversationIdFinal, null)));
                            } catch (IOException ignored) {
                                // 客户端可能已经断开
                            }
                            emitter.completeWithError(error);
                        }
                );

                context.setSubscription(disposable);
            } catch (Exception e) {
                if (context.isGenerating()) context.fail();
                AiMessageStatus finalStatus = context.getStatus();

                // 失败时保留部分回答；避免已保存成功的回答被重复保存
                if (!assistantSaved.get() && conversationId != null && !context.getContent().isBlank()) {
                    aiMessageService.saveMessage(userId, "assistant", conversationId, context.getContent(), finalStatus);
                }
                if (finalStatus != AiMessageStatus.CANCELLED) {
                    try {
                        emitter.send(SseEmitter.event().name("error").data(new AiStreamEvent("error", finalStatus == AiMessageStatus.TIMEOUT ? "AI 回复生成超时" : "AI 回复生成失败", conversationId, null)));
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(e);
                }
            }
        });
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }

}
