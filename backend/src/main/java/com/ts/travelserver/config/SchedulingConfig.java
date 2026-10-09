package com.ts.travelserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 自带的定时任务能力。
 *
 * <p>这是本项目唯一一处定时任务，用的是 {@code spring-context} 自带的
 * {@link EnableScheduling}，<b>不需要引任何新依赖</b>——
 * 跟项目一路「能不加就不加」的取舍保持一致（真要后来上多实例，
 * 换 quartz / xxl-job 时也只有 {@code MonthlyInsightJob} 一个类要改）。
 */
@EnableScheduling
@Configuration
public class SchedulingConfig {
}
