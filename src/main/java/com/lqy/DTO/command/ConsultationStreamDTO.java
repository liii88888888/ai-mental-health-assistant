package com.lqy.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 流式对话的请求体。
 * 对应原版前端 fetchEventSource 的 body：{ sessionId, userMessage }
 */
@Data
public class ConsultationStreamDTO {

    /** 会话ID（前端可能带 "session_" 前缀，用 SessionIdUtil 解析） */
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "单条消息不能超过 2000 字")
    private String userMessage;
}
