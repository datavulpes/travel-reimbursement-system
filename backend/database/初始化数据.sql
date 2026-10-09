-- =====================================================================
-- 企业差旅管理与费用报销系统 —— 联调初始化数据
-- 前置：先执行 schema.sql 建库建表
-- 执行顺序：部门 → 用户 → 补审批人 → 往来对象 → 差标 → 同类参考
--           → 历史出差单 → 结果回填 → 两条演示单据
-- 可重复执行（先 DELETE 再 INSERT）
-- =====================================================================

-- 库名 travel_server，与 schema.sql 保持一致
USE travel_server;
SET NAMES utf8mb4;

-- 库已经建过、又不想重建的，先补这一列再往下跑（trip_apply 新增了 trip_type）：
--   ALTER TABLE trip_apply
--     ADD COLUMN trip_type VARCHAR(20) NULL COMMENT '出差类型：验货 / 商务拜访 / 签单 / 供应商审核 / 展会 / 其他'
--     AFTER partner_id;
-- 直接重跑 schema.sql 会 DROP 重建，那这条就不用管了。

DELETE FROM eval_result;
DELETE FROM biz_result;
DELETE FROM payment_record;
DELETE FROM expense_report;
DELETE FROM invoice;
DELETE FROM loan_record;
DELETE FROM approval_record;
DELETE FROM trip_itinerary;
DELETE FROM trip_apply;
DELETE FROM cost_ref;
DELETE FROM trip_standard;
DELETE FROM partner;
DELETE FROM sys_user;
DELETE FROM dept;

-- 1. 部门（leader_id 先置 0，第 3 步回填）--------------------------------
INSERT INTO dept (id, dept_name, monthly_budget, standard_level, leader_id) VALUES
(1, '销售部',   80000.00, '二级', 0),
(2, '采购部',   50000.00, '三级', 0),
(3, '技术部',   40000.00, '二级', 0),
(4, '财务部',   10000.00, '二级', 0);

-- 2. 用户：四个角色各一个，密码统一 123456（BCrypt 密文，Spring Security 可直接校验）
INSERT INTO sys_user (id, username, password, real_name, role, dept_id, bank_account) VALUES
(1, 'wengshaogang', '$2b$10$FS7LSI6E76cfT8ZJqoSLSudw8uUXFOB9Gg2AvT3XXDcMFu2AJYkt.', '翁绍刚', 'EMPLOYEE', 1, '6222 **** 1234'),
(2, 'daiqinghao',   '$2b$10$8fK7yvzHV0PRgWg50qMYXe1Lx0hbDeTGpHTwXusmlW.nOg7FmKxg2', '戴庆豪', 'APPROVER', 1, '6222 **** 5678'),
(3, 'wusiting',     '$2b$10$UEcyXZ591TJzBBih.P.yZ.Fv.486lSUjp/nlk6VUUzMcruS6vqD36', '吴思挺', 'FINANCE',  4, '6222 **** 9012'),
(4, 'manager',      '$2b$10$yXPFR/0xalpUmgPM4geT.OvheSkI/m5529qQtRUTtBzpAQvbQfZk2', '管理层', 'MANAGER', 1, NULL);

-- 3. 回填部门审批人（销售部→戴庆豪，其余→管理层）
UPDATE dept SET leader_id = 2 WHERE id = 1;
UPDATE dept SET leader_id = 4 WHERE id IN (2, 3, 4);

-- 4. 往来对象 ----------------------------------------------------------
INSERT INTO partner (id, partner_name, partner_type, total_invest, total_output) VALUES
(1, '东莞宏远电子',   'CUSTOMER',  18600.00, 320000.00),
(2, '深圳启明科技',   'CUSTOMER',   9200.00,  75000.00),
(3, '广州华南模具厂', 'SUPPLIER',  14300.00,      0.00),
(4, '佛山顺德五金',   'SUPPLIER',   6100.00,      0.00);

