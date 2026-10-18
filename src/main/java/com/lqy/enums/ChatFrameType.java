package com.lqy.enums;

import lombok.Getter;

@Getter
public enum ChatFrameType {
    DELTA("delta", "增量消息"),
    ERROR("error", "AI侧出错"),
    DONE("done", "正常结束");

    private final String code;
    private final String description;

    ChatFrameType(String code, String description) {
        this.code = code;
        this.description = description;
    }


}
