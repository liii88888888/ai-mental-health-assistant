package com.lqy.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqy.DTO.command.KnowledgeArticleQueryDTO;
import com.lqy.DTO.command.KnowledgeArticleCreateDTO;
import com.lqy.DTO.response.KnowledgeArticlePageDTO;
import com.lqy.entity.KnowledgeArticle;
import com.lqy.entity.KnowledgeCategory;
import com.lqy.entity.User;
import com.lqy.exception.BusinessException;
import com.lqy.mapper.KnowledgeCategoryMapper;
import com.lqy.mapper.KnowledgeArticleMapper;
import com.lqy.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库：文章读写的 Cache-Aside 实现。
 *
 * 这个模块的存在意义就是"把 Cache-Aside 真的写一遍" —— 文章是典型的读多写少数据，
 * 正好是缓存的最佳战场。
 */
@Slf4j
@Service
public class KnowledgeArticleService {

    /** 缓存 key 前缀 */
    private static final String KEY_PREFIX = "knowledge:article:";

    /** 缓存存活时间 */
    private static final Duration TTL = Duration.ofMinutes(30);

    @Autowired
    private KnowledgeArticleMapper articleMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private KnowledgeCategoryMapper categoryMapper;

    /**
     * 文章列表（分页）。
     *
     * ★ 这个接口**故意不走缓存**。
     *   缓存的目标是"单篇详情"：key 稳定、读多写少、内容不随别人变化。
     *   列表页带分页 + 排序 + 筛选，key 组合会爆炸，而且每发一篇新文章所有列表页都该失效 —— 缓存收益是负的。
     *   **缓存不是越多越好，是要选对颗粒度。**
     */
    public IPage<KnowledgeArticlePageDTO> page(KnowledgeArticleQueryDTO query) {
        LambdaQueryWrapper<KnowledgeArticle> wrapper = new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(query.getCategoryId() != null, KnowledgeArticle::getCategoryId, query.getCategoryId())
                .eq(query.getStatus() != null, KnowledgeArticle::getStatus, query.getStatus())
                .like(StringUtils.hasText(query.getKeyword()), KnowledgeArticle::getTitle, query.getKeyword());

        applySort(wrapper, query.getSortField(), query.getSortDirection());

        Page<KnowledgeArticle> page = new Page<>(query.getCurrentPage(), query.getSize());
        IPage<KnowledgeArticle> result = articleMapper.selectPage(page, wrapper);
        List<KnowledgeArticle> records = result.getRecords();

        // 批量把 authorName / categoryName 补上，避免前端拿着 id 再查一遍（也是避免 N+1）
        Map<Long, String> authorNames = records.isEmpty()
                ? Collections.emptyMap() : batchAuthorNames(records);
        Map<Long, String> categoryNames = records.isEmpty()
                ? Collections.emptyMap() : batchCategoryNames(records);

        Page<KnowledgeArticlePageDTO> dtoPage =
                new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(records.stream()
                .map(a -> toPageDTO(a, authorNames, categoryNames))
                .toList());
        return dtoPage;
    }

    /**
     * 排序白名单。
     *
     * ★ 为什么必须白名单：sortField 是**前端传上来的用户输入**，
     *   而 ORDER BY 后面没法用预编译参数（`order by ?` 是无效 SQL），
     *   只能拼字符串 —— 直接拼就是教科书级的 SQL 注入。
     *   所以做法是"只认枚举里的几个值，其余一律走默认排序"，用代码把注入面收成 0。
     */
    private void applySort(LambdaQueryWrapper<KnowledgeArticle> wrapper, String field, String direction) {
        boolean asc = "asc".equalsIgnoreCase(direction);

        if (field == null) {
            wrapper.orderByDesc(KnowledgeArticle::getPublishedAt);
            return;
        }
        switch (field) {
            case "readCount"   -> wrapper.orderBy(true, asc, KnowledgeArticle::getReadCount);
            case "updatedAt"   -> wrapper.orderBy(true, asc, KnowledgeArticle::getUpdatedAt);
            case "publishedAt" -> wrapper.orderBy(true, asc, KnowledgeArticle::getPublishedAt);
            default            -> wrapper.orderByDesc(KnowledgeArticle::getPublishedAt);
        }
    }

