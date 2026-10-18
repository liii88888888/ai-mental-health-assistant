package com.lqy.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeArticleCreateDTO {

    @NotNull(message = "分类ID不能为空")
    private Long categoryId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    @Size(max = 500, message = "摘要不能超过 500 字")
    private String summary;

    @NotBlank(message = "内容不能为空")
    private String content;

    @Size(max = 500, message = "标签不能超过 500 字")
    private String tags;
}
