package org.example.controller;

import java.util.List;
import java.util.Map;
import org.example.dto.RecognizeResult;
import org.example.dto.Result;
import org.example.service.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单据识别对外接口（接口固定，引擎可替换）。
 *
 * <pre>
 * 设计约定（见《前后端改动统筹 · 变更-003》）：
 *   1. 本服务只做「图片 → 结构化数据」，不落库、不做业务校验
 *      —— 落库与校验由业务方复用现有 Excel 导入管线完成
 *   2. 图片以「字节」传入，本服务不依赖 MinIO / 数据库，保持无状态、易测试
 *   3. 每个字段带置信度，供前端标黄引导人工核对
 * </pre>
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);

    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 识别单据图片。
     *
     * <pre>
     * POST /api/ocr/recognize   multipart/form-data
     *   file      图片文件（必填）
     *   fileName  原始文件名（可选，仅用于日志与引擎提示）
     * </pre>
     *
     * <p>返回结构见 {@link RecognizeResult}；本接口<b>不落库</b>，
     * 人工在前端调整后，再由业务方提交确认接口完成入库。
     */
    @PostMapping("/recognize")
    public Result<RecognizeResult> recognize(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName) throws Exception {

        String name = (fileName != null && !fileName.isBlank())
                ? fileName
                : file.getOriginalFilename();
        log.info("收到识别请求, fileName={}, size={}字节", name, file.getSize());

        return Result.success(ocrService.recognize(file.getBytes(), name));
    }

    /**
     * 查看当前引擎与可用引擎 —— 便于测试与排查「当前到底在用哪个引擎」。
     * GET /api/ocr/engine
     */
    @GetMapping("/engine")
    public Result<Map<String, Object>> engine() {
        return Result.success(Map.of(
                "current", ocrService.currentEngine().name(),
                "available", ocrService.engineNames()));
    }

    /**
     * 健康检查 —— 部署后确认服务存活。
     * GET /api/ocr/health
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        List<String> available = ocrService.engineNames();
        return Result.success(Map.of(
                "status", "UP",
                "engine", ocrService.currentEngine().name(),
                "available", available));
    }
}
