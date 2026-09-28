package org.example.engine;

import java.util.List;
import org.example.dto.FieldValue;
import org.example.dto.OcrItem;
import org.example.dto.RecognizeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模拟识别引擎 —— 不调用任何外部服务，返回固定样例数据。
 *
 * <pre>
 * 用途：在接入真实引擎（腾讯云 ExtractDocMulti）之前，把「接口契约 + 前端联调」先跑通。
 *   1. 前端可照着真实结构做界面（单据类型 / 单据号 / 日期 / 多行项目）
 *   2. 置信度刻意设置有高有低，用于验证「低置信度标黄」的交互
 *   3. 不消耗任何 API 额度，可放心反复调用
 *
 * 样例取自真实单据（原材料领料单 0001371），字段结构与真实识别结果一致。
 * </pre>
 *
 * @see OcrEngine
 */
@Component
public class MockOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(MockOcrEngine.class);

    public static final String ENGINE_NAME = "mock";

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public RecognizeResult recognize(byte[] imageBytes, String fileName) {
        log.info("【模拟识别】fileName={}, 图片大小={}字节（未调用任何外部服务）",
                fileName, imageBytes == null ? 0 : imageBytes.length);

        RecognizeResult result = new RecognizeResult();
        result.setEngine(ENGINE_NAME);

        // 单据头
        result.setDocumentType(FieldValue.of("原材料领料单", 0.93));
        result.setDocumentNo(FieldValue.of("0001371", 0.99));
        result.setDocDate(FieldValue.of("2026-09-22", 0.88));

        // 行项目：置信度刻意有高有低，便于验证前端标黄
        result.setItems(List.of(
                new OcrItem(
                        FieldValue.of("231", 0.62),        // 低置信度
                        FieldValue.of("三不硫", 0.71),      // 低置信度
                        FieldValue.of(4173, 0.91)),
                new OcrItem(
                        FieldValue.of("231", 0.60),
                        FieldValue.of("三不硫", 0.69),
                        FieldValue.of(4736, 0.88))));

        return result;
    }
}
