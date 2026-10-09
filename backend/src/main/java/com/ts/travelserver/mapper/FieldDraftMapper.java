package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.FieldDraft;
import org.apache.ibatis.annotations.Mapper;

/**
 * 理由字段草稿（field_draft）的数据访问接口。
 *
 * <p>与 config_log 不同，这张表是会被覆盖的：同一个人在同一张单的同一个字段上
 * 反复改，只留最后一条。唯一键 (biz_type, biz_no, field_name, user_id) 是这条约束的落点，
 * 应用层靠它做「查得到就 UPDATE，查不到就 INSERT」。
 */
@Mapper
public interface FieldDraftMapper extends BaseMapper<FieldDraft> {
}
