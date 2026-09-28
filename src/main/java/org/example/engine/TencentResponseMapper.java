package org.example.engine;

import com.tencentcloudapi.ocr.v20181119.models.GroupInfo;
import com.tencentcloudapi.ocr.v20181119.models.ItemInfo;
import com.tencentcloudapi.ocr.v20181119.models.LineInfo;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.dto.FieldValue;
import org.example.dto.OcrItem;
import org.example.dto.RecognizeResult;
import org.springframework.util.StringUtils;

/**
 * 腾讯云 {@code ExtractDocMulti} 响应 → 本服务 {@link RecognizeResult} 的映射。
 *
 * <p>从引擎里单独拆出来，是因为「怎么调用」和「怎么理解返回」是两件独立的事：
 * 前者依赖 SDK 与网络，后者是纯函数，可以脱离密钥与网络完整单测
 * —— 而映射恰好是接入期最容易出错、也最难靠肉眼核对的部分。
 *
 * <p><b>置信度恒为 null</b>：该接口不返回逐字段置信度，详见
 * {@link TencentOcrEngine} 类注释的「实现约束 1」。
 *
 * <h3>实测返回结构（2026-09-28，真实《物资领料单》）</h3>
 *
 * <pre>
 * StructuralList 的每一项是「一组同构的字段」，而不是「一行单据」：
 *
 *   [0] Groups=1 → [单据号=0031015]
 *   [1] Groups=1 → [单据类型=物资领料单]
 *   [2] Groups=1 → [日期=2026年9月7日]      ← 同一字段可能重复出现多个 entry
 *   [3] Groups=1 → [日期=2026年9月7日]
 *   [4] Groups=1 → [物料编码=不存在]         ← 单个键值对，见下
 *   [5] Groups=2 → [物料名称=150催化剂][数量=1]
 *                  [物料名称=150催化剂][数量=1]
 * </pre>
 *
 * <p>两点关键观察，直接决定了下面的映射规则：
 * <ol>
 *   <li><b>模型的分组方式不稳定</b>：有时把一行的字段聚在同一个 entry 里，
 *       有时又把每个字段各吐成一个 entry（同一批入库单两种都出现过）。
 *       因此拍平后按「出现顺序」装配，不能依赖 entry 的边界 —— 详见 {@link #buildItems}。</li>
 *   <li><b>单键值对的 entry 不代表表格行</b>。<br>
 *       空白列会被模型单独吐成 {@code [物料编码=不存在]} 这样的占位值。
 *       让它开启新行就会凭空多出一条「编码=不存在、名称/数量全空」的脏行项目
 *       —— 这是实测踩到的真实缺陷，由 {@link #buildItems} 中「编码不自己开行」的规则拦住。</li>
 * </ol>
 *
 * <p><b>未解决</b>：「不存在」这类占位值本身没有被过滤掉（无法与合法值区分），
 * 必须在业务侧靠「物料编码查主数据」拦下 —— 与设计文档的约定一致。
 * 前端也确实丢弃了识别出的物料编码，一律从主数据反查。
 */
public class TencentResponseMapper {

    // ------------------------------------------------------------------
    // 字段别名表
    //
    // 正常路径下腾讯云会把 ItemNames 里的名字原样回填到 Key.autoName，
    // 因此精确匹配就够用。别名表是容错网：模版升级、返回 ConfigName、
    // 或多带单位后缀（如「数量(KG)」）时不至于整单识别不出。
    // ------------------------------------------------------------------
    private static final List<String> DOC_TYPE_ALIASES = List.of("单据类型", "单据名称", "类型", "单据类别");
    private static final List<String> DOC_NO_ALIASES = List.of("单据号", "单号", "单据编号", "编号");
    private static final List<String> DOC_DATE_ALIASES = List.of("日期", "单据日期", "制单日期", "业务日期");
    private static final List<String> MATERIAL_CODE_ALIASES =
            List.of("物料编码", "物料编号", "物料号", "物料代码", "编码");
    private static final List<String> MATERIAL_NAME_ALIASES =
            List.of("物料名称", "物料描述", "物料品名", "品名");

