package com.treasure.restart.ai.service.llm;

public record AiChatOptions(
        Double temperature,
        Integer maxTokens,
        Boolean thinkingEnabled,
        String reasoningEffort
) {

    public static AiChatOptions chatDefaults() {
        return new AiChatOptions(null, null, true, "high");
    }
}
