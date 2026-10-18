package com.lqy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@TableName("consultation_message")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** 发送者：1 用户 / 2 AI助手（见 SenderType） */
    @TableField("sender_type")
    private Integer senderType;

    /** 消息类型：1 文本（目前只有这一种） */
    @TableField("message_type")
    private Integer messageType;

    /** 消息内容 */
    private String content;

    /** 这条消息的情绪标签（可选，暂时不填） */
    @TableField("emotion_tag")
    private String emotionTag;

    /** 生成这条回复的模型（可选，用于追溯） */
    @TableField("ai_model")
    private String aiModel;

    @TableField("created_at")
    private LocalDateTime createdAt;
}