    private KnowledgeArticlePageDTO toPageDTO(KnowledgeArticle a,
                                              Map<Long, String> authorNames,
                                              Map<Long, String> categoryNames) {
        return KnowledgeArticlePageDTO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .summary(a.getSummary())
                .coverImage(a.getCoverImage())
                .tags(a.getTags())
                .categoryId(a.getCategoryId())
                .categoryName(categoryNames.get(a.getCategoryId()))
                .authorId(a.getAuthorId())
                .authorName(authorNames.get(a.getAuthorId()))
                .readCount(a.getReadCount())
                .status(a.getStatus())
                .publishedAt(a.getPublishedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private Map<Long, String> batchAuthorNames(List<KnowledgeArticle> records) {
        Set<Long> ids = records.stream()
                .map(KnowledgeArticle::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, KnowledgeArticleService::displayName));
    }

    private Map<Long, String> batchCategoryNames(List<KnowledgeArticle> records) {
        Set<Long> ids = records.stream()
                .map(KnowledgeArticle::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(KnowledgeCategory::getId,
                        c -> c.getCategoryName() == null ? "" : c.getCategoryName()));
    }

    /** 优先用昵称，没有就用用户名 */
    private static String displayName(User u) {
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
    }

    /**
     * 读文章 —— 标准的 Cache-Aside 三步：
     *   ① 查缓存，命中直接返回
     *   ② 未命中，查数据库
     *   ③ 把结果回填进缓存，再返回
     */
    public KnowledgeArticle getById(String id) {
        String key = buildKey(id);

        // ① 查缓存（Redis 挂了就降级，直接查库）
        try {
            String cached = redis.opsForValue().get(key);
            if (StringUtils.hasText(cached)) {
                log.info("[Cache-Aside] 命中缓存 id={}", id);
                return objectMapper.readValue(cached, KnowledgeArticle.class);
            }
            log.info("[Cache-Aside] 未命中，回源数据库 id={}", id);
        }
        catch (Exception e) {
            log.warn("[Cache-Aside] 读缓存失败，降级查库：{}", e.getMessage());
        }

        // ② 查数据库
        KnowledgeArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }

        // ③ 回填缓存（失败也不影响本次返回）
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(article), TTL);
        }
        catch (Exception e) {
            log.warn("[Cache-Aside] 回填缓存失败：{}", e.getMessage());
        }

        return article;
    }

    /** 新建文章：只写库，不写缓存（等第一次被读的时候再回填） */
    public KnowledgeArticle create(KnowledgeArticleCreateDTO dto, Long authorId) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeArticle article = KnowledgeArticle.builder()
                .categoryId(dto.getCategoryId())
                .title(dto.getTitle())
                .summary(dto.getSummary())
                .content(dto.getContent())
                .tags(dto.getTags())
                .authorId(authorId)
                .readCount(0)
                .status(1)
                .publishedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        articleMapper.insert(article);
        return article;
    }

    /**
     * 删除文章 —— Cache-Aside 的关键是**顺序**：
     *
     *   正确：先删库，再删缓存
     *   错误：先删缓存，再删库
     *
     * 反过来会怎样？删完缓存、还没删库的那一瞬间，如果来了个读请求，
     * 它会从数据库读到"还没删掉的旧数据"并写回缓存 —— 于是缓存里留下一条
     * 数据库里已经不存在的"幽灵文章"，要等 TTL 过期才能自愈。
     *
     * 先删库再删缓存，最坏只是中间那一下脏读，最终仍然一致。
     */
    public void delete(String id) {
        if (articleMapper.selectById(id) == null) {
            throw new BusinessException("文章不存在");
        }

        // ① 先删数据库
        articleMapper.deleteById(id);

        // ② 再删缓存
        evict(id);
    }

    /** 阅读数 +1：数据库原子自增，同时让缓存失效 */
    public void increaseReadCount(String id) {
        int rows = articleMapper.update(null, new LambdaUpdateWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getId, id)
                .setSql("read_count = read_count + 1"));

        if (rows == 0) {
            throw new BusinessException("文章不存在");
        }
        // 数据变了，缓存必须失效，否则会一直读到旧的阅读数
        evict(id);
    }

    /** 删缓存：删失败也没关系，TTL 会兜底 */
    public void evict(String id) {
        try {
            redis.delete(buildKey(id));
            log.info("[Cache-Aside] 已删除缓存 id={}", id);
        }
        catch (Exception e) {
            log.warn("[Cache-Aside] 删除缓存失败（TTL 会兜底）：{}", e.getMessage());
        }
    }

    private String buildKey(String id) {
        return KEY_PREFIX + id;
    }
}
