package com.ts.travelserver.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月度洞察报告的定时更新。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>当月</b>：每天跑一次。当月的数据每天都在长，昨天的解读今天就过时了。</li>
 *   <li><b>上月</b>：只在每月 1 号结算一次。它刚关门，之前那份多半是「月中」的样子；
 *       之后再不动——历史月份的数据不会再变，反复重算只是烧模型费。
 *       真有人在事后补录了付款，页面读的时候会用 {@code stale} 标记提醒，
 *       由人决定要不要手动重出。</li>
 * </ul>
 *
 * <h3>为什么还要监听启动事件</h3>
 * 定时任务只在服务运行到那个点时才执行。半夜关机的机器上，0 点那次必然漏掉，
 * 第二天一起来当月就没有更新的报告。所以启动时再检查一次：
 * 当月今天还没出过，就补一份。这也让现场演示不必等到半夜——
 * 起服务即有一条当天报告。
 *
 * <h3>失败处理</h3>
 * 整个任务包在 try 里：定时效果图及其失败服务不该受影响或者堆日志雪崩。
 * 报告本质是缓存产物，这次失败下次自动补。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyInsightJob {

    private final MonthlyInsightService monthlyInsightService;

    /**
     * 每天 0 点跑一次。cron 写在配置里，方便现场关掉或调密。
     */
    @Scheduled(cron = "${travel.ai.insight-cron:0 0 0 * * *}", zone = "Asia/Shanghai")
    public void autoGenerate() {
        run("定时任务");
    }

    /**
     * 服务起来补一次：覆盖「半夜没开机」这种情况。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 这时候所有的 Bean、数据源、连接池都就绪了，不会再因为没准备好而白跑一趟。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        run("启动补跑");
    }

    private void run(String tag) {
        try {
            monthlyInsightService.runScheduled();
        } catch (Exception e) {
            log.warn("月度洞察{}失败，下次再补：{}", tag, e.getMessage());
        }
    }
}
