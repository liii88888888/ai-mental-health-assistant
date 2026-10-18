package com.lqy.DTO.command;

import lombok.Data;

/**
 * 文章列表查询参数。
 *
 * 注意这里的 {@code currentPage}：原版前端用的就是这个名字（不是其他接口的 {@code current}）。
 * 我选择让后端迁就前端，而不是改前端 —— 少动一处就少一处回归风险。
 */
@Data
public class KnowledgeArticleQueryDTO {

    /** 页码，从 1 开始（前端传 currentPage） */
    private Long currentPage = 1L;

    /** 每页条数 */
    private Long size = 10L;

    /** 排序字段：publishedAt / readCount / updatedAt（会被白名单校验） */
    private String sortField;

    /** 排序方向：asc / desc */
    private String sortDirection;

    /** 分类筛选 */
    private Long categoryId;

    /** 标题模糊搜索 */
    private String keyword;

    /** 状态筛选：1 已发布 / 0 已下架。不传则不过滤（后台列表要看全部） */
    private Integer status;
}
