-- 前后端联调用：把库摆到「四个角色都有活干」的状态
-- 跑法：mysql -uroot -p<DB_PASSWORD> travel_server < 重置联调测试数据.sql
-- 配套文档：《企业差旅管理工具-前后端联调全链路测试数据与效果.docx》
-- 说明：只改状态字段，不删数据，随时能改回去

USE travel_server;

-- ============================================================
-- 1）财务端：待复核列表（E-01）查的是 status='PENDING'
--    测过一轮之后报销单都变 PAID，财务登录后列表是空的，退回一张给它
-- ============================================================
UPDATE expense_report SET status = 'PENDING'
 WHERE report_no IN ('ER202609220002', 'ER202609230001');

-- 改回去：
-- UPDATE expense_report SET status = 'PAID' WHERE report_no = 'ER202609220002';

-- ============================================================
-- 2）审批端：两级审批各有各的待办
--    初审（戴庆豪，dept 1 的 leader）看 status='DEPT_REVIEW' 且本部门的单
--    复审（manager，必须 MANAGER 角色）看 status='FINAL_REVIEW' 的单
--    两边都空的时候，把草稿推一步（只改状态，审批记录不会自动生成）
-- ============================================================
-- UPDATE trip_apply SET status = 'DEPT_REVIEW'  WHERE apply_no = 'TA202609230001';
-- UPDATE trip_apply SET status = 'FINAL_REVIEW' WHERE apply_no = 'TA202609230002';

-- ============================================================
-- 3）员工端：报销提交（D-02）只认 APPROVED / CLOSED 的申请单
--    这条查出来为空就没法提交报销，得先走一遍审批
-- ============================================================
SELECT apply_no, status FROM trip_apply WHERE status IN ('APPROVED', 'CLOSED');

-- ============================================================
-- 4）清理本轮链路测试产生的单据（TA20260923% / ER20260923%）
--    默认注释掉：删了之后月报和钻取会少样本。
--    想从干净状态重跑一整条链路时再放开，注意顺序（从子表往主表删）
-- ============================================================
-- DELETE FROM payment_record WHERE report_no LIKE 'ER20260923%';
-- DELETE FROM invoice        WHERE apply_no  LIKE 'TA20260923%';
-- DELETE FROM loan_record    WHERE apply_no  LIKE 'TA20260923%';
-- DELETE FROM expense_report WHERE apply_no  LIKE 'TA20260923%';
-- DELETE FROM biz_result     WHERE apply_no  LIKE 'TA20260923%';
-- DELETE FROM approval_record WHERE apply_no LIKE 'TA20260923%';
-- DELETE FROM trip_itinerary WHERE apply_no  LIKE 'TA20260923%';
-- DELETE FROM trip_apply     WHERE apply_no  LIKE 'TA20260923%';

-- ============================================================
-- 5）看一眼当前各角色能看到多少东西
-- ============================================================
SELECT '申请单' AS 项, status, COUNT(*) AS 数量 FROM trip_apply GROUP BY status;
SELECT '报销单' AS 项, status, COUNT(*) AS 数量 FROM expense_report GROUP BY status;
SELECT '预借款' AS 项, status, COUNT(*) AS 数量 FROM loan_record GROUP BY status;

-- 费效比口径自检：投入 ÷ 产出，正常应远小于 1（如 0.0019）
-- 如果看到 171.43，说明后端还是旧代码，重启一下
SELECT SUM(p.pay_amount) AS 投入, SUM(b.output_amount) AS 产出
  FROM payment_record p
  JOIN expense_report e ON e.report_no = p.report_no
  LEFT JOIN biz_result b ON b.apply_no = e.apply_no;
