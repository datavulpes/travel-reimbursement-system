package com.ts.travelserver.controller;

import com.ts.travelserver.ai.DrillExplainService;
import com.ts.travelserver.ai.MonthlyInsightService;
import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.AnalyticsService;
import com.ts.travelserver.vo.DrillExplainVO;
import com.ts.travelserver.vo.DrillVO;
import com.ts.travelserver.vo.MonthlyInsightVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import com.ts.travelserver.vo.PartnerRoiVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 费效比分析与钻取（模块 04）。
 *
 * <p>只有管理层能看（设计报告 6.8 权限矩阵）。月份统一传 yyyy-MM，不传就按当前月。
 */
@RestController
@RequestMapping("/analytics")
@RequireRole(SysUser.ROLE_MANAGER)
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final MonthlyInsightService monthlyInsightService;
    private final DrillExplainService drillExplainService;

    /**
     * F-04 月报四个关键数字：总投入、总产出、费效比、可疑差旅占比。
     */
    @GetMapping("/summary")
    public R<MonthlyReportVO> summary(@RequestParam(required = false) String month) {
        return R.ok(analyticsService.summary(month));
    }

    /**
     * F-01 部门费效比月报，柱状图的数据源。
     */
    @GetMapping("/monthly")
    public R<MonthlyReportVO> monthly(@RequestParam(required = false) String month,
                                      @RequestParam(required = false) Long deptId) {
        return R.ok(analyticsService.monthly(month, deptId));
    }

    /**
     * F-02 归因钻取：部门 → 单据 → 票据 / 审批依据 / 业务结果。
     */
    @GetMapping("/drill")
    public R<DrillVO> drill(@RequestParam Long deptId,
                            @RequestParam(required = false) String month) {
        return R.ok(analyticsService.drill(deptId, month));
    }

    /**
     * F-03 对象投入产出对照。不传月份取累计值。
     */
    @GetMapping("/partner")
    public R<PartnerRoiVO> partner(@RequestParam(required = false) String month) {
        return R.ok(analyticsService.partner(month));
    }

    /**
     * 月度洞察：月报数字之上的一层解读，带环比归因分解。
     *
     * <p>刻意与 {@code /monthly} 分开：月报自己带的模板结论永远可用、不调模型，
     * 页面刷一次月报要连带查 6 个月画趋势图，洞察要是在月报里一起返回，
     * 切一次月份就是 6 次模型调用。分成两个接口后，趋势照旧只查统计，
     * 洞察只在人真的要看的时候读一次。
     *
     * <p><b>这个 GET 不会自动重算</b>：有就返回库里那份快照（含生成时间），
     * 一次都没有才现生成。月初到月底，同一天里反复刷新看到的是同一份——
     * 这样管理层能在同一份报告上对着讨论，不用担心数字自己动了。
     */
    @GetMapping("/insight")
    public R<MonthlyInsightVO> insight(@RequestParam(required = false) String month) {
        return R.ok(monthlyInsightService.read(month));
    }

    /**
     * 手动重新分析。
     *
     * <p>另开一个 POST 而不是给 {@code /insight} 加 {@code force=1}：
     * 这个动作要烧一次模型调用、还要覆盖库里那条记录，
     * 挂在一个幂等的 GET 上不合理，也不好在文档里解释清楚。
     */
    @PostMapping("/insight/refresh")
    public R<MonthlyInsightVO> insightRefresh(@RequestParam(required = false) String month) {
        return R.ok(monthlyInsightService.refresh(month));
    }

    /**
     * 勾掉 / 取消一条「下一步动作」。
     *
     * <p>单独一个接口，因为动作是跟着报告走的：勾一次就写回那个月的报告，
     * 重新分析之后动作会重排，旧的勾选跟着旧动作一起消失——
     * 不留一个"上次勾过了"的记号去冒充这一版做过事。
     *
     * @param code    动作代码，见 MonthlyInsightVO 的 ACT_* 常量
     * @param target  动作对象（部门名 / 出差类型名），面向全公司时传空串
     */
    @PostMapping("/insight/action")
    public R<Boolean> insightAction(@RequestParam String month,
                                    @RequestParam String code,
                                    @RequestParam(required = false) String target,
                                    @RequestParam(defaultValue = "true") boolean adopted) {
        return R.ok(monthlyInsightService.setActionAdopted(month, code, target, adopted));
    }

    /**
     * 钻取解释：这个部门这个月为什么是这个样子。
     *
     * <p>用 POST 而不是 GET，是为了让人一眼看出它不是一个幂等的查询：
     * 每调一次就会打一次模型。前端只在点了「看分析」之后调，
     * 切部门、切月份都不自动跑——主动冒出来的解释等于又一块没人要的文字。
     */
    @PostMapping("/drill/explain")
    public R<DrillExplainVO> drillExplain(@RequestParam Long deptId,
                                          @RequestParam(required = false) String month) {
        return R.ok(drillExplainService.explain(deptId, month));
    }

    /**
     * 这份洞察有没有用：人看完点一下「有帮助 / 没帮助」。
     *
     * <p>与上面「动作勾选」是两件事，别合并：动作是「报告让我去查的那件事做没做」，
     * 这里是「这份报告本身值不值得看」。前者是业务台账，后者是对 AI 的评价，
     * 落的地方也不同——写回当月那条 {@code MONTHLY_INSIGHT} 留痕的 accepted。
     *
     * <p>可以反复点，以最后一次为准：改主意是常事，
     * 而这里记的是「人当下的判断」，不是一次性的投票。
     */
    @PostMapping("/insight/feedback")
    public R<Boolean> insightFeedback(@RequestParam String month,
                                      @RequestParam(defaultValue = "true") boolean helpful) {
        return R.ok(monthlyInsightService.setFeedback(month, helpful));
    }
}
