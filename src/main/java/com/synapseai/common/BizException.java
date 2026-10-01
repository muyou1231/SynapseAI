package com.synapseai.common;

/**
 * 业务异常：可预期的业务规则冲突（越权、乐观锁冲突、成环校验失败等）。
 * <p>
 * 由 {@link GlobalExceptionHandler} 统一转成 {@code Result.error(message)}，
 * HTTP 状态码仍为 200 —— 与项目既有的接口风格保持一致（前端统一判 code）。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 1;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