    /**
     * 数量别名。
     *
     * <p>纸质单上「申请数量」与「实发数量」是两列，本服务契约只有一个「数量」。
     * 两者在拆散形态下都会被识别成「数量」，已无从区分；在聚拢形态下模型会原样回填
     * 我们请求的名字，此时本列表的顺序即优先级（实发数量优先 —— 实发才是仓库实际出库数，
     * 与领料汇总口径一致。⚠️ 业务假设，待确认）。
     */
    private static final List<String> QUANTITY_ALIASES =
            List.of("实发数量", "领料数量", "入库数量", "数量", "申请数量", "重量", "净重");

    /**
     * 日期：覆盖模型实际返回过的几种写法 ——
     * {@code 2026年9月7日} / {@code 2026-09-22} / {@code 2026/9/7} / {@code 26.9.7}。
     * 末尾的「日」可有可无。
     */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{2,4})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*日?");

    private final String engineName;

    public TencentResponseMapper(String engineName) {
        this.engineName = engineName;
    }

    /** 映射入口：结构化列表 → 识别结果 */
    public RecognizeResult map(GroupInfo[] structuralList) {
        return toResult(flatten(structuralList));
    }

    /**
     * 把腾讯云的三层嵌套拍平成「一行一条记录」。
     *
     * <pre>
     * StructuralList[]                      一个分组（对应单据的一块区域）
     *   └ GroupInfo.Groups[]                分组内的「每一行」（SDK 注释原文）
     *       └ LineInfo.Lines[]              该行内的各个单元格
     *           └ ItemInfo{ Key, Value }    一个键值对
     *
     * 字段名优先取 Key.autoName（识别出的名字），为空回退 Key.configName（模版配置名）。
     * 值为空的丢弃 —— 对后续映射没有信息量。
     * </pre>
     *
     * <p>刻意拍成「<b>有序键值对</b>」而不是「一行一条 Map」：模型对同一批单据会给出两种形态 ——
     * 有时把字段聚在一个 Line 里返回，有时又把每个字段各吐成一个 entry（实测入库单两种都出现过）。
     * 按 Line 分组会把后一种形态整批丢掉，所以装配统一放到 {@link #toResult} 里做。
     */
    public List<Field> flatten(GroupInfo[] structuralList) {
        List<Field> fields = new ArrayList<>();
        if (structuralList == null) {
            return fields;
        }

        for (GroupInfo group : structuralList) {
            if (group == null || group.getGroups() == null) {
                continue;
            }
            for (LineInfo line : group.getGroups()) {
                if (line == null || line.getLines() == null) {
                    continue;
                }
                for (ItemInfo item : line.getLines()) {
                    if (item == null || item.getKey() == null || item.getValue() == null) {
                        continue;
                    }
                    String name = firstNonBlank(item.getKey().getAutoName(), item.getKey().getConfigName());
                    String value = item.getValue().getAutoContent();
                    if (name == null || !StringUtils.hasText(value)) {
                        continue;
                    }
                    fields.add(new Field(name.trim(), value.trim()));
                }
            }
        }
        return fields;
    }

    /** 拍平后的一个键值对（保留出现顺序） */
    public record Field(String name, String value) {
    }

    /**
     * 从有序键值对里抽出单据头与物料行。
     *
     * <p>单据头字段与物料字段<b>名字不重叠</b>，所以不必先判定哪些是表头：表头字段各取第一个非空值。
     */
    public RecognizeResult toResult(List<Field> fields) {
        RecognizeResult result = new RecognizeResult();
        result.setEngine(engineName);

        // 置信度恒为 null：腾讯云该接口不返回逐字段置信度
        result.setDocumentType(field(headerValue(fields, DOC_TYPE_ALIASES)));
        result.setDocumentNo(field(headerValue(fields, DOC_NO_ALIASES)));
        result.setDocDate(field(normalizeDate(headerValue(fields, DOC_DATE_ALIASES))));
        result.setItems(buildItems(fields));
        return result;
    }

    /** 表头字段：取第一个命中的非空值 */
    private String headerValue(List<Field> fields, List<String> aliases) {
        for (Field f : fields) {
            if (matchAlias(f.name(), aliases)) {
                return f.value();
            }
        }
        return null;
    }

    /**
     * 把有序键值对装配成物料行。
     *
     * <p>难点是模型对同一批单据会给出两种形态（实测入库单两种都有），且<b>字段顺序还不一样</b>：
     * <pre>
     *   聚拢形态：[物料编码=231][物料名称=三不硫][数量=4173]   一行一个 entry，编码在名称之前
     *   拆散形态：[物料名称=150产品] … [数量=5388] … [物料编码=不存在]   各字段各占一个 entry
     * </pre>
     * 按下面的规则装配，两种形态都能还原：
     * <ol>
     *   <li><b>物料名称开启新的一行</b> —— 每种物料各占一行，名称就是行的标识</li>
     *   <li>数量挂到当前行；还没有行时它也可以开一行（免得整单没名称时把数据丢掉）。
     *       同一行内数量<b>取先出现的</b> —— 纸质单的「申请数量」「实发数量」都会被识别成「数量」，
     *       两者已无从区分</li>
     *   <li>物料编码<b>不自己开启新行</b>：没有当前行时先记下、留给接下来那一行（聚拢形态的编码在名称之前）；
     *       当前行已有编码时同样留给下一行。让它自己开行的话，「整列为空」时模型吐的
     *       {@code [物料编码=不存在]} 就会凭空造出一条「编码=不存在、其余全空」的脏行
     *       —— 这是实测踩到的真实缺陷</li>
     *   <li>编码值必须<b>像编码</b>（字母数字与 {@code . _ -}），否则丢弃 ——
     *       「不存在」这类中文占位值不是编码，留着只会污染结果</li>
     * </ol>
     */
    private List<OcrItem> buildItems(List<Field> fields) {
        List<ItemBuilder> rows = new ArrayList<>();
        ItemBuilder current = null;
        // 出现在「物料名称」之前的编码：属于接下来开启的那一行（聚拢形态每行都以编码开头）
        String pendingCode = null;

        for (Field f : fields) {
            if (matchAlias(f.name(), MATERIAL_NAME_ALIASES)) {
                current = new ItemBuilder();
                current.materialName = f.value();
                current.materialCode = pendingCode;
                pendingCode = null;
                rows.add(current);
            } else if (matchAlias(f.name(), QUANTITY_ALIASES)) {
                if (current == null) {
                    current = new ItemBuilder();
                    current.materialCode = pendingCode;
                    pendingCode = null;
                    rows.add(current);
                }
                // 同名的「数量」（拆散形态下申请/实发都会叫「数量」）取先出现的；
                // 但若模型给出的是不同名字（申请数量 / 实发数量），则按别名表的优先级取舍 ——
                // 实发才是仓库实际出库数，与领料汇总口径一致
                int rank = aliasRank(f.name(), QUANTITY_ALIASES);
                if (current.quantity == null || rank < current.quantityRank) {
                    current.quantity = f.value();
                    current.quantityRank = rank;
                }
            } else if (matchAlias(f.name(), MATERIAL_CODE_ALIASES)) {
                String code = asMaterialCode(f.value());
                if (code == null) {
                    continue;
                }
                if (current != null && current.materialCode == null) {
                    current.materialCode = code;
                } else {
                    pendingCode = code;
                }
            }
        }

        List<OcrItem> items = new ArrayList<>();
        for (ItemBuilder row : rows) {
            items.add(new OcrItem(field(row.materialCode), field(row.materialName),
                    field(parseQuantity(row.quantity))));
        }
        return items;
    }

    /**
     * 取物料编码值：只收「像编码」的（字母数字与 {@code . _ -}）。
     * 「整列为空」时模型会吐「不存在」这类中文占位值，那不是编码；收了只会在行里制造噪声。
     */
    private String asMaterialCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        return value.matches("[A-Za-z0-9._\\-]+") ? value : null;
    }

    /** 装配中的一行 */
    private static final class ItemBuilder {
        private String materialCode;
        private String materialName;
        private String quantity;
        /** 当前数量命中的别名优先级（越小越优先） */
        private int quantityRank = Integer.MAX_VALUE;
    }

    /**
     * 键名归一化后比较：去空白、去全角/半角括号后缀、去尾随符号。
     * 这样「数量」「数量(KG)」「数量（kg）」视为同一个字段。
     */
    private boolean matchAlias(String key, List<String> aliases) {
        String normalized = normalizeKey(key);
        return aliases.stream().map(TencentResponseMapper::normalizeKey).anyMatch(normalized::equals);
    }

    /** 命中的别名在列表中的位置（越小优先级越高）；未命中返回一个很大的值 */
    private int aliasRank(String key, List<String> aliases) {
        String normalized = normalizeKey(key);
        for (int i = 0; i < aliases.size(); i++) {
            if (normalized.equals(normalizeKey(aliases.get(i)))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.replaceAll("[\\s　]", "")
                .replaceAll("[（(].*?[)）]", "")
                .replaceAll("[:：*]+$", "");
    }

    /**
     * 数量归一化。
     *
     * <p>腾讯云返回的是文本，可能夹带千分位逗号或单位（「4,173 KG」）。
     * 能解析成数字就返回数字 —— 后端要拿它复用现有导入校验；
     * 解析不出就<b>原样返回文本</b>，交人工在前端修正，不猜也不编造。
     */
    public Object parseQuantity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String cleaned = raw.replaceAll("[,，\\s]", "")
                .replaceAll("(?i)(kg|千克|公斤|吨|t|g)$", "");
        try {
            if (cleaned.matches("[+-]?\\d+")) {
                return Long.parseLong(cleaned);
            }
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return raw.trim();
        }
    }

    /**
     * 日期归一化为 {@code yyyy-MM-dd}。
     *
     * <p>模型返回的日期格式五花八门：「2026年9月7日」「2026-09-22」「2026/9/7」「26年9月7日」都出现过。
     * 业务侧统一要 {@code yyyy-MM-dd}（列表页的日期筛选按这个比字符串，落库也是 date 列），
     * 所以在这里收口 —— 让前端和后端各写一份解析，迟早会得出两种结果。
     *
     * <p>两位年份按「就近」补全为 20xx（如「26年」→ 2026）。<b>注意</b>：年份本身被模型读错时
     * （实测出现过把「26年」读成「24年」），这里会照样补全成 2024 这样一个合法日期 ——
     * 格式正确反而更难察觉，所以年份仍需人工核对。
     *
     * <p>解析不出就<b>原样返回</b>（如把涂鸦当日期读出的「20458」）：那说明这一格本来就要人工改，
     * 返回 null 反而把「模型到底给了什么」这条线索丢了。
     */
    public String normalizeDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String text = raw.trim();

        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        int year = Integer.parseInt(matcher.group(1));
        if (year < 100) {
            year += (year < 70) ? 2000 : 1900;
        }

        try {
            return LocalDate.of(year,
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))).toString();
        } catch (DateTimeException e) {
            // 非法日期（如 2 月 30 日）同样原样返回，交人工
            return text;
        }
    }

    private static FieldValue field(Object value) {
        if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
            return FieldValue.empty();
        }
        return FieldValue.of(value instanceof String text ? text.trim() : value);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
