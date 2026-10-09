package com.ts.travelserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 同类花费参考的定时重算。
 *
 * <h3>为什么要定时</h3>
 * 参考值的窗口是「近 12 个月」，每天都在往前挪，昨天算出来的数今天就偏了。
 * 靠人想起来点一次（{@code POST /config/cost-ref/refresh}）要么忘，
 * 要么在不该刷的时候刷——它本职是缓存，不该要求人记得去照顾。
 *
 * <h3>为什么放在凌晨 2 点</h3>
 * 跟月度洞察那条任务错开：洞察挂 0 点，两边都要全表扫单据，
 * 挤在一起跑会让监控图上出现一段莫名的慢。参考值这类纯计算放后半夜最合适，白天没人等它。
 *
 * <h3>失败处理</h3>
 * 整个动作包在 try 里。参考值本质是缓存，这次没重算成功，
 * 下次自动再来；不能因为一次数据库抖动把服务拖垮或者刷一屏日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostRefJob {

    private final CostRefService costRefService;

    /**
     * 每天凌晨 2 点重算一次。cron 写在配置里，现场想关掉或调密都不用改代码。
     */
    @Scheduled(cron = "${travel.cost-ref.refresh-cron:0 0 2 * * *}", zone = "Asia/Shanghai")
    public void autoRefresh() {
        run("定时任务");
    }

    /**
     * 服务起来补一次：覆盖「半夜关机」的场景。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 到这一步数据源与连接池都就绪了，不会白白跑一趟。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        run("启动补跑");
    }

    private void run(String tag) {
        try {
            costRefService.refresh();
            log.info("[参考值] {}重算完成", tag);
        } catch (Exception e) {
            log.warn("[参考值] {}重算失败，等下次再试：{}", tag, e.getMessage());
        }
    }
}
