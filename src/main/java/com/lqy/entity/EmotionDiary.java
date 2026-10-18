package com.lqy.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("emotion_diary")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmotionDiary {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("diary_date")
    private LocalDate diaryDate;

    /** 情绪评分 1-10 */
    @TableField("mood_score")
    private Integer moodScore;

    /** 主要情绪（用户从固定选项中选） */
    @TableField("dominant_emotion")
    private String dominantEmotion;

    /** 情绪触发因素 */
    @TableField("emotion_triggers")
    private String emotionTriggers;

    /** 日记正文 */
    @TableField("diary_content")
    private String diaryContent;

    /** 睡眠质量 1-5 */
    @TableField("sleep_quality")
    private Integer sleepQuality;

    /** 压力水平 1-5 */
    @TableField("stress_level")
    private Integer stressLevel;

    /** AI 情绪分析结果（JSON 字符串） */
    @TableField("ai_emotion_analysis")
    private String aiEmotionAnalysis;

    @TableField("ai_analysis_updated_at")
    private LocalDateTime aiAnalysisUpdatedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}