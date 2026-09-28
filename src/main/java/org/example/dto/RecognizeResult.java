package org.example.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 识别结果（单据头 + 行项目）。
 *
 * <p>本服务只负责「图片 → 结构化数据」，<b>不落库、不做业务校验</b>：
 * 落库与校验由业务方（hnd_factory）复用现有 Excel 导入管线完成。
 */
public class RecognizeResult {

    /** 单据类型（如「原材料领料单」），业务方据此分派落库目标表 */
    private FieldValue documentType;

    /** 单据号（业务唯一键） */
    private FieldValue documentNo;

    /** 单据日期 */
    private FieldValue docDate;

    /** 行项目 */
    private List<OcrItem> items = new ArrayList<>();

    /** 实际执行识别的引擎标识，便于排查「这批数据是谁识别的」 */
    private String engine;

    /** 识别耗时（毫秒） */
    private Long costMillis;

    public FieldValue getDocumentType() {
        return documentType;
    }

    public void setDocumentType(FieldValue documentType) {
        this.documentType = documentType;
    }

    public FieldValue getDocumentNo() {
        return documentNo;
    }

    public void setDocumentNo(FieldValue documentNo) {
        this.documentNo = documentNo;
    }

    public FieldValue getDocDate() {
        return docDate;
    }

    public void setDocDate(FieldValue docDate) {
        this.docDate = docDate;
    }

    public List<OcrItem> getItems() {
        return items;
    }

    public void setItems(List<OcrItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public Long getCostMillis() {
        return costMillis;
    }

    public void setCostMillis(Long costMillis) {
        this.costMillis = costMillis;
    }
}
