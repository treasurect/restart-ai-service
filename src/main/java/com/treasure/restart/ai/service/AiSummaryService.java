package com.treasure.restart.ai.service;

import com.treasure.common.helper.LogHelper;
import com.treasure.restart.ai.model.ChatModel;
import com.treasure.restart.ai.service.llm.AiChatOptions;
import com.treasure.restart.ai.service.llm.AiModelClient;
import com.treasure.restart.ai.context.AiTokenCounter;
import com.treasure.restart.ai.entity.AiConversation;
import com.treasure.restart.ai.entity.AiMessage;
import com.treasure.restart.ai.mapper.AiConversationMapper;
import com.treasure.restart.ai.mapper.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiTokenCounter tokenCounter;
    private final AiModelClient aiModelClient;

    /**
     * 1.现将用户+助手 计算N轮对话，预留最近2轮消息 再往前 >= 2轮 就形成摘要
     */
    public void toCheckOrUpdateSummary(Long cid, Long userId) {
        AiConversation conversation = conversationMapper.getCurrentConversation(cid, userId);
        if (conversation == null) throw new IllegalArgumentException("会话不存在或无权访问");
        Long oldUptoMsgId = conversation.getSummaryUptoMessageId();
        if (oldUptoMsgId == null) oldUptoMsgId = 0L;

        List<List<AiMessage>> completeTurns = buildCompleteTurns(messageMapper.loadCompletedMessagesAfterId(cid, oldUptoMsgId));
        //预留最近消息2条 阈值2个
        int needSaveCount = completeTurns.size() - 2;
        if (needSaveCount < 2) return;
        ArrayList<List<AiMessage>> needSaveTurns = new ArrayList<>(completeTurns.subList(0, needSaveCount));

        ArrayList<AiMessage> needSaveMessages = new ArrayList<>();
        for (List<AiMessage> needSaveTurn : needSaveTurns) {
            needSaveMessages.addAll(needSaveTurn);
        }
        if (needSaveMessages.isEmpty()) return;
        Long newUptoMsgId = needSaveMessages.get(needSaveMessages.size() - 1).getId();
        if (newUptoMsgId == null || newUptoMsgId < oldUptoMsgId) return;

        String oldSummary = conversation.getSummary();
        String newSummary = generateSummary(oldSummary, needSaveMessages);
        if (newSummary == null || newSummary.isBlank()) return;
        int summaryTokens = tokenCounter.count(newSummary);
        int i = conversationMapper.updateSummary(cid, userId, newSummary, newUptoMsgId, summaryTokens, oldUptoMsgId);
        LogHelper.info("AiSummaryService -> 摘要更新" + (i == 1 ? "成功" : "失败") + "，conversationId={" + cid + "}, uptoMessageId={" + newUptoMsgId + "}, summaryTokens={" + summaryTokens + "}");
    }

    /**
     * 将完整的轮次[一条user+一条assistant] 存入到List
     * <p>
     * 输入必须按消息 ID 正序。
     * 一个轮次从 user 开始，至少包含一条 assistant 才算完整。
     */
    private List<List<AiMessage>> buildCompleteTurns(List<AiMessage> messages) {
        List<List<AiMessage>> allTurns = new ArrayList<>();

        if (messages == null || messages.isEmpty()) return allTurns;

        List<AiMessage> currentTurn = null;
        boolean hasAssistant = false;
        for (AiMessage message : messages) {
            if (message == null || message.getRole() == null || message.getContent() == null) continue;

            if ("user".equalsIgnoreCase(message.getRole())) {
                if (currentTurn != null && hasAssistant) allTurns.add(currentTurn);

                currentTurn = new ArrayList<>();
                currentTurn.add(message);
                hasAssistant = false;
                continue;
            }

            if (currentTurn == null) {
                continue;
            }

            if ("assistant".equalsIgnoreCase(message.getRole())) {
                currentTurn.add(message);
                hasAssistant = true;
            }
        }

        if (currentTurn != null && hasAssistant) allTurns.add(currentTurn);
        return allTurns;
    }

    private String generateSummary(String oldSummary, ArrayList<AiMessage> needSaveMessages) {
        StringBuilder builder = new StringBuilder();
        needSaveMessages.forEach(needSaveMessage -> {
            builder.append(needSaveMessage.getRole())
                    .append(": ")
                    .append(needSaveMessage.getContent())
                    .append("\n");
        });
        String systemPrompt = """
                你是一个对话历史摘要器。
                请将已有摘要与新增对话合并为一份准确、简洁、可供后续 AI 对话使用的摘要。
                
                优先保留：
                1. 用户明确提供的事实与长期目标；
                2. 已确认的技术选型、约束和设计决策；
                3. 已完成事项、当前问题和待办事项；
                4. 对后续问题有帮助的关键参数。
                
                不要把猜测写成事实，不要编造内容。
                删除重复信息，纠正已经明确被后续对话推翻的旧信息。
                输出纯文本摘要，不要添加解释。
                摘要应尽可能简洁，目标不超过 1200 Token。
                """;
        String userPrompt = """
                [已有摘要]:%s。
                [新增的完整对话轮次]:%s。
                请将这两部分合并为一份最新摘要
                """.formatted(oldSummary == null || oldSummary.isBlank() ? "无内容" : oldSummary, builder);
        ArrayList<ChatModel> messages = new ArrayList<>();
        messages.add(ChatModel.system(systemPrompt));
        messages.add(ChatModel.user(userPrompt));
        String summary = aiModelClient.chat(messages, new AiChatOptions(0.1, 1500, false, null)).trim();
        if (summary.isBlank()) throw new IllegalStateException("DeepSeek 返回空摘要");
        return summary;
    }
}
