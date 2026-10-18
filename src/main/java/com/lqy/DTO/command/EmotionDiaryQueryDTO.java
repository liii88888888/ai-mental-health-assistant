package com.lqy.DTO.command;

import lombok.Data;

@Data
public class EmotionDiaryQueryDTO {

    /** 页码，从 1 开始（对应前端的 current） */
    private Long current = 1L;

    /** 每页条数（对应前端的 size） */
    private Long size = 10L;

    /** 按用户筛选（后台用） */
    private Long userId;

    /** 情绪评分区间，形如 "1-3" / "4-6" / "7-10" */
    private String moodScoreRange;
}