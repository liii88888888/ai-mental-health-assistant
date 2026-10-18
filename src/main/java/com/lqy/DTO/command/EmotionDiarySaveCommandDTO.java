package com.lqy.DTO.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDate;

@Data
public class EmotionDiarySaveCommandDTO {
    /** 日记日期：前端默认传今天；不传时后端兜底取当天 */
    private LocalDate diaryDate;

    /** 情绪自评 1-10 */
    @NotNull(message = "情绪评分不能为空")
    @Min(value = 1, message = "情绪评分最低为 1")
    @Max(value = 10, message = "情绪评分最高为 10")
    private Integer moodScore;

    /** 主要情绪，必须是 EmotionType 里的 8 个之一（在 Service 里校验） */
    @NotBlank(message = "主要情绪不能为空")
    @Size(max = 50, message = "主要情绪长度不能超过 50")
    private String dominantEmotion;

    @Size(max = 1000, message = "情绪触发因素不能超过 1000 字")
    private String emotionTriggers;

    @Size(max = 2000, message = "日记内容不能超过 2000 字")
    private String diaryContent;

    @Min(value = 1, message = "睡眠质量最低为 1")
    @Max(value = 5, message = "睡眠质量最高为 5")
    private Integer sleepQuality;

    @Min(value = 1, message = "压力水平最低为 1")
    @Max(value = 5, message = "压力水平最高为 5")
    private Integer stressLevel;
}
