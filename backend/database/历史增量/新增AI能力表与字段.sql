-- =====================================================================
--  AI 能力增量脚本 —— 对已有库执行即可，可重复执行，不会删库也不会清数据
--
--  本脚本做两件事：
--        1. 建 ai_log 表（AI 调用留痕）
--        2. 给 eval_result 加六列 AI 语义评价字段
--
--  背景：AI 不直接打分，只出语义调节量 Δ ∈ [−10, +5]，
--        总分 = clamp(规则加权分 + Δ)，档位仍按原阈值切。
--        所以规则那四列一个字都不动，只是多记一份「AI 有没有参与、给了多少」。
--        全为空 = 这次评价没有 AI 参与，分数与纯规则模型完全一致。
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 新增AI能力表与字段.sql
--        或 source 本文件；或双击 添加AI能力表.bat
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS travel_server
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE travel_server;

-- ---------------------------------------------------------------------
-- 1. AI 调用留痕表：只追加不修改，页面上是只读的
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕主键 ID',
  scene          VARCHAR(30)  NOT NULL COMMENT '场景：SEMANTIC_EVAL 语义评价 / DRAFT_ADVICE 填写建议',
  ref_no         VARCHAR(32)  NULL     COMMENT '关联单号：申请号或报销单号，没有则为空',
  prompt_version VARCHAR(20)  NULL     COMMENT 'prompt 版本号，与 eval_result.prompt_version 同源',
  input_hash     VARCHAR(64)  NULL     COMMENT '输入内容的 hash（场景+版本+入参），不存原文',
  model          VARCHAR(40)  NULL     COMMENT '实际模型名；本地启发式为 local-heuristic',
  output_json    VARCHAR(4000) NULL    COMMENT '模型返回原文（校验前），截断保存',
  delta          DECIMAL(3,1) NULL     COMMENT '解析出的 Δ',
  grade          VARCHAR(2)   NULL     COMMENT '解析出的档位 A~E',
  cost_ms        INT          NULL     COMMENT '调用耗时（毫秒）',
  accepted       TINYINT      NULL     DEFAULT 0 COMMENT '1 = 结论被采纳，0 = 解析失败或 evidence 校验未通过',
  fallback       TINYINT      NULL     DEFAULT 0 COMMENT '1 = 降级（本地启发式或调用失败），0 = 真实模型',
  operator_id    BIGINT       NULL     COMMENT '操作人，关联 sys_user.id；取不到记 0',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
  PRIMARY KEY (id),
  KEY idx_scene_time (scene, created_at),
  KEY idx_ref (ref_no),
  KEY idx_accepted (accepted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用留痕表：模型输入输出与是否被采纳，只追加不修改';

-- ---------------------------------------------------------------------
-- 2. eval_result 加 AI 字段（每列单独加，重复执行不会报错）
-- ---------------------------------------------------------------------
SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'ai_delta');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN ai_delta DECIMAL(3,1) NULL COMMENT ''AI 语义调节分，-10 ~ +5；为空表示未参与'' AFTER explain_json',
  'SELECT ''ai_delta 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'ai_grade');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN ai_grade VARCHAR(2) NULL COMMENT ''AI 语义档位：A +5 / B 0 / C -3 / D -7 / E -10'' AFTER ai_delta',
  'SELECT ''ai_grade 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'ai_reasons');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN ai_reasons JSON NULL COMMENT ''AI 理由明细 JSON'' AFTER ai_grade',
  'SELECT ''ai_reasons 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'ai_model');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN ai_model VARCHAR(40) NULL COMMENT ''产出该结论的模型名'' AFTER ai_reasons',
  'SELECT ''ai_model 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'prompt_version');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN prompt_version VARCHAR(20) NULL COMMENT ''prompt 版本号'' AFTER ai_model',
  'SELECT ''prompt_version 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_result' AND COLUMN_NAME = 'ai_enhanced');
SET @sql = IF(@exist = 0,
  'ALTER TABLE eval_result ADD COLUMN ai_enhanced TINYINT NULL DEFAULT 0 COMMENT ''1 = AI 结论参与了本次评分'' AFTER prompt_version',
  'SELECT ''ai_enhanced 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 校验：
--   SHOW COLUMNS FROM eval_result LIKE 'ai%';
--   SELECT scene, grade, delta, accepted, fallback, cost_ms, created_at
--     FROM ai_log ORDER BY created_at DESC LIMIT 20;
--
-- 想查「AI 到底有没有在起作用」：
--   SELECT grade, COUNT(*) cnt, AVG(delta) avg_delta
--     FROM ai_log WHERE scene='SEMANTIC_EVAL' GROUP BY grade;
--   -- accepted=0 的比例高，说明 prompt 或 evidence 口径要调，不是模型不行
-- ---------------------------------------------------------------------
