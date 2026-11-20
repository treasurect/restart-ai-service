package com.treasure.restart.ai.model;

import java.util.List;

public record ChatModel(ChatRole role, String content, List<String> imageUrls) {
    public ChatModel {
        content = content == null ? "" : content;
        imageUrls = imageUrls == null ? List.of() : imageUrls.stream().filter(url -> url != null && !url.isEmpty()).toList();
    }

    public static ChatModel system(String content) {
        return new ChatModel(ChatRole.SYSTEM, content, null);
    }

    public static ChatModel user(String content) {
        return user(content, null);
    }

    public static ChatModel user(String content, List<String> imageUrls) {
        return new ChatModel(ChatRole.USER, content, imageUrls);
    }

    public static ChatModel assistant(String content) {
        return new ChatModel(ChatRole.ASSISTANT, content, null);
    }

    public boolean hasImage(){
        return imageUrls != null && !imageUrls.isEmpty();
    }
}
