package org.example.exception;

import org.example.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return Result.error(413, "上传文件超过大小限制");
    }

    /**
     * 并发闸门拒绝（2026-10-15 code-review 的 Critical 3）。
     *
     * <p>单列 429 而不是并进 400：调用方据此决定「稍后重试」而不是「改请求」。
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public Result<Void> handleTooManyRequests(TooManyRequestsException e) {
        return Result.error(429, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 引擎侧异常往往被层层包装，记全栈便于排查；对外只回一句统一文案
        // （注释一直这么写，但此前代码仍拼了 e.getMessage()：Spring 框架异常原文
        //   会连内部类名、方法签名一起回给免鉴权调用方 —— 2026-10-15 code-review 的 Critical 2 改正）
        log.error("识别服务异常", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}
