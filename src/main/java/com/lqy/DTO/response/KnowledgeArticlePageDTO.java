package com.lqy.DTO.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章列表项。
 *
 * 比实体多了 categoryName / authorName —— 这两个是前端表格要显示的，
 * 但库里只存了 id。所以在 Service 里批量补上，避免前端拿着 id 再查一遍（N+1）。
 */
@Data
@Builder
public class KnowledgeArticlePageDTO {

    private String id;
    private String title;
    private String summary;
    private String coverImage;
    private String tags;

    private Long categoryId;
    private String categoryName;

    private Long authorId;
    private String authorName;

    private Integer readCount;
    private Integer status;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
}
