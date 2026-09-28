package org.example.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.example.config.OcrProperties;
import org.example.dto.RecognizeResult;
import org.example.engine.OcrEngine;
import org.example.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 识别编排：选引擎 → 校验入参 → 调用 → 回填耗时。
 *
 * <p>本服务<b>不落库、不依赖 MinIO</b>，图片字节由调用方传入，保持无状态。
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** 引擎注册表：key = {@link OcrEngine#name()}。新增实现类自动注册，无需改这里 */
    private final Map<String, OcrEngine> engines;

    private final OcrProperties properties;

    public OcrService(List<OcrEngine> engineList, OcrProperties properties) {
        this.engines = engineList.stream().collect(Collectors.toMap(
                OcrEngine::name, engine -> engine, (first, second) -> first, LinkedHashMap::new));
        this.properties = properties;
        log.info("识别引擎已注册={}, 当前启用={}", engines.keySet(), properties.getEngine());
    }

    /** 当前启用的引擎；未配置或标识不存在时给出明确提示 */
    public OcrEngine currentEngine() {
        String name = properties.getEngine();
        OcrEngine engine = engines.get(name);
        if (engine == null) {
            throw new BusinessException(
                    "未找到识别引擎「" + name + "」，可用引擎：" + engines.keySet()
                            + "（配置项 ocr.engine）");
        }
        return engine;
    }

    /** 所有已注册引擎的标识，便于排查「当前到底在用哪个」 */
    public List<String> engineNames() {
        return List.copyOf(engines.keySet());
    }

    public RecognizeResult recognize(byte[] imageBytes, String fileName) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("图片内容为空");
        }
        if (imageBytes.length > properties.getMaxImageBytes()) {
            throw new BusinessException("图片超过大小上限 "
                    + (properties.getMaxImageBytes() / 1024 / 1024) + "MB");
        }

        OcrEngine engine = currentEngine();
        if (!engine.ready()) {
            throw new BusinessException("识别引擎「" + engine.name() + "」未就绪："
                    + engine.unavailableReason());
        }

        long start = System.currentTimeMillis();
        RecognizeResult result = engine.recognize(imageBytes, fileName);
        result.setCostMillis(System.currentTimeMillis() - start);
        if (result.getEngine() == null) {
            result.setEngine(engine.name());
        }

        log.info("识别完成, engine={}, fileName={}, 耗时={}ms, 行数={}",
                result.getEngine(), fileName, result.getCostMillis(), result.getItems().size());
        return result;
    }
}
