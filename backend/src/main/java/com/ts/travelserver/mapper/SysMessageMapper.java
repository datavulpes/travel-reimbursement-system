package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.SysMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 站内消息。
 *
 * <p>都是单表按 user_id 查，不需要手写 SQL。
 */
@Mapper
public interface SysMessageMapper extends BaseMapper<SysMessage> {

    /**
     * 我的未读条数。
     *
     * <p>顶栏铃铛每半分钟问一次，只 COUNT 一个索引列，不查正文。
     */
    @Select("SELECT COUNT(*) FROM sys_message WHERE user_id = #{userId} AND read_flag = 0")
    long countUnread(@Param("userId") Long userId);
}
