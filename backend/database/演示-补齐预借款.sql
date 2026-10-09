-- =====================================================================
-- 演示-补齐预借款.sql
--
-- 作用：给「已经审批通过但库里没有预借款」的申请单补一条 loan_record。
--
-- 为什么要补：
--   loan_record 本来是「复审通过的那一刻」由后端自动生成的（LoanService.create，
--   BR-04：额度 = min(预估合计, 部门月度预算)）。但初始化数据里的申请单是直接
--   写成 APPROVED / CLOSED 状态的，等于跳过了审批流，预借款就永远不会被生成。
--   后果是报销页拿到 1001「该申请还没有预借款」，再到 E-04 付款时
--   PaymentRequest.loanId 是 @NotNull 必填，点「确认付款」直接被拦下。
--
-- 这份脚本把预借款按同样的 BR-04 口径补回来，让付款这一步能走通。
-- 幂等：靠 uk_apply_user (apply_no, user_id) 唯一键 + INSERT IGNORE，
--       已经有预借款的单静默跳过，重复跑既不新增也不覆盖。
-- =====================================================================

INSERT IGNORE INTO loan_record (apply_no, user_id, amount, status, offset_amount, created_at)
SELECT
    a.apply_no,
    a.user_id,
    CASE
        WHEN d.monthly_budget IS NULL OR d.monthly_budget <= 0 THEN a.est_total
        ELSE LEAST(a.est_total, d.monthly_budget)
    END AS amount,
    'ACTIVE' AS status,
    0 AS offset_amount,
    DATE_ADD(a.created_at, INTERVAL 5 MINUTE) AS created_at
FROM trip_apply a
LEFT JOIN dept d ON d.id = a.dept_id
WHERE a.status IN ('APPROVED', 'CLOSED')
  AND a.est_total > 0
  AND NOT EXISTS (
        SELECT 1 FROM loan_record l
        WHERE l.apply_no = a.apply_no AND l.user_id = a.user_id
  );

-- ---------------------------------------------------------------------
-- 校验查询结果（会随脚本一起打出来）
-- ---------------------------------------------------------------------
SELECT apply_no, user_id, amount, status, offset_amount, created_at
  FROM loan_record
 ORDER BY id DESC LIMIT 20;

SELECT COUNT(*) AS loan_count FROM loan_record;
