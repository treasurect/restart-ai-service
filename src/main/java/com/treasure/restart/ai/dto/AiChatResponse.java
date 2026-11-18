package com.treasure.restart.ai.dto;

import org.jspecify.annotations.Nullable;

public record AiChatResponse(
        @Nullable Long conversationId,
        String reply
) {
}
