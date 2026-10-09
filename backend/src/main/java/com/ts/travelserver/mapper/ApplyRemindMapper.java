package com.ts.travelserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ts.travelserver.entity.ApplyRemind;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 催办记录（apply_remind）的数据访问接口。
 */
@Mapper
public interface ApplyRemindMapper extends BaseMapper<ApplyRemind> {

    /**
     * 一批单据各自的催办次数。
     *
     * <p>手写 SQL 的理由同 {@code AiCorrectionMapper.topFields}：
     * 这是 group by，Wrapper 拼不出来，而为它建一张统计表更不划算——
     * 催办一天最多几十条，现算一次足够。
     */
    @Select("""
            <script>
            SELECT apply_no AS applyNo, COUNT(*) AS cnt
            FROM apply_remind
            WHERE apply_no IN
              <foreach collection="applyNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            GROUP BY apply_no
            </script>
            """)
    List<RemindCount> countBy(@Param("applyNos") List<String> applyNos);

    /** 一对「单号 → 催办次数」，只给上面那条查询用 */
    class RemindCount {
        private String applyNo;
        private Long cnt;

        public String getApplyNo() {
            return applyNo;
        }

        public void setApplyNo(String applyNo) {
            this.applyNo = applyNo;
        }

        public Long getCnt() {
            return cnt;
        }

        public void setCnt(Long cnt) {
            this.cnt = cnt;
        }
    }
}
