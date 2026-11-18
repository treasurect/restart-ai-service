package com.treasure.restart.ai.dto;

public record AiStreamEvent(String type, String content, Long conversationId, Long messageId) {
}
