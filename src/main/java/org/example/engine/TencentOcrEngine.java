package org.example.engine;

import com.tencentcloudapi.common.AbstractModel;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ocr.v20181119.OcrClient;
import com.tencentcloudapi.ocr.v20181119.models.ExtractDocMultiRequest;
import com.tencentcloudapi.ocr.v20181119.models.ExtractDocMultiResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.example.config.OcrProperties;
import org.example.dto.RecognizeResult;
import org.example.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 腾讯云「文档抽取（多模态版）」引擎 —— {@code ExtractDocMulti}。
 *
 * <pre>
 * 选型理由：支持自定义字段名（ItemNames），一份代码即可适配多种单据，
 * 与「Schema 驱动、不把单据类型写死在代码里」的设计一致。
 *
 * 三个必须知道的实现约束：
 *   1. 【无置信度】ExtractDocMulti 的 Value 只有 AutoContent 与 Coord，
 *      不返回逐字段置信度（ExtractDocMultiPro 同样没有）。
 *      因此本引擎产出的所有 FieldValue.confidence 恒为 null，
 *      前端「低置信度标黄」拿不到数据 —— 这是接口能力边界，不是实现遗漏。
 *      需要该能力只能改用逐行返回 Confidence 的通用识别接口（如 GeneralAccurateOCR），
 *      代价是字段抽取要自己做，识别质量通常不如文档抽取。
 *   2. 【结构嵌套】返回是三层嵌套，由 {@link TencentResponseMapper} 拍平后映射。
 *   3. 【地域化】文档抽取按地域提供服务，region 填错会报资源不存在。
 * </pre>
 *
 * @see OcrEngine
 * @see TencentResponseMapper
 */
@Component
public class TencentOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TencentOcrEngine.class);

    public static final String ENGINE_NAME = "tencent";

    /** 图片经 Base64 后不得超过 7MB（腾讯云限制），据此留出余量反推原始字节上限 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final OcrProperties properties;

    private final TencentResponseMapper mapper = new TencentResponseMapper(ENGINE_NAME);

    /** 客户端内部维护连接池，构造开销大 —— 懒加载一次并复用 */
    private volatile OcrClient client;

    public TencentOcrEngine(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public boolean ready() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        OcrProperties.Tencent tencent = properties.getTencent();
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(tencent.getSecretId())) {
            missing.add("ocr.tencent.secret-id");
        }
        if (!StringUtils.hasText(tencent.getSecretKey())) {
            missing.add("ocr.tencent.secret-key");
        }
        return missing.isEmpty() ? null : "缺少配置 " + String.join("、", missing);
    }

    @Override
    public RecognizeResult recognize(byte[] imageBytes, String fileName) {
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException("图片 " + (imageBytes.length / 1024) + "KB 超过腾讯云 7MB（Base64 后）限制，"
                    + "请先压缩到 " + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB 以内");
        }

        OcrProperties.Tencent tencent = properties.getTencent();
        long start = System.currentTimeMillis();
        ExtractDocMultiResponse response = callTencent(imageBytes, tencent, fileName);

        if (tencent.isLogRawResponse()) {
            log.info("【腾讯云原始响应】fileName={}, requestId={}, json={}",
                    fileName, response.getRequestId(), AbstractModel.toJsonString(response));
        }

        RecognizeResult result = mapper.map(response.getStructuralList());

        log.info("腾讯云识别完成, fileName={}, requestId={}, 物料行数={}, 耗时={}ms",
                fileName, response.getRequestId(), result.getItems().size(),
                System.currentTimeMillis() - start);
        return result;
    }

    private ExtractDocMultiResponse callTencent(
            byte[] imageBytes, OcrProperties.Tencent tencent, String fileName) {
        ExtractDocMultiRequest request = new ExtractDocMultiRequest();
        request.setImageBase64(Base64.getEncoder().encodeToString(imageBytes));
        request.setConfigId(tencent.getConfigId());
        // 只要自定义字段：不取全文字段，显著减小响应体与 token 消耗
        request.setReturnFullText(false);

        List<String> itemNames = tencent.getItemNames();
        if (itemNames != null && !itemNames.isEmpty()) {
            request.setItemNames(itemNames.toArray(new String[0]));
            // true = 仅返回自定义字段；否则会附带一堆模版默认字段，拍平时变成噪声行
            request.setItemNamesShowMode(true);
        }

        try {
            return client(tencent).ExtractDocMulti(request);
        } catch (TencentCloudSDKException e) {
            throw new BusinessException(describe(e, fileName));
        }
    }

    /** 把 SDK 异常翻译成可操作的提示 —— 鉴权类错误占联调期的大多数 */
    private String describe(TencentCloudSDKException e, String fileName) {
        String code = e.getErrorCode();
        String detail = "[" + code + "] " + e.getMessage() + " (requestId=" + e.getRequestId() + ")";

        // 服务未开通：账号层面没开通文字识别（或该子产品），与下面「有权限但未开通」是两回事
        if (code != null && (code.contains("UnOpenError") || code.contains("NotOpen"))) {
            return "腾讯云 OCR 服务未开通：请登录腾讯云控制台，在「文字识别 OCR」产品页开通服务，"
                    + "并确认已开通所需的子能力（文档抽取为多模态能力，可能需单独申请）。" + detail;
        }
        // 有服务、但这对密钥所属账号没有该动作的 CAM 权限
        if (code != null && code.startsWith("AuthFailure")) {
            return "腾讯云鉴权失败：密钥未授予 OCR 权限。"
                    + "请在腾讯云 CAM 为该密钥所属账号授予 ocr:ExtractDocMulti（或 QcloudOCRFullAccess）。"
                    + detail;
        }
        if (code != null && code.contains("LimitExceeded")) {
            return "腾讯云调用频率或额度受限，请稍后重试或检查配额。" + detail;
        }
        return "腾讯云识别失败, fileName=" + fileName + " " + detail;
    }

    private OcrClient client(OcrProperties.Tencent tencent) {
        OcrClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    HttpProfile httpProfile = new HttpProfile();
                    httpProfile.setEndpoint(tencent.getEndpoint());
                    httpProfile.setConnTimeout(10);
                    // 文档抽取是模型推理，偶发慢于普通 OCR，读超时留足
                    httpProfile.setReadTimeout(60);

                    ClientProfile clientProfile = new ClientProfile();
                    clientProfile.setHttpProfile(httpProfile);
                    // 显式指定签名算法：不依赖 SDK 默认值，升级 SDK 时行为不变
                    clientProfile.setSignMethod(ClientProfile.SIGN_TC3_256);

                    current = new OcrClient(
                            new Credential(tencent.getSecretId(), tencent.getSecretKey()),
                            tencent.getRegion(), clientProfile);
                    client = current;
                    log.info("腾讯云识别客户端已初始化, region={}, endpoint={}, configId={}",
                            tencent.getRegion(), tencent.getEndpoint(), tencent.getConfigId());
                }
            }
        }
        return current;
    }
}
