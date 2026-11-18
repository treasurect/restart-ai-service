package com.treasure.restart.ai.dto;

import java.util.List;

public record AiChatRequest(
        Long conversationId,
        String message,
        List<String> imageUrls,
        List<HistoryMsgItem> history
) {
    public record HistoryMsgItem(
            String role,
            String content,
            List<String> imageUrls
    ) {
    }
}
