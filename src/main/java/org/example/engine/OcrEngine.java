package org.example.engine;

import org.example.dto.RecognizeResult;

/**
 * 识别引擎抽象。
 *
 * <pre>
 * 本接口是本服务的「稳定面」：
 *   - 上层（Controller / Service）只依赖它，不认识任何具体厂商 SDK
 *   - 换厂商（腾讯云 → 阿里云）、换模型、转内网自建，只需新增一个实现类
 *   - 业务侧（hnd_factory / 前端）零改动
 * </pre>
 *
 * <p>新增引擎的步骤：
 * <ol>
 *   <li>实现本接口，{@link #name()} 返回唯一标识（如 {@code tencent}）</li>
 *   <li>加对应 SDK 依赖到 build.gradle</li>
 *   <li>配置 {@code ocr.engine=<你的标识>}</li>
 * </ol>
 * 无需改动 Service / Controller —— 引擎按 {@link #name()} 自动注册。
 */
public interface OcrEngine {

    /**
     * 引擎唯一标识，用于 {@code ocr.engine} 配置与识别结果回显（便于排查「这批数据是谁识别的」）。
     * 建议全小写，如 {@code mock} / {@code tencent}。
     */
    String name();

    /**
     * 引擎是否就绪（密钥、区域等必要配置是否齐全）。
     *
     * <p>用于在「配置缺失」时给出明确提示，而不是运行到一半抛底层异常。
     * 未就绪时 {@link #recognize} 不应被调用。
     */
    default boolean ready() {
        return true;
    }

    /** 配置缺失等导致不可用的原因说明，{@link #ready()} 为 true 时可为 null */
    default String unavailableReason() {
        return null;
    }

    /**
     * 识别单据图片。
     *
     * @param imageBytes 图片字节（由调用方传入，本服务不落盘、不依赖存储）
     * @param fileName   原始文件名，仅用于日志与引擎侧的类型提示，可为 null
     * @return 识别结果；字段未识别出时用 {@code FieldValue.empty()}，不要返回 null
     */
    RecognizeResult recognize(byte[] imageBytes, String fileName);
}
