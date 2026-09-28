package org.example.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 识别服务配置。
 *
 * <p>密钥类配置<b>不落盘到仓库</b>，通过外部注入：
 * <ul>
 *   <li>本地：环境变量，或项目根 config/application.yml（已 gitignore）</li>
 *   <li>生产：1Panel 环境变量（优先级最高）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    /**
     * 启用的识别引擎标识，对应 {@code OcrEngine#name()}。
     * 默认 mock —— 开箱即可启动联调，不会误调真实 API 产生费用。
     */
    private String engine = "mock";

    /** 单张图片大小上限（字节），超过直接拒绝，避免把超大图送到外部 API */
    private long maxImageBytes = 10L * 1024 * 1024;

    /** 腾讯云引擎配置（{@code ocr.engine=tencent} 时生效） */
    private Tencent tencent = new Tencent();

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public Tencent getTencent() {
        return tencent;
    }

    public void setTencent(Tencent tencent) {
        this.tencent = tencent;
    }

    /**
     * 腾讯云「文档抽取（多模态版）」ExtractDocMulti 配置。
     *
     * <p>凭据一律由外部注入，本类只声明结构，不含任何默认密钥。
     */
    public static class Tencent {

        private String secretId = "";

        private String secretKey = "";

        /** 地域，如 ap-guangzhou。文档抽取为地域化服务，填错会报资源不存在 */
        private String region = "ap-guangzhou";

        /** 服务接入点，一般无需改动 */
        private String endpoint = "ocr.tencentcloudapi.com";

        /**
         * 模版配置 id：{@code General} 通用场景、{@code Table} 表格模版。
         * 不同配置返回的结构化程度不同，是识别效果的主要调节项。
         */
        private String configId = "General";

        /**
         * 自定义结构化字段名（对应接口的 ItemNames）。
         *
         * <p>与本服务 {@code OcrItem} 的字段范围一致：只识别「物料编码 / 物料名称 / 数量」，
         * 单位与序号不识别（单位由业务方按物料编码查主数据带出、序号由前端按行号生成）。
         */
        private List<String> itemNames = new ArrayList<>(
                List.of("单据类型", "单据号", "日期", "物料编码", "物料名称", "数量"));

        /**
         * 是否把腾讯云的原始响应 JSON 打进日志。
         *
         * <p>联调验证期开启：映射逻辑依赖真实返回的嵌套形态，
         * 开启后无需改动代码即可看到原始结构，便于核对与修正字段映射。
         * 稳定后应关闭 —— 原始响应含单据全文，长期打印会放大日志量与信息暴露面。
         */
        private boolean logRawResponse = false;

        public String getSecretId() {
            return secretId;
        }

        public void setSecretId(String secretId) {
            this.secretId = secretId;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getConfigId() {
            return configId;
        }

        public void setConfigId(String configId) {
            this.configId = configId;
        }

        public List<String> getItemNames() {
            return itemNames;
        }

        public void setItemNames(List<String> itemNames) {
            this.itemNames = itemNames;
        }

        public boolean isLogRawResponse() {
            return logRawResponse;
        }

        public void setLogRawResponse(boolean logRawResponse) {
            this.logRawResponse = logRawResponse;
        }
    }
}
