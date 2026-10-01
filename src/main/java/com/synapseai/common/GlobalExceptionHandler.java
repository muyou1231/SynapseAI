package com.synapseai.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛为 {@link Result}，避免异常堆栈直接暴露给前端。
 * <p>
 * 处理优先级：业务异常 → 参数校验异常 → 参数缺失/类型错误 → 兜底未知异常。
 * 参数校验错误信息取第一条即可，避免前端弹出一大串提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：直接把提示语返回给前端 */
    @ExceptionHandler(BizException.class)
    public Result<?> handleBiz(BizException e) {
        Result<Object> r = new Result<>();
        r.setCode(e.getCode());
        r.setMessage(e.getMessage());
        return r;
    }

    /** @Valid 校验 @RequestBody 失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String msg = errors.isEmpty()
                ? "参数不合法"
                : errors.get(0).getDefaultMessage();
        return Result.error(msg);
    }

    /** @Validated 校验 @RequestParam / @PathVariable 失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数不合法");
        return Result.error(msg);
    }

    /** 缺少必填的请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error("缺少参数：" + e.getParameterName());
    }

    /** 参数类型不匹配（如 id 传了非数字） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error("参数格式不正确：" + e.getName());
    }

    /** 兜底：记录日志，返回通用错误，不泄露内部细节 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleOther(Exception e) {
        log.error("接口异常", e);
        return Result.error("服务器开小差了，请稍后重试");
    }
}
