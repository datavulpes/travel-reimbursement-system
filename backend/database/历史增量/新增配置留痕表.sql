-- =====================================================================
--  配置调整留痕表（config_log）—— 增量脚本，对已有库执行即可，可重复执行
--
--  背景：原先 H-04「调整留痕」是从 trip_standard 的版本链临时推导的，
--        dept 表只有一个 monthly_budget 字段，预算改完前值就没了，
--        管理层在页面上填的「调整原因」后端接了也没有地方存。
--  本脚本做两件事：
--        1. 建 config_log 表（已经执行过完整 schema.sql 的新库无需执行本文件）
--        2. 把库里现有的差标版本链、部门预算初始值回填成留痕，历史数据不再空白
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 新增配置留痕表.sql
--        或 source 本文件；或双击 执行脚本.bat 选它
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS travel_server
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE travel_server;

CREATE TABLE IF NOT EXISTS config_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕主键 ID',
  item_type    VARCHAR(20)  NOT NULL COMMENT '变更类型：STANDARD 差标 / BUDGET 预算 / DEPT 部门',
  item_key     VARCHAR(64)  NOT NULL COMMENT '变更项标识，如「二级-高铁」「dept:3」',
  action       VARCHAR(10)  NOT NULL COMMENT '操作类型：INSERT 新增 / UPDATE 修改 / DELETE 删除',
  before_value VARCHAR(200) NULL COMMENT '调整前的值，新增时为空',
  after_value  VARCHAR(200) NULL COMMENT '调整后的值，删除时为空',
  operator_id  BIGINT       NOT NULL COMMENT '操作人，关联 sys_user.id',
  operate_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  reason       VARCHAR(200) NULL COMMENT '调整原因，管理层填写后写进留痕',
  PRIMARY KEY (id),
  KEY idx_item_type_time (item_type, operate_at),
  KEY idx_item_key (item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置调整留痕表：差标与部门预算调整的前后值与责任人，只追加不修改';

-- ---------------------------------------------------------------------
-- 回填一：每组差标最早的一版，按「新增」记一条（before 为空）
-- ---------------------------------------------------------------------
INSERT INTO config_log (item_type, item_key, action, before_value, after_value,
                        operator_id, operate_at, reason)
SELECT 'STANDARD',
       CONCAT(first.city_level, '-', first.traffic_type),
       'INSERT',
       NULL,
       first.max_amount,
       IFNULL(first.updated_by, (SELECT id FROM sys_user WHERE role = 'MANAGER' ORDER BY id LIMIT 1)),
       TIMESTAMP(first.effective_at),
       CONCAT('历史数据回填：', first.city_level, first.traffic_type, ' 首版差标，自 ', first.effective_at, ' 起执行')
FROM trip_standard first
WHERE first.id IS NOT NULL
  AND first.effective_at = (
        SELECT MIN(same.effective_at)
        FROM trip_standard same
        WHERE same.city_level = first.city_level
          AND same.traffic_type = first.traffic_type
  )
  AND NOT EXISTS (
        SELECT 1 FROM config_log l
        WHERE l.item_type = 'STANDARD'
          AND l.item_key = CONCAT(first.city_level, '-', first.traffic_type)
          AND l.action = 'INSERT'
  );

-- ---------------------------------------------------------------------
-- 回填二：同一组差标的后一版，与其「直接前一版」配成一条修改记录
--        （只认相邻两条，跨版本的落后值不会被拉进来）
-- ---------------------------------------------------------------------
INSERT INTO config_log (item_type, item_key, action, before_value, after_value,
                        operator_id, operate_at, reason)
SELECT 'STANDARD',
       CONCAT(cur.city_level, '-', cur.traffic_type),
       'UPDATE',
       prev.max_amount,
       cur.max_amount,
       IFNULL(cur.updated_by, (SELECT id FROM sys_user WHERE role = 'MANAGER' ORDER BY id LIMIT 1)),
       TIMESTAMP(cur.effective_at),
       CONCAT('历史数据回填：', cur.city_level, cur.traffic_type, ' 自 ', cur.effective_at, ' 起执行新标准')
FROM trip_standard cur
JOIN trip_standard prev
  ON prev.city_level = cur.city_level
 AND prev.traffic_type = cur.traffic_type
 AND prev.effective_at < cur.effective_at
 -- 保证 prev 就是 cur 的直接前一版：两者之间不存在第三版
 AND NOT EXISTS (
       SELECT 1 FROM trip_standard mid
       WHERE mid.city_level = cur.city_level
         AND mid.traffic_type = cur.traffic_type
         AND mid.effective_at < cur.effective_at
         AND mid.effective_at > prev.effective_at
 )
WHERE NOT EXISTS (
      SELECT 1 FROM config_log l
      WHERE l.item_type = 'STANDARD'
        AND l.item_key = CONCAT(cur.city_level, '-', cur.traffic_type)
        AND l.after_value = cur.max_amount
);

-- ---------------------------------------------------------------------
-- 回填三：各部门当前预算，按「新增」记一条基线，之后在页面上改就有对比了
-- ---------------------------------------------------------------------
INSERT INTO config_log (item_type, item_key, action, before_value, after_value,
                        operator_id, operate_at, reason)
SELECT 'BUDGET',
       CONCAT('dept:', d.id),
       'INSERT',
       NULL,
       d.monthly_budget,
       (SELECT id FROM sys_user WHERE role = 'MANAGER' ORDER BY id LIMIT 1),
       NOW(),
       CONCAT('历史数据回填：', d.dept_name, ' 初始化月度预算')
FROM dept d
WHERE NOT EXISTS (
      SELECT 1 FROM config_log l
      WHERE l.item_type = 'BUDGET'
        AND l.item_key = CONCAT('dept:', d.id)
);

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
--  校验：
--    SELECT COUNT(*) FROM config_log;
--    SELECT * FROM config_log ORDER BY operate_at DESC LIMIT 20;
-- =====================================================================
