package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.AiCorrection;
import com.ts.travelserver.vo.AiStatusVO.FieldCorrectionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工修正留痕（ai_correction）的数据访问接口。
 *
 * <p>只有 INSERT 与 SELECT，理由同 ai_log。
 *
 * <p>「高频被改字段」是一条 group by 聚合，MyBatis-Plus 的 Wrapper 拼不出来，
 * 所以这里手写一条 SQL——因为这个聚合只有「哪个字段被改得最多」这一个用途，
 * 为它单独建一张统计表不划算（理由见 {@code AiStatusService} 的类注释）。
 */
@Mapper
public interface AiCorrectionMapper extends BaseMapper<AiCorrection> {

    /**
     * 按字段统计修正次数，从多到少。
     *
     * @param from 统计窗口起点
     * @param size 取前几条
     */
    @Select("""
            SELECT field_name AS field, COUNT(*) AS cnt
            FROM ai_correction
            WHERE created_at >= #{from}
            GROUP BY field_name
            ORDER BY cnt DESC, field_name ASC
            LIMIT #{size}
            """)
    List<FieldCorrectionVO> topFields(@Param("from") LocalDateTime from, @Param("size") int size);
}
