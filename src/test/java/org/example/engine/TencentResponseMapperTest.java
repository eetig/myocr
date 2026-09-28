package org.example.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tencentcloudapi.ocr.v20181119.models.GroupInfo;
import com.tencentcloudapi.ocr.v20181119.models.ItemInfo;
import com.tencentcloudapi.ocr.v20181119.models.Key;
import com.tencentcloudapi.ocr.v20181119.models.LineInfo;
import com.tencentcloudapi.ocr.v20181119.models.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.example.dto.OcrItem;
import org.example.dto.RecognizeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 响应映射单测。
 *
 * <p>映射是接入期最容易出错、又最难靠肉眼核对的部分，且不依赖密钥与网络，
 * 因此把行为固定在这里。
 *
 * <p><b>结构假设已用真实响应验证过</b>（2026-09-28，真实《物资领料单》，见
 * {@link TencentResponseMapper} 类注释的「实测返回结构」）。
 * 其中「单键值对的 entry 不是物料行」一条是实测踩到的真实缺陷，
 * 由 {@link #doesNotTreatLoneSingleFieldEntryAsItemRow()} 守住。
 *
 * <p>仍待加强：用例是手工构造的，尚未直接回放腾讯云原始 JSON。
 * 若再遇到形态不符的情况，应把真实 JSON 落成测试资源回放，而不是继续手工构造。
 */
class TencentResponseMapperTest {

    private final TencentResponseMapper mapper = new TencentResponseMapper("tencent");

    // ------------------------------------------------------------------
    // 构造测试数据（对应腾讯云的三层嵌套）
    // ------------------------------------------------------------------

    private static ItemInfo item(String autoName, String configName, String content) {
        Key key = new Key();
        key.setAutoName(autoName);
        key.setConfigName(configName);
        Value value = new Value();
        value.setAutoContent(content);
        ItemInfo info = new ItemInfo();
        info.setKey(key);
        info.setValue(value);
        return info;
    }

    private static LineInfo line(ItemInfo... items) {
        LineInfo line = new LineInfo();
        line.setLines(items);
        return line;
    }

    private static GroupInfo group(LineInfo... lines) {
        GroupInfo group = new GroupInfo();
        group.setGroups(lines);
        return group;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("典型单据：表头 + 多行物料，表头取到、物料行逐行拆开")
    void mapsHeaderAndMultipleItems() {
        GroupInfo header = group(line(
                item("单据类型", null, "原材料领料单"),
                item("单据号", null, "0001371"),
                item("日期", null, "2026-09-22")));
        GroupInfo table = group(
                line(item("物料编码", null, "231"), item("物料名称", null, "三不硫"), item("数量", null, "4173")),
                line(item("物料编码", null, "232"), item("物料名称", null, "四不硫"), item("数量", null, "4736")));

        RecognizeResult result = mapper.map(new GroupInfo[]{header, table});

        assertEquals("原材料领料单", result.getDocumentType().getValue());
        assertEquals("0001371", result.getDocumentNo().getValue());
        assertEquals("2026-09-22", result.getDocDate().getValue());

        assertEquals(2, result.getItems().size());
        assertEquals("231", result.getItems().get(0).getMaterialCode().getValue());
        assertEquals("三不硫", result.getItems().get(0).getMaterialName().getValue());
        assertEquals(4173L, result.getItems().get(0).getQuantity().getValue());
        assertEquals(4736L, result.getItems().get(1).getQuantity().getValue());
        assertEquals("tencent", result.getEngine());
    }

    @Test
    @DisplayName("单行单据：表头与物料在同一行，退化成一条物料行且表头不丢")
    void mapsSingleRowDocument() {
        GroupInfo single = group(line(
                item("单据类型", null, "原材料领料单"),
                item("单据号", null, "0001371"),
                item("日期", null, "2026-09-22"),
                item("物料编码", null, "231"),
                item("物料名称", null, "三不硫"),
                item("数量", null, "4173")));

        RecognizeResult result = mapper.map(new GroupInfo[]{single});

        assertEquals("0001371", result.getDocumentNo().getValue());
        assertEquals(1, result.getItems().size());
        assertEquals("231", result.getItems().get(0).getMaterialCode().getValue());
    }

    @Test
    @DisplayName("空响应与 null 不抛异常，返回空结果")
    void handlesEmptyResponse() {
        RecognizeResult fromNull = mapper.map(null);
        assertTrue(fromNull.getItems().isEmpty());
        assertNull(fromNull.getDocumentNo().getValue());

        RecognizeResult fromEmpty = mapper.map(new GroupInfo[0]);
        assertTrue(fromEmpty.getItems().isEmpty());
    }

    @Test
    @DisplayName("空值单元格不产出记录：全是空串的行被丢弃")
    void skipsRowsWithoutContent() {
        GroupInfo empty = group(line(
                item("单据号", null, "   "),
                item("物料编码", null, "")));

        RecognizeResult result = mapper.map(new GroupInfo[]{empty});

        assertTrue(result.getItems().isEmpty());
        assertNull(result.getDocumentNo().getValue());
    }

    @Test
    @DisplayName("数量容错：千分位与单位可解析成数字，解析不出则原样返回文本")
    void parsesQuantity() {
        assertEquals(4173L, mapper.parseQuantity("4,173 KG"));
        assertEquals(4173L, mapper.parseQuantity("4173kg"));
        assertEquals(4173L, mapper.parseQuantity("4173"));
        assertEquals(new BigDecimal("4173.5"), mapper.parseQuantity("4,173.5"));
        // 解析不出时不做猜测，把原文交还人工修正
        assertEquals("约四千", mapper.parseQuantity("约四千"));
        assertNull(mapper.parseQuantity(""));
        assertNull(mapper.parseQuantity(null));
    }

    @Test
    @DisplayName("日期归一为 yyyy-MM-dd：模型返回过中文式、斜杠式、两位年份等多种写法")
    void normalizesDateToIso() {
        assertEquals("2026-09-07", mapper.normalizeDate("2026年9月7日"));
        assertEquals("2026-09-22", mapper.normalizeDate("2026-09-22"));
        assertEquals("2026-09-07", mapper.normalizeDate("2026/9/7"));
        assertEquals("2026-09-07", mapper.normalizeDate("2026.9.7"));
        assertEquals("2026-09-07", mapper.normalizeDate(" 2026年09月07日 "));
        // 两位年份按就近补全为 20xx
        assertEquals("2026-09-26", mapper.normalizeDate("26年9月26日"));
    }

    @Test
    @DisplayName("日期解析不出就原样返回 —— 别把「模型到底给了什么」这条线索丢掉")
    void keepsUnparsableDateVerbatim() {
        // 实测：模型把单据右上角的手写涂鸦当成了日期
        assertEquals("20458", mapper.normalizeDate("20458"));
        assertEquals("约九月", mapper.normalizeDate("约九月"));
        // 非法日期（2 月 30 日）同样原样返回，交人工改
        assertEquals("2026年2月30日", mapper.normalizeDate("2026年2月30日"));
        // 空值不炸
        assertNull(mapper.normalizeDate(null));
        assertEquals("", mapper.normalizeDate(""));
    }

    @Test
    @DisplayName("键名容错：带单位后缀与全角括号同样命中别名")
    void matchesAliasWithSuffix() {
        GroupInfo table = group(line(
                item("物料编码（kg）", null, "231"),
                item("数量(kg)", null, "4173")));

        RecognizeResult result = mapper.map(new GroupInfo[]{table});

        assertEquals(1, result.getItems().size());
        assertEquals("231", result.getItems().get(0).getMaterialCode().getValue());
        assertEquals(4173L, result.getItems().get(0).getQuantity().getValue());
    }

    @Test
    @DisplayName("字段名回退：autoName 为空时用 configName")
    void fallsBackToConfigName() {
        GroupInfo table = group(line(
                item(null, "单据号", "0001371"),
                item(null, "物料编码", "231"),
                item(null, "数量", "4173")));

        RecognizeResult result = mapper.map(new GroupInfo[]{table});

        assertEquals("0001371", result.getDocumentNo().getValue());
        assertEquals("231", result.getItems().get(0).getMaterialCode().getValue());
    }

    @Test
    @DisplayName("置信度恒为 null：该接口不返回逐字段置信度")
    void confidenceIsAlwaysNull() {
        GroupInfo table = group(line(
                item("单据号", null, "0001371"),
                item("物料编码", null, "231"),
                item("数量", null, "4173")));

        RecognizeResult result = mapper.map(new GroupInfo[]{table});
        OcrItem first = result.getItems().get(0);

        assertNull(result.getDocumentNo().getConfidence());
        assertNull(first.getMaterialCode().getConfidence());
        assertNull(first.getMaterialName().getConfidence());
        assertNull(first.getQuantity().getConfidence());
    }

    @Test
    @DisplayName("回归：单键值对的 entry 不是物料行 —— 不得凭空多出「编码=不存在」的脏行")
    void doesNotTreatLoneSingleFieldEntryAsItemRow() {
        // 真实缺陷复现：单据上「物料编码」整列空白，模型仍吐出一个占位值
        GroupInfo loneCode = group(line(item("物料编码", null, "不存在")));
        GroupInfo table = group(
                line(item("物料名称", null, "150催化剂"), item("数量", null, "1")),
                line(item("物料名称", null, "150催化剂"), item("数量", null, "1")));

        RecognizeResult result = mapper.map(new GroupInfo[]{loneCode, table});

        assertEquals(2, result.getItems().size(), "只应有两行真实物料，不含孤立编码行");
        for (OcrItem item : result.getItems()) {
            assertNull(item.getMaterialCode().getValue(), "空白列的占位值不应被当成物料编码");
            assertEquals("150催化剂", item.getMaterialName().getValue());
            assertEquals(1L, item.getQuantity().getValue());
        }
    }

    @Test
    @DisplayName("回归：真实单据返回结构逐项还原（表头正确、物料行两条、无脏行）")
    void mapsRealDocumentStructure() {
        // 2026-09-28 实测《物资领料单》原始结构，见 TencentResponseMapper 类注释
        GroupInfo[] structuralList = {
                group(line(item("单据号", null, "0031015"))),
                group(line(item("单据类型", null, "物资领料单"))),
                group(line(item("日期", null, "2026年9月7日"))),
                group(line(item("日期", null, "2026年9月7日"))),   // 同字段重复出现
                group(line(item("物料编码", null, "不存在"))),      // 空白列的占位值
                group(line(item("物料名称", null, "150催化剂"), item("数量", null, "1")),
                      line(item("物料名称", null, "150催化剂"), item("数量", null, "1"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals("物资领料单", result.getDocumentType().getValue());
        assertEquals("0031015", result.getDocumentNo().getValue());
        // 模型返回「2026年9月7日」，映射层归一为业务统一格式（列表页筛选与落库的 date 列都用它）
        assertEquals("2026-09-07", result.getDocDate().getValue());
        assertEquals(2, result.getItems().size());
        assertEquals(1L, result.getItems().get(0).getQuantity().getValue());
    }

    @Test
    @DisplayName("数量口径：同一行同时出现申请数量与实发数量时，取实发数量")
    void prefersIssuedQuantityOverRequested() {
        GroupInfo table = group(line(
                item("序号", null, "1"),
                item("物料名称", null, "150催化剂"),
                item("申请数量", null, "5"),
                item("实发数量", null, "3")));

        RecognizeResult result = mapper.map(new GroupInfo[]{table});

        assertEquals(1, result.getItems().size());
        assertEquals(3L, result.getItems().get(0).getQuantity().getValue(),
                "实发数量才是仓库实际出库数，与领料汇总口径一致");
    }

    @Test
    @DisplayName("拍平：保留键值对的出现顺序，便于人工核对映射")
    void flattenKeepsOrder() {
        GroupInfo table = group(line(
                item("物料编码", null, "231"),
                item("物料名称", null, "三不硫"),
                item("数量", null, "4173")));

        List<TencentResponseMapper.Field> fields = mapper.flatten(new GroupInfo[]{table});

        assertEquals(3, fields.size());
        assertEquals(List.of("物料编码", "物料名称", "数量"),
                fields.stream().map(TencentResponseMapper.Field::name).toList());
        assertEquals("4173", fields.get(2).value());
    }

    // ------------------------------------------------------------------
    // 回归：模型对同一批单据会给出两种分组形态，两种都必须装配出正确的行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 拆散形态：每个字段各占一个 entry，仍要装配成一行（实测入库单大量如此）")
    void assemblesFieldsSplitAcrossEntries() {
        // 取自真实的《生产入库单》原始返回：字段没有聚在一起，且「数量」因申请/实发两列出现两次
        GroupInfo[] structuralList = {
                group(line(item("单据号", null, "0000896"))),
                group(line(item("物料名称", null, "150产品"))),
                group(line(item("日期", null, "2026年9月19日"))),
                group(line(item("单据类型", null, "生产入库单"))),
                group(line(item("数量", null, "5388"))),
                group(line(item("数量", null, "5388"))),
                group(line(item("物料编码", null, "不存在"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals("生产入库单", result.getDocumentType().getValue());
        assertEquals("0000896", result.getDocumentNo().getValue());
        assertEquals(1, result.getItems().size(), "字段被拆开也要装配成 1 行，不能整单丢掉");
        OcrItem first = result.getItems().get(0);
        assertEquals("150产品", first.getMaterialName().getValue());
        assertEquals(5388L, first.getQuantity().getValue());
    }

    @Test
    @DisplayName("★ 拆散形态 + 多行：每遇到一次物料名称就开新行")
    void assemblesMultipleRowsSplitAcrossEntries() {
        GroupInfo[] structuralList = {
                group(line(item("单据号", null, "0001362"))),
                group(line(item("物料名称", null, "三氯氢硅"))),
                group(line(item("数量", null, "1426"))),
                group(line(item("物料名称", null, "电石"))),
                group(line(item("数量", null, "3591"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals(2, result.getItems().size());
        assertEquals("三氯氢硅", result.getItems().get(0).getMaterialName().getValue());
        assertEquals("电石", result.getItems().get(1).getMaterialName().getValue());
    }

    @Test
    @DisplayName("★ 拆散形态下「物料编码=不存在」仍不得自己开一行")
    void loneCodeStillDoesNotOpenARowWhenFieldsAreSplit() {
        GroupInfo[] structuralList = {
                group(line(item("物料编码", null, "不存在"))),
                group(line(item("物料名称", null, "150催化剂"))),
                group(line(item("数量", null, "1"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals(1, result.getItems().size());
        assertEquals("150催化剂", result.getItems().get(0).getMaterialName().getValue());
    }

    @Test
    @DisplayName("聚拢形态下每行的编码归各自的行（编码在名称之前，不能挂到上一行）")
    void assignsSplitCodesToTheirOwnRows() {
        GroupInfo table = group(
                line(item("物料编码", null, "231"), item("物料名称", null, "三不硫"), item("数量", null, "4173")),
                line(item("物料编码", null, "232"), item("物料名称", null, "四不硫"), item("数量", null, "4736")));

        RecognizeResult result = mapper.map(new GroupInfo[]{table});

        assertEquals(2, result.getItems().size());
        assertEquals("231", result.getItems().get(0).getMaterialCode().getValue());
        assertEquals("232", result.getItems().get(1).getMaterialCode().getValue(),
                "第二行的编码若挂到第一行，两行的编码就会串位");
    }

    @Test
    @DisplayName("「不存在」这类占位值不是编码，必须丢弃 —— 否则会污染结果")
    void dropsPlaceholderCodeValues() {
        GroupInfo[] structuralList = {
                group(line(item("物料名称", null, "电石"), item("数量", null, "2380"))),
                group(line(item("物料编码", null, "不存在"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals(1, result.getItems().size());
        assertNull(result.getItems().get(0).getMaterialCode().getValue());
    }

    @Test
    @DisplayName("同一行内数量出现两次（申请/实发）取先出现的那个")
    void keepsFirstQuantityWithinARow() {
        GroupInfo[] structuralList = {
                group(line(item("物料名称", null, "电石"))),
                group(line(item("数量", null, "2380"))),
                group(line(item("数量", null, "1380"))),
        };

        RecognizeResult result = mapper.map(structuralList);

        assertEquals(1, result.getItems().size());
        assertEquals(2380L, result.getItems().get(0).getQuantity().getValue());
    }
}
