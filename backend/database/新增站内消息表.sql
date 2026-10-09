-- 站内消息：sys_message
--
-- 催办、审批结论、复核结论、付款这四件事此前都只写在业务表里，
-- 当事人不主动打开对应页面就一个字都看不到 —— 催办尤其明显：
-- 员工点了催办，审批人那边毫无反应，只有他某天打开待办列表才偶然看到「已催 3 次」。
-- 这张表补的就是这一环：把「有事找你」主动推到收件人眼前。
--
-- 只做站内，不做邮件 / 短信：这个项目没有外网通道，做一个发不出去的通道比不做更糟。
-- 消息是通知，不是业务数据 —— 删掉它不影响任何单据，所以业务接口不读它，
-- 它只服务消息中心一个页面。
--
-- 幂等：表已存在时跳过，可反复执行。

USE travel_server;

CREATE TABLE IF NOT EXISTS sys_message (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息主键 ID',
  user_id    BIGINT       NOT NULL COMMENT '收件人，关联 sys_user.id',
  msg_type   VARCHAR(32)  NOT NULL COMMENT '消息类型：APPLY_REMIND 催办 / APPLY_RESULT 审批结论 / EXPENSE_REVIEW 复核结论 / EXPENSE_PAID 已付款',
  biz_type   VARCHAR(20)  NULL     COMMENT '关联单据类型：APPLY 申请单 / EXPENSE 报销单',
  biz_no     VARCHAR(32)  NULL     COMMENT '关联单号，点消息跳详情用',
  title      VARCHAR(100) NOT NULL COMMENT '消息标题',
  content    VARCHAR(500) NULL     COMMENT '消息正文，一句话说清发生了什么',
  read_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0 未读 / 1 已读',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息生成时间',
  read_at    DATETIME     NULL     COMMENT '已读时间，未读为 NULL',
  PRIMARY KEY (id),
  KEY idx_user_flag (user_id, read_flag, id),
  KEY idx_biz (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内消息：只服务消息中心，删掉不影响任何业务单据';

SELECT 'sys_message 就绪，现有消息 ' AS msg, COUNT(*) AS cnt FROM sys_message;
