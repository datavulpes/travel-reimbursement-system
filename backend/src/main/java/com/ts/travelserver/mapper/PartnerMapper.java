package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.Partner;
import org.apache.ibatis.annotations.Mapper;

/**
 * 往来对象（partner）的数据访问接口。
 *
 * <p>MyBatis-Plus 的 BaseMapper 已经够用：单表 CRUD 与分页都走它，
 * 复杂统计（费效比月报、审批待办列表）放在 Service 层组装，不写 XML、不写物理外键关联查询。
 */
@Mapper
public interface PartnerMapper extends BaseMapper<Partner> {
}
