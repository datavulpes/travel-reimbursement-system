-- =====================================================================
--  月度洞察增加「下一步动作 + 数字出处」两列 —— 增量脚本，可重复执行
--
--  背景：以前一份洞察报告只回答「发现了什么」，不回答「下一步查什么」，
--        也没留下这些数字是从哪几张表汇总出来的。
--        加这两列是为了补上处方层，并让每一个数都能被追问。
--
--  加的两列：
--        actions_json   下一步动作 JSON（含是否被勾选）
--                       动作代码是六个固定值之一，对象必须是本次入参里真实存在的
--                       部门名 / 出差类型名。自由文本会让模型写出「暂停审批」这类
--                       系统里根本做不到的处置动作，所以只给它枚举去选。
--        sources_json   数字出处 JSON，字符串数组，如
--                       "payment_record 2026-09 已付款 8 单，投入 ¥12345"
--                       页面不展示（那是讲解型文案），留给后端自检与口径核对。
--
--  老数据不用补：加列之前生成的报告这两列为 NULL，
--  页面读到空动作就是不显示那一块，读到空出处也不影响任何展示。
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 新增洞察动作与出处列.sql
--        或 source 本文件；或双击 新增洞察动作与出处列.bat
-- =====================================================================

SET NAMES utf8mb4;
USE travel_server;

-- 1. 下一步动作（幂等：列已存在就跳过）
SET @sql1 = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'travel_server' AND table_name = 'monthly_insight' AND column_name = 'actions_json') = 0,
  'ALTER TABLE monthly_insight ADD COLUMN actions_json TEXT NULL COMMENT ''下一步动作 JSON，形状同 MonthlyInsightVO.ActionVO[]，含是否被勾选'' AFTER drivers_json',
  'SELECT ''actions_json 已存在，跳过'' AS note');
PREPARE s1 FROM @sql1; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 2. 数字出处（幂等：列已存在就跳过）
SET @sql2 = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'travel_server' AND table_name = 'monthly_insight' AND column_name = 'sources_json') = 0,
  'ALTER TABLE monthly_insight ADD COLUMN sources_json TEXT NULL COMMENT ''数字出处 JSON，字符串数组；后端自检与口径核对用，不上页面'' AFTER actions_json',
  'SELECT ''sources_json 已存在，跳过'' AS note');
PREPARE s2 FROM @sql2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- 校验：下面这张表应返回两行，is_nullable 均为 YES
SELECT column_name, column_type, is_nullable, column_comment
  FROM information_schema.columns
 WHERE table_schema = 'travel_server'
   AND table_name = 'monthly_insight'
   AND column_name IN ('actions_json', 'sources_json')
 ORDER BY ordinal_position;

-- 顺带看一眼现有报告：这两列为 NULL 是正常的，下次生成或重新分析就会带上
SELECT month, LEFT(actions_json, 40) AS actions_head, LEFT(sources_json, 40) AS sources_head,
       generated_by, generated_at
  FROM monthly_insight
 ORDER BY month;
