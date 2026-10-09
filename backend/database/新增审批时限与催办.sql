-- 审批时限与催办：trip_apply 补 status_at 列 + 新建 apply_remind 表
--
-- status_at  = 进入当前状态的时间。算「在这一步待了多久」要用它，
--              没有它，待办列表分不清哪张单刚提交、哪张已经压了三天。
-- apply_remind = 员工催一次写一行，只追加。它不改变任何单据状态，
--              所以必须单独记（approval_record 记的是审批人做了什么）。
--
-- 幂等：列 / 表已存在时跳过，可反复执行。

USE travel_server;

-- 1) trip_apply.status_at
SET @exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'travel_server' AND TABLE_NAME = 'trip_apply' AND COLUMN_NAME = 'status_at'
);
SET @sql = IF(@exists = 0,
  'ALTER TABLE trip_apply ADD COLUMN status_at DATETIME NULL COMMENT ''进入当前状态的时间；NULL 表示老数据，不计时'' AFTER status',
  'SELECT ''status_at 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 老数据补一遍：把创建时间填进去，免得换页看到一半有值一半是横杠。
-- 这不是伪造——创建时间那一刻的单子确实处在它当时的状态上，
-- 只是「精确到哪一步流转的」补不回来了。
UPDATE trip_apply SET status_at = created_at WHERE status_at IS NULL;

-- 2) apply_remind
CREATE TABLE IF NOT EXISTS apply_remind (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '催办主键 ID',
  apply_no     VARCHAR(32)  NOT NULL COMMENT '被催的申请单号',
  step         VARCHAR(20)  NULL     COMMENT '催办时单据所处的环节：DEPT_REVIEW / FINAL_REVIEW',
  from_user_id BIGINT       NULL     COMMENT '谁催的，关联 sys_user.id',
  note         VARCHAR(200) NULL     COMMENT '催办时留的一句话，可空',
  remind_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '催办时间',
  PRIMARY KEY (id),
  KEY idx_apply_time (apply_no, remind_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申请单催办记录：申请人催一次写一行，只追加不修改';

SELECT 'apply_remind 就绪，行数 ' AS msg, COUNT(*) AS cnt FROM apply_remind;