-- 5. 差标（城市等级 + 交通方式 → 单次上限）------------------------------
INSERT INTO trip_standard (id, city_level, traffic_type, max_amount, effective_at, updated_by) VALUES
(1,  '一级', '高铁', 1200.00, '2026-01-01', 4),
(2,  '一级', '飞机', 2500.00, '2026-01-01', 4),
(3,  '一级', '自驾',  800.00, '2026-01-01', 4),
(4,  '二级', '高铁',  900.00, '2026-01-01', 4),
(5,  '二级', '飞机', 1800.00, '2026-01-01', 4),
(6,  '二级', '自驾',  600.00, '2026-01-01', 4),
(7,  '三级', '高铁',  700.00, '2026-01-01', 4),
(8,  '三级', '飞机', 1400.00, '2026-01-01', 4),
(9,  '三级', '自驾',  500.00, '2026-01-01', 4),
(10, '四级', '高铁',  550.00, '2026-01-01', 4),
(11, '四级', '自驾',  400.00, '2026-01-01', 4),
(12, '五级', '高铁',  400.00, '2026-01-01', 4),
(13, '五级', '自驾',  300.00, '2026-01-01', 4);

-- 6. 同类花费参考（审批详情页右侧参考卡的数据源）------------------------
INSERT INTO cost_ref (id, to_city, trip_type, avg_amount, min_amount, max_amount, sample_cnt) VALUES
(1, '东莞', '商务拜访', 1450.00,  980.00, 2100.00, 26),
(2, '东莞', '验货',     1780.00, 1250.00, 2600.00, 18),
(3, '深圳', '商务拜访', 1620.00, 1100.00, 2400.00, 31),
(4, '深圳', '签单',     2450.00, 1800.00, 3600.00, 12),
(5, '广州', '验货',     1180.00,  860.00, 1750.00, 22),
(6, '佛山', '供应商审核', 980.00,  720.00, 1400.00, 15);

-- 7. 历史出差单（已结案）——给 F1 历史产出率、F3 频次异常度提供取数 ------
--
-- F1 算法：数该对象 APPROVED / CLOSED 的历史单 N 趟，其中回填结果不是「无进展」的 M 趟，
--          产出率 = M ÷ N × 100。没有历史单时取中性分 60——那样答辩讲不出东西，
--          所以这里刻意铺了历史数据，让 F1 有真实取值。
-- F3 算法：取最近一趟的 created_at 算距今天数，≥30 天 100 分 / 15~29 天 70 分 /
--          7~14 天 45 分 / <7 天 20 分。这条会随「今天」变，属正常，别当 bug。
--
--   东莞宏远电子（partner 1）：4 趟，产出 3 趟 → F1 = 75.00，最近一趟 8-20（约 32 天前）→ F3 = 100
--   深圳启明科技（partner 2）：3 趟，产出 0 趟 → F1 = 0.00，最近一趟 9-18（3 天前）  → F3 = 20
--
-- 历史单刻意不建 approval_record：待办列表只取 DEPT_REVIEW / FINAL_REVIEW，
-- 结案单不会出现在审批端，缺审批记录不影响任何页面。
INSERT INTO trip_apply
(id, apply_no, user_id, dept_id, reason, partner_id, trip_type,
 est_traffic, est_hotel, est_total, standard_result, exceed_reason, status, created_at)
VALUES
(2, 'TA202608200001', 1, 1, '东莞宏远电子年度框架合同续签，现场确认新机型报价', 1, '签单',
 620.00, 580.00, 1200.00, 'NORMAL', NULL, 'CLOSED', '2026-08-20 09:00:00'),
(3, 'TA202607150001', 1, 1, '东莞宏远电子第二批样品验收，核对尺寸与外观不良率', 1, '验货',
 620.00, 580.00, 1200.00, 'NORMAL', NULL, 'CLOSED', '2026-07-15 09:00:00'),
