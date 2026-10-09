package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.MonthlyInsight;
import org.apache.ibatis.annotations.Mapper;

/**
 * 月度洞察报告读写。
 *
 * <p>只做按月份查与 upsert：查用 uk_month（每月一条），写在 Service 里先查有无、
 * 有则 updateById 无则 insert。没有删接口——历史月份的快照要留着，
 * 重跑演示数据时由脚本统一清。
 */
@Mapper
public interface MonthlyInsightMapper extends BaseMapper<MonthlyInsight> {
}
