package com.lqy.controller;

import com.lqy.DTO.command.KnowledgeArticleCreateDTO;
import com.lqy.DTO.command.KnowledgeArticleQueryDTO;
import com.lqy.common.Result;
import com.lqy.entity.KnowledgeArticle;
import com.lqy.DTO.response.KnowledgeArticlePageDTO;
import com.lqy.service.KnowledgeArticleService;
import com.lqy.util.SecurityUtil;
import jakarta.validation.Valid;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库：文章接口（最小版，只覆盖 Cache-Aside 相关的读写）
 *
 * 注意：这里是 demo，删除没有做管理员校验 —— 生产环境应该是管理员才能删。
 */
@RestController
@RequestMapping("/api/knowledge/article")
public class KnowledgeArticleController {

    @Autowired
    private KnowledgeArticleService articleService;

    /**
     * 文章列表（分页）。
     *
     * ★ 这个方法之前是缺失的 —— 前端调 /page，被下面的 /{id} 吞掉了：
     *   Spring 把 "page" 当成文章 ID，查不到就抛 "文章不存在"，
     *   前端拦截器把 -1 当"登录过期"，于是点一下知识库就被踢回登录页。
     *
     * 现在 /page 是字面量路径，Spring 的路径匹配会优先选中它，不会再落到 /{id} 上。
     */
    @GetMapping("/page")
    public Result<IPage<KnowledgeArticlePageDTO>> page(KnowledgeArticleQueryDTO query) {
        return Result.ok(articleService.page(query));
    }

    /** 文章详情（走缓存） */
    @GetMapping("/{id}")
    public Result<KnowledgeArticle> detail(@PathVariable String id) {
        return Result.ok(articleService.getById(id));
    }

    /** 新建文章 */
    @PostMapping
    public Result<KnowledgeArticle> create(@Valid @RequestBody KnowledgeArticleCreateDTO dto) {
        Long authorId = SecurityUtil.getLoginUser().getUserId();
        return Result.ok(articleService.create(dto, authorId));
    }

    /** 删除文章（先删库，再删缓存） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        articleService.delete(id);
        return Result.ok();
    }

    /** 阅读一次（阅读数 +1，并让缓存失效） */
    @PutMapping("/{id}/read")
    public Result<Void> read(@PathVariable String id) {
        articleService.increaseReadCount(id);
        return Result.ok();
    }
}