(4, 'TA202606220001', 1, 1, '东莞宏远电子产线稽核，确认交期延误原因', 1, '验货',
 620.00, 580.00, 1200.00, 'NORMAL', NULL, 'CLOSED', '2026-06-22 09:00:00'),
(5, 'TA202605100001', 1, 1, '东莞宏远电子首批量产件交付跟进', 1, '验货',
 620.00, 580.00, 1200.00, 'NORMAL', NULL, 'APPROVED', '2026-05-10 09:00:00'),
(7, 'TA202607200001', 1, 1, '深圳启明科技技术交流，评估新方案可行性', 2, '商务拜访',
 560.00, 480.00, 1040.00, 'NORMAL', NULL, 'CLOSED', '2026-07-20 09:00:00'),
(8, 'TA202609120001', 1, 1, '深圳启明科技方案二次沟通，确认报价区间', 2, '商务拜访',
 560.00, 480.00, 1040.00, 'NORMAL', NULL, 'CLOSED', '2026-09-12 09:00:00'),
(9, 'TA202609180002', 1, 1, '深圳启明科技合同条款沟通，未达成一致', 2, '商务拜访',
 560.00, 480.00, 1040.00, 'NORMAL', NULL, 'CLOSED', '2026-09-18 09:00:00');

INSERT INTO trip_itinerary
(id, apply_no, seq, start_date, end_date, from_city, to_city, traffic_type, city_level)
VALUES
(2, 'TA202608200001', 1, '2026-08-24', '2026-08-25', '惠州', '东莞', '高铁', '一级'),
(3, 'TA202607150001', 1, '2026-07-20', '2026-07-21', '惠州', '东莞', '高铁', '一级'),
(4, 'TA202606220001', 1, '2026-06-25', '2026-06-26', '惠州', '东莞', '高铁', '一级'),
(5, 'TA202605100001', 1, '2026-05-15', '2026-05-16', '惠州', '东莞', '高铁', '一级'),
(7, 'TA202607200001', 1, '2026-07-22', '2026-07-23', '惠州', '深圳', '高铁', '一级'),
(8, 'TA202609120001', 1, '2026-09-13', '2026-09-14', '惠州', '深圳', '高铁', '一级'),
(9, 'TA202609180002', 1, '2026-09-19', '2026-09-20', '惠州', '深圳', '高铁', '一级');

-- 结果回填（biz_result）：F1 的分子来源，也是费效比分析的分子 --------------
--   partner 1：4 条，1 条无进展 → 有效 3 趟 → 产出率 75%
--   partner 2：3 条，全是无进展 → 有效 0 趟 → 产出率 0%（反面对照）
INSERT INTO biz_result
(id, apply_no, partner_id, result_type, result_desc, output_amount, filled_by, filled_at)
VALUES
(1, 'TA202608200001', 1, '签单',   '现场签下年度框架合同，新增订单 5.8 万',        58000.00, 1, '2026-08-26 10:00:00'),
(2, 'TA202607150001', 1, '验收',   '第二批样品验收通过，不良率 1.2% 达标',         12000.00, 1, '2026-07-22 10:00:00'),
(3, 'TA202606220001', 1, '无进展', '产线稽核未形成整改方案，对方回复待定',             0.00, 1, '2026-06-27 10:00:00'),
(4, 'TA202605100001', 1, '交付',   '首批量产件完成交付并回款',                     26000.00, 1, '2026-05-18 10:00:00'),
(5, 'TA202607200001', 2, '无进展', '技术方案未获对方认可，暂缓',                       0.00, 1, '2026-07-23 10:00:00'),
(6, 'TA202609120001', 2, '无进展', '报价仍高于对方预算，未推进',                       0.00, 1, '2026-09-14 10:00:00'),
(7, 'TA202609180002', 2, '无进展', '合同条款未达成一致，对方要求再议',                 0.00, 1, '2026-09-20 18:30:00');

