package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.ConfigLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配置调整留痕（config_log）的数据访问接口。
 *
 * <p>只有 INSERT 与 SELECT 两种用法：写由 {@code StandardService} 在每次调整后追加，
 * 读由 H-04 分页查询。表里的数据不允许改，因此 Service 层不提供任何更新入口。
 */
@Mapper
public interface ConfigLogMapper extends BaseMapper<ConfigLog> {
}
