package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.example.config.OcrProperties;
import org.example.dto.RecognizeResult;
import org.example.engine.OcrEngine;
import org.example.exception.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发闸门的行为测试（2026-10-15 code-review 的 Critical 3 回归防线）。
 *
 * <p>这条护栏的意义全在"**并发真的发生时**怎么办"，所以测试必须真起一个线程把许可占住，
 * 再看第二个请求的反应 —— 断言源码里有 `Semaphore` 三个字是抓不到"忘了 release"或
 * "上限没生效"的。
 */
class OcrServiceConcurrencyTest {

    /** 桩引擎：进入后阻塞，直到测试放行 —— 用来把并发上限"卡住" */
    private static final class BlockingEngine implements OcrEngine {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String name() {
            return "blocking";
        }

        /** 测试用：等第一个请求真的进到引擎里 */
        boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        @Override
        public RecognizeResult recognize(byte[] imageBytes, String fileName) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RecognizeResult();
        }
    }

    private static OcrService serviceWith(BlockingEngine engine, int maxConcurrent) {
        OcrProperties properties = new OcrProperties();
        properties.setEngine(engine.name());
        properties.setMaxConcurrent(maxConcurrent);
        return new OcrService(List.of(engine), properties);
    }

    @Test
    @DisplayName("上限=1 时第二个请求快速失败为 429，且第一个请求不受影响（许可被正确释放）")
    void rejectsWhenConcurrencyExhausted() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        OcrService service = serviceWith(engine, 1);

        AtomicReference<RecognizeResult> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                firstResult.set(service.recognize(new byte[] {1, 2, 3}, "a.jpg"));
            } catch (Throwable e) {
                firstError.set(e);
            }
        });
        first.start();
        assertTrue(engine.awaitEntered(), "第一个请求应已进入引擎并占住许可");

        try {
            TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
                    () -> service.recognize(new byte[] {1, 2, 3}, "b.jpg"));
            assertTrue(e.getMessage().contains("并发上限"), "文案应说明是并发上限：" + e.getMessage());
        } finally {
            engine.release();
            first.join(5000);
        }

        assertNull(firstError.get(), "第一个请求不应被并发闸门影响：" + firstError.get());
        assertNotNull(firstResult.get(), "第一个请求应正常返回（许可释放后不影响它）");
    }

    @Test
    @DisplayName("上限=1 是下限：配 0 或负数被夹到 1，不会变成\"谁都能过\"")
    void clampsInvalidConcurrencyToAtLeastOne() throws Exception {
        for (int configured : new int[] {0, -3}) {
            BlockingEngine engine = new BlockingEngine();
            OcrService service = serviceWith(engine, configured);

            Thread first = new Thread(() -> service.recognize(new byte[] {1}, "a.jpg"));
            first.start();
            assertTrue(engine.awaitEntered());
            try {
                assertThrows(TooManyRequestsException.class,
                        () -> service.recognize(new byte[] {1}, "b.jpg"),
                        "上限被配成 " + configured + " 时仍应夹到 1");
            } finally {
                engine.release();
                first.join(5000);
            }
        }
    }

    @Test
    @DisplayName("入参校验仍先于并发闸门：空图片直接 400 语义，不占许可")
    void validatesBeforeAcquiringSlot() {
        BlockingEngine engine = new BlockingEngine();
        OcrService service = serviceWith(engine, 1);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.recognize(new byte[0], "empty.jpg"));
        assertEquals("图片内容为空", e.getMessage());
    }
}
