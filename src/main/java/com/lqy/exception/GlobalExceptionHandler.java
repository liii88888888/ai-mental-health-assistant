package com.lqy.exception;

import com.lqy.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    //业务异常：邮箱已存在、手机号已被注册等可预期错误
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return fail(e.getMessage());
    }

    //非法参数：用户名已存在、两次密码不一致、无效的用户类型
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常：{}", e.getMessage());
        return fail(e.getMessage());
    }

    //@Valid 校验失败：返回第一条字段错误提示
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败：{}", message);
        return fail(message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity <Void> handleNoResourceFound (NoResourceFoundException e) {
        log.warn("资源不存在：{}", e.getMessage());
        return ResponseEntity.notFound().build();
    }

    /** 请求方法不对（比如用 GET 调了一个 POST 接口）：明确告诉调用方，别报成"系统繁忙" */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMessage());
        return ResponseEntity.status(405).build();
    }


    //兜底：未预期的系统异常，不把内部细节暴露给前端
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常：", e);
        return fail("系统繁忙，请稍后重试");
    }

    //统一构造错误响应：code=-1 + 具体错误信息
    private Result<Void> fail(String message) {
        Result<Void> result = Result.error();
        result.setMessage(message);
        return result;
    }
}