-- 8. 演示单据一：待部门审批，差标超标但值得去 ------------------------------
--
-- 差标结论为什么是 EXCEED（口径见设计报告 6.3.3）：
--   行程只有一段：惠州 → 东莞，一级城市 + 高铁，差标上限 1200 元，
--   允许上浮 10% 即 1320 元；整单预估 2740 元 > 1320 元，故判 EXCEED。
--   拆分看：交通 1180 元在上限内，住宿 1560 元（2 晚）无对应差标档，
--   超标部分实际全部来自住宿——超标说明里就是这么写的，答辩时按这个口径讲。
--   （原先这里写的是 HIGH，与 2740 的金额矛盾，已改。）
--
-- 注意 EXCEED 时 exceed_reason 必填，否则后端返回 2003（BR-01），别把它清空。
INSERT INTO trip_apply
(id, apply_no, user_id, dept_id, reason, partner_id, trip_type,
 est_traffic, est_hotel, est_total,
 standard_result, exceed_reason, status, created_at)
VALUES
(1, 'TA202609180001', 1, 1, '赴东莞宏远电子进行年度合同续签谈判并现场验货', 1, '验货',
 1180.00, 1560.00, 2740.00, 'EXCEED',
 '合同续签谈判需连住两晚，住宿 1560 元实报；交通 1180 元在一级高铁上限 1200 元内，超标部分全部来自住宿，已获销售总监口头同意',
 'DEPT_REVIEW', '2026-09-18 09:20:00');

INSERT INTO trip_itinerary
(id, apply_no, seq, start_date, end_date, from_city, to_city, traffic_type, city_level)
VALUES
(1, 'TA202609180001', 1, '2026-09-22', '2026-09-24', '惠州', '东莞', '高铁', '一级');

-- 这组分数是照着评价引擎的算法手算出来的，点「重新评价」不会跳变：
--   F1 历史产出率   75.00 × 0.35 = 26.25  该对象 4 趟历史，3 趟有产出 → 3÷4 = 75%
--   F2 成本占比    100.00 × 0.30 = 30.00  2740 ÷ 320000 = 0.86%，低于 5% 给满分
--   F3 频次异常度  100.00 × 0.20 = 20.00  距上次（8-20）约 32 天，超过 30 天基准
--   F4 事由完整度   84.00 × 0.15 = 12.60  事由 22 字(24/40) + 关联对象(30/30) + 行程完整(30/30)
--   总分 88.85 ≥ 75 → SUGGEST 建议出行
INSERT INTO eval_result
(id, apply_no, f_history, f_cost, f_freq, f_complete, total_score, level, explain_json, eval_at)
VALUES
(1, 'TA202609180001', 75.00, 100.00, 100.00, 84.00, 88.85, 'SUGGEST',
 '{"factors":[{"key":"F_HISTORY","name":"历史产出率","score":"75.00","weight":"0.35","value":"该对象历史 4 趟，有效产出 3 次，产出率 75.0%"},{"key":"F_COST","name":"成本占对象产出比","score":"100.00","weight":"0.30","value":"本次预估 2740.00 元，占该对象累计产出 0.86%"},{"key":"F_FREQ","name":"频次异常度","score":"100.00","weight":"0.20","value":"距上次去该对象 32 天，正常节奏参考 30 天"},{"key":"F_COMPLETE","name":"事由完整度","score":"84.00","weight":"0.15","value":"事由 22 字（24/40），关联对象（30/30），行程填写完整（30/30）"}],"hints":["本次预估 2740.00 元，高于同类参考上限 2600.00 元（样本 18 条）"]}',
 '2026-09-18 09:20:05');

