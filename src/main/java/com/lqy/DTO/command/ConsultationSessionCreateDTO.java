package com.lqy.DTO.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 开始会话的请求体。
 * 对应原版前端：{ initialMessage, sessionTitle }
 */
@Data
public class ConsultationSessionCreateDTO {

    /** 用户的开场白，会作为这个会话的第一条消息存下来 */
    @NotBlank(message = "初始消息不能为空")
    private String initialMessage;

    /** 会话标题；前端会传，不传则由后端按时间生成 */
    private String sessionTitle;
}
