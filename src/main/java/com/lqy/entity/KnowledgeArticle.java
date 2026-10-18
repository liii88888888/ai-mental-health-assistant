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
@TableName("knowledge_article")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeArticle {

    /** 主键是 UUID 字符串，不是自增 —— 交给 MyBatis-Plus 生成 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("category_id")
    private Long categoryId;

    private String title;

    private String summary;

    /** 正文（最小版先存纯文本，不做富文本） */
    private String content;

    @TableField("cover_image")
    private String coverImage;

    private String tags;

    @TableField("author_id")
    private Long authorId;

    @TableField("read_count")
    private Integer readCount;

    /** 1:已发布 */
    private Integer status;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
