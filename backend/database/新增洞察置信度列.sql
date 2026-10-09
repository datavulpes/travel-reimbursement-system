-- =====================================================================
--  月度洞察增加「样本量 + 置信度」两列 —— 增量脚本，可重复执行
--
--  背景：以前一份洞察报告只说「结论是什么」，不说「这份结论能信到什么程度」。
--        管理层看到 9 月费效比 0.3849，无从判断它是站在 30 张单上、
--        还是站在 3 张单上——两者该被采纳的程度完全不同，界面上却长得一样。
--
--  加的两列：
--        sample_cnt   已付款单据数，页面写「样本 N 单」
--        confidence   置信度 0~1 = 样本充足度 × 数字校验通过率 × 产出回填完整度
--
--  置信度刻意不由模型自报。模型说自己多有把握不作数——它算的数本来就不进结果，
--  让它给自己打分更是无从验证。三项全部由统计层的真实数字算出来。
--
--  老数据不用补：加列之前生成的报告 confidence 为 NULL，
--  读出来时后端会用当前统计补算一次（只算不存，下次重新分析自然带上）。
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 新增洞察置信度列.sql
--        或 source 本文件；或双击 新增洞察置信度列.bat
-- =====================================================================

SET NAMES utf8mb4;
USE travel_server;

-- 1. 样本量（幂等：列已存在就跳过）
SET @sql1 = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'travel_server' AND table_name = 'monthly_insight' AND column_name = 'sample_cnt') = 0,
  'ALTER TABLE monthly_insight ADD COLUMN sample_cnt INT NULL COMMENT ''快照当时的已付款单据数，即这份结论的样本量；页面写「样本 N 单」'' AFTER sample_note',
  'SELECT ''sample_cnt 已存在，跳过'' AS note');
PREPARE s1 FROM @sql1; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 2. 置信度（幂等：列已存在就跳过）
SET @sql2 = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'travel_server' AND table_name = 'monthly_insight' AND column_name = 'confidence') = 0,
  'ALTER TABLE monthly_insight ADD COLUMN confidence DECIMAL(4,2) NULL COMMENT ''快照当时的置信度 0~1 = 样本充足度 x 数字校验通过率 x 产出回填完整度，不由模型自报'' AFTER sample_cnt',
  'SELECT ''confidence 已存在，跳过'' AS note');
PREPARE s2 FROM @sql2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- 校验：下面这张表应返回两行，is_nullable 均为 YES
SELECT column_name, column_type, is_nullable, column_comment
  FROM information_schema.columns
 WHERE table_schema = 'travel_server'
   AND table_name = 'monthly_insight'
   AND column_name IN ('sample_cnt', 'confidence')
 ORDER BY ordinal_position;

-- 顺带看一眼现有报告的置信度：NULL 是正常的，读出来时后端会补算
SELECT month, sample_cnt, confidence, generated_by, generated_at
  FROM monthly_insight
 ORDER BY month;
