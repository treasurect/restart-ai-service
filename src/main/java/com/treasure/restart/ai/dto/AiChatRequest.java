package com.treasure.restart.ai.dto;

import java.util.List;

public record AiChatRequest(
        String modelId,
        Long conversationId,
        String message,
        List<String> imageUrls,
        List<HistoryMsgItem> history
) {
    public AiChatRequest {
        modelId = modelId == null || modelId.isBlank() ? "deepseek-flash" : modelId.trim();
    }

    public record HistoryMsgItem(
            String role,
            String content,
            List<String> imageUrls
    ) {
    }
}
