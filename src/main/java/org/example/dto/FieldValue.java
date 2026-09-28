package org.example.dto;

/**
 * 带置信度的字段值。
 *
 * <p>置信度是「辅助录入」的核心：前端据此把低置信度字段标黄，引导人工重点核对。
 * 引擎若无法给出置信度，允许为 {@code null}（前端按「未知」处理，不标黄也不隐藏）。
 */
public class FieldValue {

    /** 字段值：文本、数字或 null（未识别出） */
    private Object value;

    /** 置信度 0~1；null 表示引擎未提供 */
    private Double confidence;

    public FieldValue() {
    }

    public FieldValue(Object value, Double confidence) {
        this.value = value;
        this.confidence = confidence;
    }

    public static FieldValue of(Object value, Double confidence) {
        return new FieldValue(value, confidence);
    }

    public static FieldValue of(Object value) {
        return new FieldValue(value, null);
    }

    /** 空值（未识别出） */
    public static FieldValue empty() {
        return new FieldValue(null, null);
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
