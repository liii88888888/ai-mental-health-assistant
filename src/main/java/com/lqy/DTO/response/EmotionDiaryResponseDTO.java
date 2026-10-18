package com.lqy.DTO.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class EmotionDiaryResponseDTO {

    private Long id;
    private Long userId;

    /** 关联的用户信息（列表页要展示） */
    private String username;
    private String nickname;

    private LocalDate diaryDate;
    private Integer moodScore;
    private String dominantEmotion;
    private String emotionTriggers;
    private String diaryContent;
    private Integer sleepQuality;
    private Integer stressLevel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * AI 情绪分析结果（已经从 JSON 字符串解析成对象）。
     * 还没分析过时为 null —— 前端要用可选链判断。
     */
    private EmotionAnalysisResult aiAnalysis;
}
