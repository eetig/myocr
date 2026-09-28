package org.example.dto;

/**
 * 单据行项目（识别结果）。
 *
 * <p>字段范围与《前后端改动统筹 · 变更-003》约定一致：
 * <ul>
 *   <li>识别：物料编码 / 物料名称 / 数量</li>
 *   <li>不识别：单位（由主数据按物料编码带出）、序号（前端按行号生成）</li>
 * </ul>
 */
public class OcrItem {

    private FieldValue materialCode;
    private FieldValue materialName;
    private FieldValue quantity;

    public OcrItem() {
    }

    public OcrItem(FieldValue materialCode, FieldValue materialName, FieldValue quantity) {
        this.materialCode = materialCode;
        this.materialName = materialName;
        this.quantity = quantity;
    }

    public FieldValue getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(FieldValue materialCode) {
        this.materialCode = materialCode;
    }

    public FieldValue getMaterialName() {
        return materialName;
    }

    public void setMaterialName(FieldValue materialName) {
        this.materialName = materialName;
    }

    public FieldValue getQuantity() {
        return quantity;
    }

    public void setQuantity(FieldValue quantity) {
        this.quantity = quantity;
    }
}
