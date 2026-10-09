package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.entity.AiLog;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.ConfigLog;
import com.ts.travelserver.entity.CostRef;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.entity.MonthlyInsight;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.PaymentRecord;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.entity.TripStandard;
import com.ts.travelserver.mapper.AiLogMapper;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.ConfigLogMapper;
import com.ts.travelserver.mapper.CostRefMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.EvalResultMapper;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.LoanRecordMapper;
import com.ts.travelserver.mapper.MonthlyInsightMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.PaymentRecordMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.mapper.TripStandardMapper;
import com.ts.travelserver.vo.BrowseVO;
import com.ts.travelserver.vo.TableVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 全表只读浏览（系统管理员专用）。
 *
 * <p>只做 SELECT，没有任何写入口——哪怕是一张账面上的派生表。理由：
 * 库里有几处「改了不报错、错了没人知道」的地方，页面看不出区别。
 * 举两个最容易踩的：
 * <ul>
 *   <li>{@code partner.total_invest / total_output} 是付款与结果回填时增量累加出来的冗余值，
 *       AnalyticsService 的对象页直接读它，不再全表汇总。手改一个数字，对象投入产出页跟着错，不报异常。</li>
 *   <li>{@code approval_record.snapshot}、{@code eval_result.explain_json} 是 JSON 字符串列，
 *       随便塞一段进去，读到的时候才炸，而那时候已经找不到是谁写错的。</li>
 * </ul>
 *
 * <p>表名<strong>必须</strong>命中 {@link #TABLES} 白名单才能查。
 * 17 张表各自注册了一个 BaseMapper，分页查询走的就是 MyBatis-Plus 自己的 selectPage，
 * 没有一处拼接表名或列名字符串去构造 SQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowseService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 单页上限。浏览是为了看结构、定位某一行，不是导出数据 */
    private static final long MAX_PAGE_SIZE = 100L;

    /** 单元格超长文本的截断长度，超过这段就用省略号收尾 */
    private static final int MAX_TEXT = 240;

    /** 屏蔽后显示的样子 */
    private static final String MASK = "******";

    private final PartnerMapper partnerMapper;
    private final DeptMapper deptMapper;
    private final SysUserMapper sysUserMapper;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final TripStandardMapper tripStandardMapper;
    private final CostRefMapper costRefMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final EvalResultMapper evalResultMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final InvoiceMapper invoiceMapper;
    private final ExpenseReportMapper expenseReportMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final BizResultMapper bizResultMapper;
    private final ConfigLogMapper configLogMapper;
    private final MonthlyInsightMapper monthlyInsightMapper;
    private final AiLogMapper aiLogMapper;

    /** 表注册表。key 是表名，也是浏览接口的路径参数 */
    private final Map<String, TableDef> TABLES = new LinkedHashMap<>();

    @PostConstruct
    void registerTables() {
        put("partner", "往来对象", "档案", Partner.class, partnerMapper, null, null,
                "累计投入与产出是回填时增量累加的结果，不是随时重算的数，别照着它校对单据");
        put("dept", "部门", "档案", Dept.class, deptMapper, null, null,
                "月度预算与差标等级的调整归管理层，这里不让改");
        put("sys_user", "用户账号", "档案", SysUser.class, sysUserMapper,
                Set.of("password"), Set.of("bankAccount"),
                "密码不以任何形式返回；银行账号按财务侧口径只显示前 3 后 4");
        put("trip_standard", "差标版本", "标准", TripStandard.class, tripStandardMapper, null, null,
                "同一组组合有多版历史，当前生效的是生效日期不晚于今天、且日期最靠后的那一版");
        put("cost_ref", "同类花费参考", "派生", CostRef.class, costRefMapper, null, null,
                "定时任务每天凌晨 2 点重算，近 12 个月没样本的组合会被删掉");
        put("trip_apply", "出差申请单", "单据", TripApply.class, tripApplyMapper, null, null,
                "status 只能由业务流转写入（草稿提交、审批、付款结案、手工关闭），不提供修改");
        put("trip_itinerary", "行程段", "单据", TripItinerary.class, tripItineraryMapper, null, null, null);
        put("approval_record", "审批留痕", "留痕", ApprovalRecord.class, approvalRecordMapper, null, null,
                "snapshot 是提交那一瞬间的整单快照，JSON 字符串");
        put("expense_report", "报销单", "单据", ExpenseReport.class, expenseReportMapper, null, null,
                "三个金额必须自洽：net_amount = total_amount - offset_amount");
        put("invoice", "票据影像", "单据", Invoice.class, invoiceMapper, null, null,
                "img_path 只是文件相对路径，不是文件内容；缺的那几张看「缺票登记」");
        put("biz_result", "业务结果", "单据", BizResult.class, bizResultMapper, null, null,
                "回填时会同步累加往来对象的累计产出");
        put("payment_record", "付款记录", "单据", PaymentRecord.class, paymentRecordMapper, null, null,
                "管理层费效分析的投入口径就是这张表的实付金额合计");
        put("loan_record", "预借与冲抵", "单据", LoanRecord.class, loanRecordMapper, null, null, null);
        put("eval_result", "评价结果", "派生", EvalResult.class, evalResultMapper, null, null,
                "explain_json 是四维明细，JSON 字符串");
        put("config_log", "配置留痕", "留痕", ConfigLog.class, configLogMapper, null, null,
                "应用层只追加，不修改不删除");
        put("monthly_insight", "月度洞察报告", "派生", MonthlyInsight.class, monthlyInsightMapper, null, null,
                "一个月份一行，可被覆盖；input_hash 与当前数据不一致时页面会标「数据已更新」");
        put("ai_log", "模型调用日志", "留痕", AiLog.class, aiLogMapper, null, null, null);
    }

    /**
     * 可选的表清单。rowCount 现查，columns 从实体属性推导，空表也列得出来。
     */
    public List<TableVO> tables() {
        List<TableVO> list = new ArrayList<>();
        for (Map.Entry<String, TableDef> entry : TABLES.entrySet()) {
            TableDef def = entry.getValue();
            TableVO vo = new TableVO();
            vo.setName(entry.getKey());
            vo.setLabel(def.label());
            vo.setPk(def.pk());
            vo.setGroup(def.group());
            vo.setNote(def.note());
            vo.setColumns(def.columns());
            vo.setRowCount(def.mapper().selectCount(null));
            list.add(vo);
        }
        return list;
    }

    /**
     * 浏览某一张表的一页数据。
     *
     * @param name    表名，必须在注册表里
     * @param pageNum 页码，从 1 开始
     * @param size    每页条数，上限 100
     * @param id      可选，只取主键等于它的那一行。排查问题时直接跳到具体记录用
     */
    public BrowseVO browse(String name, long pageNum, long size, Long id) {
        TableDef def = TABLES.get(name);
        if (def == null) {
            throw BusinessException.paramError("没有叫「" + name + "」的表，可以浏览的范围见 /admin/tables");
        }
        long safeNum = Math.max(1, pageNum);
        long safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        if (id != null && id <= 0) {
            throw BusinessException.paramError("请填写正确的记录 ID");
        }

        IPage<?> page = selectPage(def, safeNum, safeSize, id);

        BrowseVO vo = new BrowseVO();
        vo.setTable(toTableVO(name, def));
        vo.setColumns(def.columns());
        vo.setTotal(page.getTotal());
        vo.setPageNum(page.getCurrent());
        vo.setPageSize(page.getSize());
        page.getRecords().forEach(row -> vo.getRows().add(toMap(row, def)));
        return vo;
    }

    /**
     * 通用分页查询。
     *
     * <p>17 张表各自实体不同，泛型擦除后没法用一个带具体类型的东西横着查，
     * 所以这里用 raw 写法：表名与排序列都来自 {@link #TABLES} 这份常量，
     * 不是调用方传进来拼进 SQL 的，改出一个 ${} 拼接才是真正要防的事。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private IPage<?> selectPage(TableDef def, long pageNum, long pageSize, Long id) {
        QueryWrapper wrapper = new QueryWrapper<>()
                .eq(id != null, def.pk(), id)
                .orderByDesc(def.pk());
        return def.mapper().selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 一行数据摊平成「列名 -> 值」。同时做三件事：丢掉不该返回的列、
     * 给敏感列打码、把时间与超长文本转成适合直接显示的样子。
     */
    private Map<String, Object> toMap(Object row, TableDef def) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String column : def.columns()) {
            if (def.dropped() != null && def.dropped().contains(column)) {
                continue;
            }
            Object value;
            try {
                value = def.readerFor(column).getReadMethod().invoke(row);
            } catch (ReflectiveOperationException e) {
                // 某个属性的 getter 抛异常不应该让整页看不到数据
                log.warn("读取 {} 的 {} 列失败", def.label(), column, e);
                continue;
            }
            map.put(column, readable(column, value, def.masked()));
        }
        return map;
    }

    /**
     * 值转成页面能直接渲染的形态：时间格式化、字符串截断、敏感列打码。
     * BigDecimal 保留数字本身，前端表格要对齐显示小数位。
     */
    private Object readable(String column, Object value, Set<String> masked) {
        if (masked != null && masked.contains(column)) {
            return mask(value);
        }
        if (value instanceof LocalDateTime time) {
            return time.format(DATE_TIME);
        }
        if (value instanceof LocalDate date) {
            return date.format(DATE);
        }
        if (value instanceof String text && text.length() > MAX_TEXT) {
            return text.substring(0, MAX_TEXT) + "…（已截断）";
        }
        return value;
    }

    /** 银行账号之类：前 3 后 4，中间的看不出来。与 SystemController 的脱敏口径一致 */
    private String mask(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        if (text.length() <= 7) {
            return MASK;
        }
        return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
    }

    private TableVO toTableVO(String name, TableDef def) {
        TableVO vo = new TableVO();
        vo.setName(name);
        vo.setLabel(def.label());
        vo.setPk(def.pk());
        vo.setGroup(def.group());
        vo.setNote(def.note());
        vo.setColumns(def.columns());
        vo.setRowCount(def.mapper().selectCount(null));
        return vo;
    }

    private void put(String name, String label, String group, Class<?> entityClass, BaseMapper<?> mapper,
                     Set<String> dropped, Set<String> masked, String note) {
        try {
            List<String> columns = columnsOf(entityClass, dropped);
            TABLES.put(name, new TableDef(label, group, note, entityClass, mapper, "id",
                    dropped == null ? Set.of() : dropped,
                    masked == null ? Set.of() : masked,
                    columns, readersOf(entityClass)));
        } catch (IntrospectionException e) {
            // 实体写不出 getter 属于编码期问题，不该在运行到管理者打开页面时才炸，
            // 但注册发生在容器启动阶段，包一层让启动失败信息能指向具体这张表
            throw new IllegalStateException("浏览页注册失败，无法读取实体 " + entityClass.getSimpleName() + " 的属性", e);
        }
    }

    /**
     * 列名清单：实体属性名转下划线，丢掉屏蔽列，id 排在最前。
     */
    private static List<String> columnsOf(Class<?> entityClass, Set<String> dropped) throws IntrospectionException {
        Map<String, String> sorted = new TreeMap<>();
        for (PropertyDescriptor pd : Introspector.getBeanInfo(entityClass).getPropertyDescriptors()) {
            String column = toSnake(pd.getName());
            if ("class".equals(column) || column.isEmpty()) {
                continue;
            }
            if (dropped != null && dropped.contains(column)) {
                continue;
            }
            sorted.put(column, column);
        }
        List<String> columns = new ArrayList<>();
        if (sorted.containsKey("id")) {
            columns.add("id");
            sorted.remove("id");
        }
        columns.addAll(sorted.values());
        return columns;
    }

    /** 属性名到 getter 的映射，反射调用放在请求里要比每行列一次 Introspector 便宜得多 */
    private static Map<String, PropertyDescriptor> readersOf(Class<?> entityClass) throws IntrospectionException {
        Map<String, PropertyDescriptor> readers = new LinkedHashMap<>();
        for (PropertyDescriptor pd : Introspector.getBeanInfo(entityClass).getPropertyDescriptors()) {
            if (pd.getReadMethod() == null) {
                continue;
            }
            readers.put(toSnake(pd.getName()), pd);
        }
        return readers;
    }

    /** createdAt -> created_at */
    private static String toSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 一张表的注册信息。
     *
     * @param dropped 不返回的列，如 sys_user.password
     * @param masked  需要脱敏的列，如 bank_account
     */
    private record TableDef(String label, String group, String note, Class<?> entityClass, BaseMapper<?> mapper,
                            String pk, Set<String> dropped, Set<String> masked,
                            List<String> columns, Map<String, PropertyDescriptor> reader) {

        PropertyDescriptor readerFor(String column) {
            return reader.get(column);
        }
    }
}
