-- =====================================================================
--  草稿放宽必填 —— 增量脚本，对已有库执行即可，可重复执行
--
--  背景：员工填申请常常一次填不完。「存草稿」本该允许半成品落库，
--        但 trip_apply.reason / partner_id 和 trip_itinerary 的六列全是 NOT NULL，
--        事由没写、对象没选、行程段只填了目的地，保存就会撞 1048
--        （Column 'xxx' cannot be null），等于变相要求一次填完。
--
--  本脚本做一件事：把这几列放宽成 NULL。
--        完整性仍由提交（A-02）把关 —— 后端 ApplySubmitRequest 上的
--        @NotBlank / @NotNull 只挂在 /apply/submit，草稿接口 /apply/draft 不挂，
--        所以「草稿随便存、提交必须齐」这个口径不靠数据库约束来实现。
--
--  不改的三列：est_traffic / est_hotel / est_total 保持 NOT NULL。
--        草稿没估价时由应用层按 0 落库，报表汇总就不用到处判空，
--        金额是参与计算的字段，留 NULL 比留 0 危险得多。
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 放宽草稿必填.sql
--        或 source 本文件；或双击 放宽草稿必填.bat
-- =====================================================================

SET NAMES utf8mb4;
USE travel_server;

-- 1. 申请单：事由与往来对象允许为空
ALTER TABLE trip_apply
  MODIFY COLUMN reason     VARCHAR(200) NULL COMMENT '出差事由（合理性评价「事由完整度」因子的数据来源）',
  MODIFY COLUMN partner_id BIGINT       NULL COMMENT '关联往来对象，关联 partner.id';

-- 2. 行程段：日期、城市、交通方式、城市等级允许为空
ALTER TABLE trip_itinerary
  MODIFY COLUMN start_date   DATE        NULL COMMENT '出发日期',
  MODIFY COLUMN end_date     DATE        NULL COMMENT '返回日期（不早于出发日期）',
  MODIFY COLUMN from_city    VARCHAR(50) NULL COMMENT '出发城市',
  MODIFY COLUMN to_city      VARCHAR(50) NULL COMMENT '目的地城市（决定差标等级与同类花费参考）',
  MODIFY COLUMN traffic_type VARCHAR(20) NULL COMMENT '交通方式枚举：高铁 / 飞机 / 自驾 / 其他',
  MODIFY COLUMN city_level   VARCHAR(10) NULL COMMENT '目的地城市等级：一级 ~ 五级，用于匹配 trip_standard';

-- 校验：下面几条应该全部返回 YES
SELECT table_name, column_name, is_nullable
  FROM information_schema.columns
 WHERE table_schema = 'travel_server'
   AND ((table_name = 'trip_apply'    AND column_name IN ('reason', 'partner_id', 'est_traffic', 'est_hotel', 'est_total'))
     OR (table_name = 'trip_itinerary' AND column_name IN ('start_date', 'end_date', 'from_city', 'to_city', 'traffic_type', 'city_level')))
 ORDER BY table_name, column_name;
