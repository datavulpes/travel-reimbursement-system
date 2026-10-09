package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.AiLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 调用留痕（ai_log）的数据访问接口。
 *
 * <p>只有 INSERT 与 SELECT：写由 {@code AiLogService} 在每次调用后追加，
 * 读留给事后核对。表里的数据不允许改，Service 层不提供任何更新入口。
 */
@Mapper
public interface AiLogMapper extends BaseMapper<AiLog> {
}
