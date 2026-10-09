-- 人工修正留痕表（ai_correction）
-- 用途：记「系统给了一个值，人改成了另一个值」。
--   采集点一：票据 OCR 回填后员工手改（scene = OCR_INVOICE）
--   采集点二：填写助手的建议值 vs 最终提交值（scene = APPLY_ASSIST）
-- 系统管理员端「AI 运行状况」页的「最常被改的字段」就是按这张表 group by 出来的。
-- 可重复执行。

CREATE TABLE IF NOT EXISTS ai_correction (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕主键 ID',
  scene        VARCHAR(30)  NOT NULL COMMENT '来源场景：OCR_INVOICE 票据识别回填 / APPLY_ASSIST 填写助手建议',
  biz_type     VARCHAR(20)  NULL     COMMENT '单据类型：INVOICE 票据 / TRIP_APPLY 申请单；还没落库的新建单为空',
  biz_no       VARCHAR(64)  NULL     COMMENT '单号：票据存发票号，申请单存申请号（新建单用 NEW+用户id）',
  field_name   VARCHAR(40)  NOT NULL COMMENT '被改的字段名：invoiceNo / amount / invoiceDate / seller / reason / tripType',
  old_value    VARCHAR(255) NULL     COMMENT '系统给的值（OCR 识别值 / AI 建议值）',
  new_value    VARCHAR(255) NULL     COMMENT '人最终填进去的值',
  operator_id  BIGINT       NULL     COMMENT '修改人，关联 sys_user.id',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_field_time (field_name, created_at),
  KEY idx_biz (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工修正留痕表：系统给的值被人改成了什么，只追加不修改';

SELECT 'ai_correction 就绪，行数 ' AS msg, COUNT(*) AS cnt FROM ai_correction;
