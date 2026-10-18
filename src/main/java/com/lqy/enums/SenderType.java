package com.lqy.enums;

import lombok.Getter;

@Getter
public enum SenderType {

    USER(1, "用户"),
    AI(2, "AI助手");

    private final Integer code;
    private final String description;

    SenderType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static SenderType fromCode(Integer code) {
        for (SenderType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的发送者类型: " + code);
    }
}