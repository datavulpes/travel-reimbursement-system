package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.AiPanelState;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 助手面板状态。
 *
 * <p>{@code @Mapper} 必须写：项目没有 {@code @MapperScan}，
 * 全靠接口上的这个注解注册 bean，漏了就是启动期拿不到 bean。
 */
@Mapper
public interface AiPanelStateMapper extends BaseMapper<AiPanelState> {
}
