package com.treasure.restart.ai.model;

import com.treasure.common.exception.BusinessException;

public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT;

    public String toApiRole() {
        return name().toLowerCase();
    }

    public static ChatRole fromApiRole(String role) {
        if (role == null) throw new BusinessException("Role is null");
        return switch (role.toLowerCase()) {
            case "system" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            default -> throw new BusinessException("Role is not support");
        };
    }
}
