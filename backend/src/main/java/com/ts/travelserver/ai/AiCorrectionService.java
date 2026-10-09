package com.ts.travelserver.ai;

import com.ts.travelserver.entity.AiCorrection;
import com.ts.travelserver.mapper.AiCorrectionMapper;
import com.ts.travelserver.vo.AiStatusVO.FieldCorrectionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 人工修正留痕（ai_correction）的写入与读取。
 *
 * <p>写这一张表的目的很直接：<b>想知道 AI 给的哪个值最不靠谱，只能看人改了什么。</b>
 * 采纳率只能说「有没有被采用」，改了什么才指向具体该改哪句 prompt。
 *
 * <p>两条刻意的取舍：
 * <ul>
 *   <li><b>一处改动一行</b>。一张票四个字段全改了就写四行，
 *       不做「一行存一个 JSON」——按字段 group by 是这张表唯一的用法，
 *       塞成 JSON 之后每次统计都要全表扫着解析。</li>
 *   <li><b>写入失败绝不影响业务</b>。票据已经存进去了，因为留痕没写成就把整单回滚，
 *       那是把审计数据看得比业务数据还重。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCorrectionService {

    /** 字段中文名，只给页面用 */
    private static final Map<String, String> FIELD_TEXT = Map.of(
            "invoiceNo", "发票号",
            "amount", "票面金额",
            "invoiceDate", "开票日期",
            "seller", "销售方",
            "reason", "出差事由",
            "tripType", "出差类型",
            "estTraffic", "预估交通费",
            "estHotel", "预估住宿费",
            "exceedReason", "超标说明"
    );

    private final AiCorrectionMapper mapper;

    /**
     * 票据 OCR：把识别出的四项与员工最终确认的值逐项比对，改了的各写一行。
     *
     * <p>只有 ocrFlag = 1（确实是 OCR 代填）才记：手工录入的票没有「系统给的值」，
     * 记进去只会把统计稀释掉。
     *
     * @param ocr      OCR 识别出来的四项，任一项为 null 表示当时没认出来
     * @param actual   员工最终确认入库的四项
     */
    public void recordOcr(String invoiceNo, Long operatorId, OcrValues ocr, OcrValues actual) {
        if (ocr == null || actual == null || !ocr.hasAny()) {
            return;
        }
        write(AiCorrection.SCENE_OCR_INVOICE, "INVOICE", invoiceNo, operatorId,
                "invoiceNo", ocr.invoiceNo, actual.invoiceNo);
        write(AiCorrection.SCENE_OCR_INVOICE, "INVOICE", invoiceNo, operatorId,
                "amount", ocr.amount, actual.amount);
        write(AiCorrection.SCENE_OCR_INVOICE, "INVOICE", invoiceNo, operatorId,
                "invoiceDate", ocr.invoiceDate, actual.invoiceDate);
        write(AiCorrection.SCENE_OCR_INVOICE, "INVOICE", invoiceNo, operatorId,
                "seller", ocr.seller, actual.seller);
    }

    /**
     * 填写助手：建议值 vs 最终提交值。
     *
     * <p>与 {@code AiLogService.markAdviceAdopted} 是同一件事的两个面：
     * 那边写「这一条建议整体有没有被采纳」，这边写「具体哪个字段被改成了什么」。
     */
    public void recordAdvice(String bizNo, Long operatorId, List<Item> items) {
        if (bizNo == null || bizNo.isBlank() || items == null) {
            return;
        }
        for (Item it : items) {
            if (it == null || it.field == null || it.field.isBlank()) {
                continue;
            }
            write(AiCorrection.SCENE_APPLY_ASSIST, "TRIP_APPLY", bizNo, operatorId,
                    it.field, it.suggested, it.finalValue);
        }
    }

    /**
     * 窗口内被改得最多的字段。
     */
    public List<FieldCorrectionVO> topFields(int days, int size) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(1, days));
        List<FieldCorrectionVO> rows;
        try {
            rows = mapper.topFields(from, Math.max(1, size));
        } catch (Exception e) {
            log.warn("人工修正聚合失败：{}", e.getMessage());
            return List.of();
        }
        List<FieldCorrectionVO> out = new ArrayList<>();
        for (FieldCorrectionVO r : rows) {
            r.setFieldText(FIELD_TEXT.getOrDefault(r.getField(), r.getField()));
            out.add(r);
        }
        return out;
    }

    // ---------- 内部 ----------

    /**
     * 写一行。两个值「实质相同」就不写——识别出来的 1560.00 与人填的 1560 是一回事。
     */
    private void write(String scene, String bizType, String bizNo, Long operatorId,
                       String field, String oldValue, String newValue) {
        if (!diff(oldValue, newValue)) {
            return;
        }
        try {
            AiCorrection row = new AiCorrection();
            row.setScene(scene);
            row.setBizType(bizType);
            row.setBizNo(bizNo);
            row.setFieldName(field);
            row.setOldValue(clip(oldValue));
            row.setNewValue(clip(newValue));
            row.setOperatorId(operatorId);
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("人工修正留痕写入失败（不影响业务）：{}", e.getMessage());
        }
    }

    /**
     * 是否有实质差异。金额按数值比（抹掉尾零与千分位），其余按去空白后的字符串比。
     *
     * <p>old 为 null 表示系统当时没给出这个值，不算「被人改掉」，不记。
     */
    static boolean diff(String oldValue, String newValue) {
        if (oldValue == null || oldValue.isBlank()) {
            return false;
        }
        String a = oldValue.trim();
        String b = newValue == null ? "" : newValue.trim();
        if (a.equals(b)) {
            return false;
        }
        BigDecimal da = asMoney(a);
        BigDecimal db = asMoney(b);
        if (da != null && db != null) {
            return da.compareTo(db) != 0;
        }
        return true;
    }

    private static BigDecimal asMoney(String s) {
        try {
            return new BigDecimal(s.replace(",", "")).stripTrailingZeros();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String v) {
        if (v == null) {
            return null;
        }
        return v.length() > 255 ? v.substring(0, 255) : v;
    }

    /** 填写助手的一条建议采纳情况 */
    public record Item(String field, String suggested, String finalValue) {
    }

    /** OCR 识别值 / 员工确认值，两边同结构 */
    public record OcrValues(String invoiceNo, String amount, String invoiceDate, String seller) {

        boolean hasAny() {
            return hasText(invoiceNo) || hasText(amount) || hasText(invoiceDate) || hasText(seller);
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }
}
