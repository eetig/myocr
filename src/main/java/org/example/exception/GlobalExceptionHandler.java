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

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 引擎侧异常往往被层层包装，记全栈便于排查；对外只回一句统一文案
        log.error("识别服务异常", e);
        return Result.error(500, "服务器内部错误：" + e.getMessage());
    }
}
