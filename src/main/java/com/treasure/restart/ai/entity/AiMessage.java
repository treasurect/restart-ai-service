package com.treasure.restart.ai.entity;

import com.treasure.restart.ai.AiMessageStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class AiMessage {
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
    private AiMessageStatus status;
    private String messageType;
    private String extra;
}