-- 9. 演示单据二：同样待部门审批，差标合规但不建议去 ------------------------
--
-- 这条是刻意做的对照组，用来回答答辩时必然被问的一句：
-- 「差标通过了是不是就等于该去？」
--
--   差标：深圳一级 + 高铁上限 1200，整单 1100 ≤ 1200 → NORMAL（前端绿色「正常」）
--   评价：F1 = 0（深圳启明科技 3 趟全无进展）、F3 = 20（3 天前刚去过）、
--         F4 = 72（事由只写 6 个字）→ 总分 44.80 < 55 → ATTENTION 重点关注
--
-- 于是审批端会同时看到：左边差标绿色「正常」，右边评价红色「重点关注」，
-- 下面还挂 3 条事前提示。这就是系统「卡金额」与「看值不值」分离的直接证据。
INSERT INTO trip_apply
(id, apply_no, user_id, dept_id, reason, partner_id, trip_type,
 est_traffic, est_hotel, est_total,
 standard_result, exceed_reason, status, created_at)
VALUES
(6, 'TA202609200001', 1, 1, '深圳客户拜访', 2, '商务拜访',
 620.00, 480.00, 1100.00, 'NORMAL', NULL, 'DEPT_REVIEW', '2026-09-20 14:00:00');

INSERT INTO trip_itinerary
(id, apply_no, seq, start_date, end_date, from_city, to_city, traffic_type, city_level)
VALUES
(6, 'TA202609200001', 1, '2026-09-24', '2026-09-25', '惠州', '深圳', '高铁', '一级');

-- F1 0.00 × 0.35 = 0.00   3 趟历史全是无进展，产出率 0%
-- F2 100.00 × 0.30 = 30.00 1100 ÷ 75000 = 1.47%，低于 5% 给满分
-- F3 20.00 × 0.20 = 4.00   距上次（9-18）仅 3 天，<7 天档
-- F4 72.00 × 0.15 = 10.80  事由 6 字(12/40) + 关联对象(30/30) + 行程完整(30/30)
-- 总分 44.80 < 55 → ATTENTION 重点关注
INSERT INTO eval_result
(id, apply_no, f_history, f_cost, f_freq, f_complete, total_score, level, explain_json, eval_at)
VALUES
(2, 'TA202609200001', 0.00, 100.00, 20.00, 72.00, 44.80, 'ATTENTION',
 '{"factors":[{"key":"F_HISTORY","name":"历史产出率","score":"0.00","weight":"0.35","value":"该对象历史 3 趟，有效产出 0 次，产出率 0.0%"},{"key":"F_COST","name":"成本占对象产出比","score":"100.00","weight":"0.30","value":"本次预估 1100.00 元，占该对象累计产出 1.47%"},{"key":"F_FREQ","name":"频次异常度","score":"20.00","weight":"0.20","value":"距上次去该对象 3 天，正常节奏参考 30 天"},{"key":"F_COMPLETE","name":"事由完整度","score":"72.00","weight":"0.15","value":"事由 6 字（12/40），关联对象（30/30），行程填写完整（30/30）"}],"hints":["该对象上一趟（2026-09-20 18:30:00）结果为「无进展」，距本次不足 15 天，请确认本次必要性","本次将是 30 天内第 3 次去同一对象","出差事由不足 15 字，补全目的（见谁、办什么事）可以提高事由完整度得分"]}',
 '2026-09-20 14:00:05');

-- =====================================================================
-- 校验：
--   SELECT COUNT(*) FROM sys_user;       -- 4
--   SELECT COUNT(*) FROM trip_standard;  -- 13
--   SELECT COUNT(*) FROM trip_apply;     -- 9（7 趟历史 + 2 条待审）
--   SELECT COUNT(*) FROM biz_result;     -- 7
--
--   -- 待办列表应有 2 条：一条超标但建议去，一条合规但不建议去
--   SELECT apply_no, partner_id, est_total, standard_result
--   FROM trip_apply WHERE status = 'DEPT_REVIEW';
--
--   SELECT apply_no, total_score, level FROM eval_result;
--     -- TA202609180001  88.85  SUGGEST
--     -- TA202609200001  44.80  ATTENTION
-- =====================================================================
