-- 理由字段草稿表 + 报销单两处说明列
--
-- 背景（两件事一起做，因为它们是同一个问题）：
--   1. 审批意见、财务复核说明、付款备注、强制提交说明、超标说明这五处
--      都是「一句话要斟酌很久」的文本框，写一半丢了的代价很高。
--      所在单据此时已有确定状态（待审批 / 待复核 / 待付款），
--      没有「整单存草稿」这一档可挂，于是单开一张通用草稿表。
--   2. 财务复核说明与员工强制提交说明原先根本没落库——
--      复核说明连列都没有，强制说明只在请求体里过一遍就蒸发。
--      事后翻报销单只看到结论，看不到「为什么」。
--
-- 执行方式：mysql -uroot -p travel_server < 新增理由草稿表与报销说明列.sql
--
-- 说明：脚本是增量的，不删库不清数据；重复跑会因为「列/表已存在」报错，
--       属正常现象，不影响已有数据。

-- ---------- 1. 报销单补两列 ----------

ALTER TABLE expense_report
  ADD COLUMN force_reason  VARCHAR(200) NULL COMMENT '强制提交说明：预检为 FORCE 时员工写的理由，BR-05 的留痕本体'
  AFTER status;

ALTER TABLE expense_report
  ADD COLUMN review_reason VARCHAR(200) NULL COMMENT '财务复核说明：带异常通过说明异常在哪、退回说明退在哪（BR-06）；复核通过可为空'
  AFTER force_reason;

-- ---------- 2. 通用理由草稿表 ----------

CREATE TABLE IF NOT EXISTS field_draft (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '草稿主键 ID',
  biz_type    VARCHAR(20)  NOT NULL COMMENT '业务场景：APPLY_REVIEW 审批意见 / EXPENSE_REVIEW 财务复核说明 / EXPENSE_PAY 付款备注 / EXPENSE_SUBMIT 强制提交说明 / APPLY_SUBMIT 超标说明',
  biz_no      VARCHAR(32)  NOT NULL COMMENT '业务单号：APPLY_* 记申请号，EXPENSE_* 记报销单号',
  field_name  VARCHAR(32)  NOT NULL COMMENT '字段名，与前端表单字段名一致：opinion / reviewReason / payRemark / forceReason / exceedReason',
  user_id     BIGINT       NOT NULL COMMENT '草稿归属人，关联 sys_user.id；只认本人的草稿',
  content     VARCHAR(200) NOT NULL COMMENT '草稿正文',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后一次保存时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_draft (biz_type, biz_no, field_name, user_id),
  KEY idx_user_time (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理由字段草稿表：五处理由输入框的暂存，只保留最后一次，正式值仍在业务表上';

-- 校验：
--   SHOW COLUMNS FROM expense_report LIKE '%reason';
--   SHOW CREATE TABLE field_draft;
--   SELECT biz_type, biz_no, field_name, user_id, LEFT(content, 30), updated_at
--     FROM field_draft ORDER BY updated_at DESC LIMIT 20;
