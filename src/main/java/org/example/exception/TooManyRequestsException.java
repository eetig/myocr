package org.example.exception;

/**
 * 并发闸门拒绝：同时进行的识别调用已达上限（2026-10-15 code-review 的 Critical 3）。
 *
 * <p>单独一个类型是为了在 {@link GlobalExceptionHandler} 里映射成 429 —— 与
 * 400（入参问题）区分开，调用方据此决定"稍后重试"而不是"改请求"。
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
