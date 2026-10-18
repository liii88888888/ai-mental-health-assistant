package com.lqy.enums;

import lombok.Getter;

@Getter
public enum EmotionType {

    HAPPY("开心"),
    CALM("平静"),
    ANXIOUS("焦虑"),
    SAD("悲伤"),
    EXCITED("兴奋"),
    TIRED("疲惫"),
    SURPRISED("惊讶"),
    CONFUSED("困惑");

    /** 既是存进数据库的值，也是前端展示的文字 */
    private final String label;

    EmotionType(String label) {
        this.label = label;
    }

    /**
     * 根据中文名反查枚举，取不到就抛异常
     * （会被 GlobalExceptionHandler 兜住，转成友好提示返回前端）
     */
    public static EmotionType fromLabel(String label) {
        for (EmotionType type : values()) {
            if (type.label.equals(label)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的主要情绪：" + label);
    }
}