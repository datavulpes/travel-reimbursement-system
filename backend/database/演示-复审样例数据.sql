-- =====================================================================
-- 演示用：把一张申请单推到「管理层复审」，验证复审链路
--
-- 初始化数据里 trip_apply 只有 DEPT_REVIEW（部门初审）和已结案的单，
-- 一条 FINAL_REVIEW 都没有。管理层登录后待复审列表是空的，
-- 看起来就像前端没有复审功能——实际是缺数据。
--
-- 两条路，挑一条走就行：
--   1. 跑这个脚本，管理层立刻有单可审（开箱演示用这台）
--   2. 用 daiqinghao / 123456 登录，在「待我审批」里点任意一张通过，
--      后端会自动把状态改成 FINAL_REVIEW，管理层那边就出现了（答辩现场演示用这台）
--
-- 幂等：重复跑不会插入第二条初审记录，也不会把别的环节的单误改。
-- =====================================================================

SET NAMES utf8mb4;

-- 把最那张最有讲头的演示单（超标 2740 元但评价 SUGGEST 建议出行）
-- 从部门初审推进到管理层复审
UPDATE trip_apply
SET status = 'FINAL_REVIEW'
WHERE apply_no = 'TA202609180001'
  AND status = 'DEPT_REVIEW';

-- 补一条部门初审通过记录，流转记录里「提交 → 初审 → 复审」才是完整的一条链
INSERT INTO approval_record (apply_no, approver_id, step, result, opinion, snapshot, approved_at)
SELECT 'TA202609180001', 2, 'DEPT_REVIEW', 'APPROVED',
       '超标部分来自住宿，谈判需连住两晚，同意上报管理层复审', NULL, '2026-09-18 15:40:00'
WHERE NOT EXISTS (
  SELECT 1 FROM (SELECT 1) AS t
  WHERE EXISTS (
    SELECT 1 FROM approval_record r
    WHERE r.apply_no = 'TA202609180001' AND r.step = 'DEPT_REVIEW'
  )
);

-- 顺手再放一张复审单，避免只有一条显得单薄：
-- 深圳客户拜访（差标合规但评价 ATTENTION 重点关注）是对照组，
-- 管理层复审时正好可以看到「差标绿灯 vs 评价红灯」同时出现在一张单上。
UPDATE trip_apply
SET status = 'FINAL_REVIEW'
WHERE apply_no = 'TA202609200001'
  AND status = 'DEPT_REVIEW';

INSERT INTO approval_record (apply_no, approver_id, step, result, opinion, snapshot, approved_at)
SELECT 'TA202609200001', 2, 'DEPT_REVIEW', 'APPROVED',
       '金额在差标内，但评价提示重点关注，转管理层定夺', NULL, '2026-09-20 16:10:00'
WHERE NOT EXISTS (
  SELECT 1 FROM (SELECT 1) AS t
  WHERE EXISTS (
    SELECT 1 FROM approval_record r
    WHERE r.apply_no = 'TA202609200001' AND r.step = 'DEPT_REVIEW'
  )
);

SELECT apply_no, status FROM trip_apply WHERE status = 'FINAL_REVIEW';
