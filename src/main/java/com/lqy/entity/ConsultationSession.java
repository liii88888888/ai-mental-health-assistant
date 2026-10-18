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
@TableName("consultation_session")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 会话标题（会话列表页展示用） */
    @TableField("session_title")
    private String sessionTitle;

    /** 会话开始时间 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 最后一次情绪分析结果。
     * 数据库列是 JSON 类型，但 JDBC 读出来就是普通字符串，
     * 所以 Java 侧用 String 接，写入时用 JSONUtil.toJsonStr(对象) 序列化
     */
    @TableField("last_emotion_analysis")
    private String lastEmotionAnalysis;

    @TableField("last_emotion_updated_at")
    private LocalDateTime lastEmotionUpdatedAt;

    /** 最后一条消息预览（冗余字段，写消息时更新） */
    @TableField("last_message_content")
    private String lastMessageContent;

    /** 消息条数（冗余字段） */
    @TableField("message_count")
    private Integer messageCount;

    /** 最后一条消息时间（冗余字段） */
    @TableField("last_message_time")
    private LocalDateTime lastMessageTime;
}