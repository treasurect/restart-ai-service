package com.treasure.restart.ai.service.llm;

import com.treasure.restart.ai.context.AiStreamContextManager;
import com.treasure.restart.ai.model.ChatModel;

import java.util.List;
import java.util.function.Consumer;

public interface AiModelClient {

    default String chat(List<ChatModel> messages){
        return chat(messages, AiChatOptions.chatDefaults());
    }

    String chat(List<ChatModel> messages, AiChatOptions options);

    String chatStream(List<ChatModel> messages, Consumer<String> onToken, AiStreamContextManager context);
}
