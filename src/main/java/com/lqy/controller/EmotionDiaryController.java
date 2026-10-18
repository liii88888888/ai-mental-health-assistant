package com.lqy.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lqy.DTO.command.EmotionDiaryQueryDTO;
import com.lqy.DTO.command.EmotionDiarySaveCommandDTO;
import com.lqy.DTO.response.EmotionAnalysisResult;
import com.lqy.DTO.response.EmotionDiaryResponseDTO;
import com.lqy.common.Result;
import com.lqy.security.LoginUser;
import com.lqy.service.EmotionAnalysisService;
import com.lqy.service.EmotionDiaryService;
import com.lqy.util.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emotion-diary")
public class EmotionDiaryController {
    @Autowired
    private EmotionDiaryService emotionDiaryService;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    @GetMapping("/my")
    public Result<IPage<EmotionDiaryResponseDTO>> myList(EmotionDiaryQueryDTO query) {
        // 「我的日记」只能看自己的：userId 从登录态强制覆盖，不给前端传的机会
        query.setUserId(SecurityUtil.getLoginUser().getUserId());
        return Result.ok(emotionDiaryService.page(query));
    }

    @GetMapping("/admin/page")
    public Result<IPage<EmotionDiaryResponseDTO>> adminList(EmotionDiaryQueryDTO query) {
        // 后台列表能拿到所有人的日记，必须先证明是管理员
        SecurityUtil.requireAdmin();
        return Result.ok(emotionDiaryService.page(query));
    }

    @PostMapping
    public Result<EmotionDiaryResponseDTO> save(@Valid @RequestBody EmotionDiarySaveCommandDTO dto) {
        LoginUser loginUser = SecurityUtil.getLoginUser();
        return Result.ok(emotionDiaryService.save(dto, loginUser.getUserId()));
    }

    @DeleteMapping("/my/{id}")
    public Result<Void> userDelete(@PathVariable Long id) {
        emotionDiaryService.deleteMyDiary(id, SecurityUtil.getLoginUser().getUserId());
        return Result.ok();
    }

    @DeleteMapping("/admin/{id}")
    public Result<Void> adminDelete(@PathVariable Long id) {
        emotionDiaryService.deleteByAdmin(id, SecurityUtil.getLoginUser().getRoleType());
        return Result.ok();
    }

    @PostMapping("/{id}/analyze")
    public Result<EmotionAnalysisResult> analyze(@PathVariable Long id) {
        // 只能分析自己的日记 —— 之前没校验，任何登录用户都能触发别人日记的 AI 分析
        return Result.ok(emotionAnalysisService.analyze(id, SecurityUtil.getLoginUser().getUserId()));
    }


}
