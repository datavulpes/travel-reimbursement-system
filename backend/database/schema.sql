-- =====================================================================
-- 企业差旅管理与费用报销系统 —— 数据库建表脚本
-- MySQL 8.0 / InnoDB / utf8mb4
-- 库名 travel_server（DataGrip 中已存在）
-- 与《数据库与系统功能设计》第 5 章物理结构设计一致
--
-- 用法：
--   mysql -u root -p --default-character-set=utf8mb4 < schema.sql
--   或在 Navicat / DataGrip 里整份执行
-- 脚本幂等：先 DROP 再 CREATE，改完表结构直接重跑
--
-- 备注约定：
--   · 金额为 DECIMAL，单位统一为「元」
--   · 状态类字段的 COMMENT 里列出全部枚举取值，格式为「取值含义：A xx / B yy」
--   · ID 后缀字段均关联对应表的主键，在备注里写明
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS travel_server
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE travel_server;

DROP VIEW IF EXISTS v_approval_todo;
DROP VIEW IF EXISTS v_finance_pending;
DROP VIEW IF EXISTS v_dept_monthly;

DROP TABLE IF EXISTS field_draft;
DROP TABLE IF EXISTS apply_remind;
DROP TABLE IF EXISTS ai_correction;
DROP TABLE IF EXISTS ai_log;
DROP TABLE IF EXISTS config_log;
DROP TABLE IF EXISTS eval_result;
DROP TABLE IF EXISTS biz_result;
DROP TABLE IF EXISTS payment_record;
DROP TABLE IF EXISTS expense_report;
DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS loan_record;
DROP TABLE IF EXISTS approval_record;
DROP TABLE IF EXISTS cost_ref;
DROP TABLE IF EXISTS trip_standard;
DROP TABLE IF EXISTS trip_itinerary;
DROP TABLE IF EXISTS trip_apply;
DROP TABLE IF EXISTS partner;
DROP TABLE IF EXISTS dept;
DROP TABLE IF EXISTS sys_user;

-- 1. 用户表 -----------------------------------------------------------
CREATE TABLE sys_user (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键 ID',
  username     VARCHAR(50)  NOT NULL COMMENT '登录账号（全局唯一）',
  password     VARCHAR(100) NOT NULL COMMENT '登录密码（BCrypt 密文，不明文存储）',
  real_name    VARCHAR(50)  NOT NULL COMMENT '真实姓名',
  role         VARCHAR(20)  NOT NULL COMMENT '角色枚举：EMPLOYEE 出差员工 / APPROVER 部门审批人 / FINANCE 财务复核 / MANAGER 企业管理层',
  dept_id      BIGINT       NOT NULL COMMENT '所属部门 ID，关联 dept.id',
  bank_account VARCHAR(40)  NULL COMMENT '收款银行账号（界面脱敏，仅显示后四位）',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0 正常 / 1 已删除',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '账号创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_dept (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表：四类角色共用一张表，靠 role 字段区分';

-- 2. 部门表 -----------------------------------------------------------
CREATE TABLE dept (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '部门主键 ID',
  dept_name      VARCHAR(50)   NOT NULL COMMENT '部门名称（唯一）',
  monthly_budget DECIMAL(12,2) NOT NULL COMMENT '月度差旅预算（元）',
  standard_level VARCHAR(10)   NOT NULL COMMENT '差标等级：一级 / 二级 / 三级 / 四级 / 五级（对应城市档次）',
  leader_id      BIGINT        NOT NULL COMMENT '部门审批人，关联 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_name (dept_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表：承载月度预算与默认差标等级';

-- 3. 往来对象表 -------------------------------------------------------
CREATE TABLE partner (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '往来对象主键 ID',
  partner_name VARCHAR(100)  NOT NULL COMMENT '客户或供应商名称（唯一）',
  partner_type VARCHAR(20)   NOT NULL COMMENT '对象类型枚举：CUSTOMER 客户 / SUPPLIER 供应商',
  total_invest DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '累计投入（元）：该对象名下差旅支出合计',
  total_output DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '累计产出（元）：该对象签单与验收折算金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_partner_name (partner_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='往来对象表：投入产出分析的主体，出差必须挂接到某个对象';

-- 4. 出差申请单表 -----------------------------------------------------
CREATE TABLE trip_apply (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '申请单主键 ID',
  apply_no        VARCHAR(32)   NOT NULL COMMENT '申请号（业务主键）：TA+yyyyMMdd+4位序号，一串到底贯穿申请/审批/票据/报销/复核/分析',
  user_id         BIGINT        NOT NULL COMMENT '申请人，关联 sys_user.id',
  dept_id         BIGINT        NOT NULL COMMENT '申请所属部门，关联 dept.id',
  -- 事由与往来对象允许为空：草稿阶段可能一个字还没写、还没选定去哪家。
  -- 提交（A-02）时这两项仍走 @NotBlank / @NotNull，空着交不上去，只是可以先存着
  reason          VARCHAR(200)  NULL      COMMENT '出差事由（合理性评价「事由完整度」因子的数据来源）',
  partner_id      BIGINT        NULL      COMMENT '关联往来对象，关联 partner.id',
  -- 值域六个：验货 / 商务拜访 / 项目交付 / 供应商审核 / 展会 / 其他。
  -- 必须与前端 utils/dict.js 的 TRIP_TYPES 逐字一致：字典里写成别的名字，
  -- 员工在页面上就选不出这个值，cost_ref 里对应的那一组参考会永远匹配不上。
  trip_type       VARCHAR(20)   NULL COMMENT '出差类型：验货 / 商务拜访 / 项目交付 / 供应商审核 / 展会 / 其他，与 cost_ref.trip_type 联合匹配同类花费参考',
  -- 只有选「其他」时才让员工写，非必填。不并进 cost_ref 的匹配键——「其他」那一组本来就宽，
  -- 再拿自由文本去匹配，等于让这一组永远匹配不上
  trip_type_note  VARCHAR(200)  NULL COMMENT '出差类型为「其他」时的补充说明（非必填）',
  -- 金额三列保持 NOT NULL：草稿没估价时由应用层按 0 落库，报表汇总不必到处判空
  est_traffic     DECIMAL(12,2) NOT NULL COMMENT '预估交通费（元）',
  est_hotel       DECIMAL(12,2) NOT NULL COMMENT '预估住宿费（元）',
  est_total       DECIMAL(12,2) NOT NULL COMMENT '预估费用合计（元）= 交通费 + 住宿费，应用层写入',
  standard_result VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' COMMENT '差标校验结论枚举：NORMAL 正常 / HIGH 接近上限 / EXCEED 超标；草稿阶段尚未判定，落默认值 NORMAL',
  exceed_reason   VARCHAR(200)  NULL COMMENT '超标说明（差标结论为 EXCEED 时必填，否则不允许提交）',
  status          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT 草稿 / DEPT_REVIEW 部门审批中 / FINAL_REVIEW 领导复审中 / APPROVED 已通过 / REJECTED 已驳回 / CLOSED 已关闭',
  -- 进入当前状态的时间，算「在这一步待了多久」要用它。每次状态流转都要跟着改。
  -- 为 NULL 表示老数据，页面按「不计时」处理，不拿 created_at 冒充
  status_at       DATETIME      NULL     COMMENT '进入当前状态的时间；NULL 表示老数据，不计时',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_apply_no (apply_no),
  KEY idx_user_status (user_id, status),
  KEY idx_dept_status (dept_id, status),
  KEY idx_partner (partner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出差申请单表：全系统的主线单据，其余业务表均以 apply_no 关联';

-- 5. 行程段表 ---------------------------------------------------------
CREATE TABLE trip_itinerary (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '行程段主键 ID',
  apply_no    VARCHAR(32) NOT NULL COMMENT '所属申请号，关联 trip_apply.apply_no',
  seq         INT         NOT NULL COMMENT '行程段序号（同一申请内从 1 递增）',
  -- 六个业务列全部允许 NULL：草稿里加一段行程、只填了目的地就走人是常事，
  -- 存不住的话「先存一下回头补」就成了空话。提交时由 A-02 的 @NotBlank 兜住完整性
  start_date  DATE        NULL      COMMENT '出发日期',
  end_date    DATE        NULL      COMMENT '返回日期（不早于出发日期）',
  from_city   VARCHAR(50) NULL      COMMENT '出发城市',
  to_city     VARCHAR(50) NULL      COMMENT '目的地城市（决定差标等级与同类花费参考）',
  traffic_type VARCHAR(20) NULL      COMMENT '交通方式枚举：高铁 / 飞机 / 自驾 / 其他',
  city_level  VARCHAR(10) NULL      COMMENT '目的地城市等级：一级 ~ 五级，用于匹配 trip_standard',
  PRIMARY KEY (id),
  UNIQUE KEY uk_apply_seq (apply_no, seq),
  KEY idx_apply (apply_no),
  KEY idx_route (to_city, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程段表：一张申请可拆多段行程，票据按段挂接';

-- 6. 差标表 -----------------------------------------------------------
CREATE TABLE trip_standard (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '差标主键 ID',
  city_level   VARCHAR(10)   NOT NULL COMMENT '城市等级：一级 ~ 五级',
  traffic_type VARCHAR(20)   NOT NULL COMMENT '交通方式枚举：高铁 / 飞机 / 自驾 / 其他',
  max_amount   DECIMAL(12,2) NOT NULL COMMENT '单次上限金额（元，须大于 0）',
  effective_at DATE          NOT NULL COMMENT '生效日期',
  updated_by   BIGINT        NOT NULL COMMENT '最近一次调整人，关联 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_level_traffic (city_level, traffic_type, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差标表：城市等级 + 交通方式 + 生效日期唯一，调整需留痕';

-- 7. 同类花费参考表 ---------------------------------------------------
CREATE TABLE cost_ref (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '参考记录主键 ID',
  to_city     VARCHAR(50)   NOT NULL COMMENT '目的地城市',
  trip_type   VARCHAR(20)   NOT NULL COMMENT '出差类型枚举：商务拜访 / 验货 / 项目交付 / 供应商审核 / 展会 / 其他',
  avg_amount  DECIMAL(12,2) NOT NULL COMMENT '近 12 个月同类出差平均花费（元）',
  min_amount  DECIMAL(12,2) NOT NULL COMMENT '花费区间下限（元）',
  max_amount  DECIMAL(12,2) NOT NULL COMMENT '花费区间上限（元）',
  sample_cnt  INT           NOT NULL COMMENT '样本单据数（样本过少时参考值提示可信度低）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_type (to_city, trip_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同类花费参考表：审批人判断报价是否合理的横向参照';

-- 8. 审批记录表 -------------------------------------------------------
CREATE TABLE approval_record (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '审批记录主键 ID',
  apply_no    VARCHAR(32) NOT NULL COMMENT '被审批的申请号，关联 trip_apply.apply_no',
  approver_id BIGINT      NOT NULL COMMENT '审批人，关联 sys_user.id',
  step        VARCHAR(20) NOT NULL COMMENT '审批环节枚举：DEPT_REVIEW 部门初审 / FINAL_REVIEW 管理层复审',
  result      VARCHAR(10) NOT NULL COMMENT '审批结论枚举：APPROVED 通过 / REJECTED 驳回',
  opinion     VARCHAR(200) NULL COMMENT '审批意见（结论为 REJECTED 时必填）',
  snapshot    JSON        NULL COMMENT '依据快照 JSON：存档当时的参考均值、差标结论、评价档位，事后可追溯审批依据；提交留痕没有快照，为 NULL',
  approved_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批操作时间',
  PRIMARY KEY (id),
  KEY idx_apply (apply_no),
  KEY idx_approver (approver_id, approved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录表：不引工作流引擎，状态流转 + 本表留痕即可';

-- 9. 预借款表 ---------------------------------------------------------
CREATE TABLE loan_record (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '预借款主键 ID',
  apply_no      VARCHAR(32)   NOT NULL COMMENT '关联申请号（复审通过后自动生成）',
  user_id       BIGINT        NOT NULL COMMENT '预借人，关联 sys_user.id',
  amount        DECIMAL(12,2) NOT NULL COMMENT '预借额度（元，须大于 0）',
  status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态枚举：ACTIVE 生效 / OFFSET 已部分冲抵 / SETTLED 已结清',
  offset_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '已冲抵金额（元）',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_apply_user (apply_no, user_id),
  KEY idx_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预借款表：报销时自动冲抵，避免员工垫资';

-- 10. 票据表 ----------------------------------------------------------
CREATE TABLE invoice (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '票据主键 ID',
  invoice_no   VARCHAR(50)   NOT NULL COMMENT '发票号码（全局唯一，重复报销查重的唯一依据）',
  apply_no     VARCHAR(32)   NOT NULL COMMENT '挂接申请号，关联 trip_apply.apply_no',
  itinerary_id BIGINT        NOT NULL COMMENT '挂接行程段，关联 trip_itinerary.id',
  amount       DECIMAL(12,2) NOT NULL COMMENT '票面金额（元，须大于 0）',
  invoice_date DATE          NOT NULL COMMENT '开票日期',
  seller       VARCHAR(100)  NOT NULL COMMENT '销售方名称',
  img_path     VARCHAR(200)  NOT NULL COMMENT '影像存储相对路径（只存路径不存文件本身）',
  ocr_flag     TINYINT       NOT NULL DEFAULT 0 COMMENT '录入方式标记：0 手工录入 / 1 OCR 自动识别代填',
  dup_of       VARCHAR(32)   NULL COMMENT '命中重复时，指向最早使用该发票号的申请号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_invoice_no (invoice_no),
  KEY idx_apply (apply_no),
  KEY idx_itinerary (itinerary_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票据表：发票号唯一索引是查重的根基，不可删除';

-- 11. 报销单表 --------------------------------------------------------
CREATE TABLE expense_report (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '报销单主键 ID',
  report_no       VARCHAR(32)   NOT NULL COMMENT '报销单号（业务主键）：ER+yyyyMMdd+4位序号',
  apply_no        VARCHAR(32)   NOT NULL COMMENT '关联申请号（一申请对应一张报销单）',
  total_amount    DECIMAL(12,2) NOT NULL COMMENT '票据合计金额（元）',
  offset_amount   DECIMAL(12,2) NOT NULL COMMENT '冲抵预借金额（元）',
  net_amount      DECIMAL(12,2) NOT NULL COMMENT '实付净额（元）= 票据合计 - 冲抵额',
  precheck_result VARCHAR(10)   NOT NULL COMMENT '提交前预检结论枚举：PASS 通过 / WARN 提醒 / FORCE 需填强制说明',
  status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态枚举：PENDING 待复核 / APPROVED 复核通过 / PASS_WITH_ISSUE 带异常通过 / RETURNED 已退回 / PAID 已付款',
  force_reason    VARCHAR(200)  NULL COMMENT '强制提交说明：预检为 FORCE 时员工写的理由，BR-05 的留痕本体',
  review_reason   VARCHAR(200)  NULL COMMENT '财务复核说明：带异常通过说明异常在哪、退回说明退在哪（BR-06）；复核通过可为空',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_no (report_no),
  UNIQUE KEY uk_apply (apply_no),
  KEY idx_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报销单表：把同一申请号下的票据汇总成一张单';

-- 12. 付款记录表 ------------------------------------------------------
CREATE TABLE payment_record (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '付款记录主键 ID',
  report_no   VARCHAR(32)   NOT NULL COMMENT '关联报销单号，关联 expense_report.report_no',
  pay_amount  DECIMAL(12,2) NOT NULL COMMENT '实付金额（元，等于报销单净额）',
  loan_id     BIGINT        NOT NULL COMMENT '本次对冲的预借款，关联 loan_record.id',
  pay_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '付款时间',
  operator_id BIGINT        NOT NULL COMMENT '经办财务人员，关联 sys_user.id',
  pay_remark   VARCHAR(200)  NULL COMMENT '付款备注',
  PRIMARY KEY (id),
  KEY idx_report (report_no),
  KEY idx_pay_at (pay_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款记录表：一次报销对应一笔付款，同时完成预借冲抵';

-- 13. 业务结果表 ------------------------------------------------------
CREATE TABLE biz_result (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '业务结果主键 ID',
  apply_no      VARCHAR(32)   NOT NULL COMMENT '关联申请号',
  -- 可空：trip_apply.partner_id 本来就允许为空（没挂往来对象的出差），
  -- 这里跟着放开，否则这类申请单一回填就会撞 NOT NULL 插不进去
  partner_id    BIGINT        NULL COMMENT '往来对象，关联 partner.id；未关联对象时为空',
  result_type   VARCHAR(20)   NOT NULL COMMENT '出差成果类型枚举：签单 / 验收 / 交付 / 无进展',
  result_desc   VARCHAR(200)  NULL COMMENT '成果说明文字',
  output_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '折算产出金额（元），无进展时填 0',
  filled_by     BIGINT        NOT NULL COMMENT '回填人，关联 sys_user.id',
  filled_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回填时间',
  PRIMARY KEY (id),
  KEY idx_apply (apply_no),
  KEY idx_partner (partner_id, filled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务结果表：把出差花掉的钱和带回的结果对上，是费效比分析的分子';

-- 14. 评价结果表 ------------------------------------------------------
CREATE TABLE eval_result (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价结果主键 ID',
  apply_no    VARCHAR(32)  NOT NULL COMMENT '关联申请号（一申请一条评价）',
  f_history   DECIMAL(5,2) NOT NULL COMMENT '因子一：该对象历史产出率得分（0-100，权重 30%）',
  f_cost      DECIMAL(5,2) NOT NULL COMMENT '因子二：本次成本占对象累计产出比得分（0-100，权重 30%）',
  f_freq      DECIMAL(5,2) NOT NULL COMMENT '因子三：近期同类出差频次异常度得分（0-100，权重 20%）',
  f_complete  DECIMAL(5,2) NOT NULL COMMENT '因子四：事由描述完整度得分（0-100，权重 20%）',
  total_score DECIMAL(5,2) NOT NULL COMMENT '四项加权总分',
  level       VARCHAR(20)  NOT NULL COMMENT '评价档位枚举：SUGGEST 建议出行 / NOTICE 需关注 / ATTENTION 重点关注',
  explain_json JSON        NOT NULL COMMENT '依据明细 JSON：各因子原始取值与命中情况，供审批页逐条展示',
  -- 以下六列是 AI 语义评价的落点。规则的四因子与权重一个字不动，
  -- AI 只出调节量 Δ ∈ [-10, +5]，total_score = clamp(规则加权分 + Δ)。
  -- 全部为空表示这次评价没有 AI 参与（未开启或降级），此时分数与纯规则模型完全一致。
  ai_delta     DECIMAL(3,1) NULL     COMMENT 'AI 语义调节分，-10 ~ +5；为空表示未参与',
  ai_grade     VARCHAR(2)   NULL     COMMENT 'AI 语义档位：A +5 / B 0 / C -3 / D -7 / E -10',
  ai_reasons   JSON         NULL     COMMENT 'AI 理由明细 JSON：[{point, level, evidence:{field,value}}]',
  ai_model     VARCHAR(40)  NULL     COMMENT '产出该结论的模型名；本地启发式为 local-heuristic',
  prompt_version VARCHAR(20) NULL     COMMENT '产出该结论的 prompt 版本号，改 prompt 必须改它',
  ai_enhanced  TINYINT      NULL     DEFAULT 0 COMMENT '1 = AI 结论参与了本次评分，0 = 未参与（降级）',
  eval_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价计算时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_apply (apply_no),
  KEY idx_level (level, eval_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出差合理性评价结果表：只提示不拦截，最终决策权留给审批人';

-- 15. 配置调整留痕表 --------------------------------------------------
-- 差标与部门预算的每一次增、删、改都在这里落一条，应用层只写不删（只读留痕）。
-- reason 由管理层在页面上填写后存进来，不再是写死的「生效日期 xxx」。
CREATE TABLE config_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕主键 ID',
  item_type    VARCHAR(20)  NOT NULL COMMENT '变更类型：STANDARD 差标 / BUDGET 预算 / DEPT 部门',
  item_key     VARCHAR(64)  NOT NULL COMMENT '变更项标识，如「二级-高铁」「dept:3」',
  action       VARCHAR(10)  NOT NULL COMMENT '操作类型：INSERT 新增 / UPDATE 修改 / DELETE 删除',
  before_value VARCHAR(200) NULL COMMENT '调整前的值，新增时为空',
  after_value  VARCHAR(200) NULL COMMENT '调整后的值，删除时为空',
  operator_id  BIGINT       NOT NULL COMMENT '操作人，关联 sys_user.id',
  operate_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  reason       VARCHAR(200) NULL COMMENT '调整原因，管理层在 H-02 / H-03 填写后写进留痕',
  PRIMARY KEY (id),
  KEY idx_item_type_time (item_type, operate_at),
  KEY idx_item_key (item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置调整留痕表：差标与部门预算调整的前后值与责任人，只追加不修改';

-- 16. AI 调用留痕表 ---------------------------------------------------
-- 与 config_log 一个路子：只 INSERT，不 UPDATE、不 DELETE（页面只读）。
-- 存在的理由：模型输出不可复现，没有留痕就查不清「这条 Δ 是谁、哪版 prompt、
-- 基于什么输入算出来的」；accepted=0 的样本（evidence 校验未通过）更是调 prompt 的依据。
-- 注意：这里只存输入 hash，不存事由原文——事由里可能有客户名等敏感信息。
CREATE TABLE ai_log (
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
  error_message  VARCHAR(500) NULL     COMMENT '失败原因：超时 / HTTP 非 200 / 输出无法解析 / 校验不过被丢弃；成功为 NULL',
  request_tokens  INT         NULL     COMMENT '输入 token，取自响应 usage；厂商没返回为 NULL，不估算',
  response_tokens INT         NULL     COMMENT '输出 token，取自响应 usage；厂商没返回为 NULL，不估算',
  accepted       TINYINT      NULL     DEFAULT 0 COMMENT '1 = 结论被采纳，0 = 解析失败或 evidence 校验未通过',
  fallback       TINYINT      NULL     DEFAULT 0 COMMENT '1 = 降级（本地启发式或调用失败），0 = 真实模型',
  operator_id    BIGINT       NULL     COMMENT '操作人，关联 sys_user.id；取不到记 0',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
  PRIMARY KEY (id),
  KEY idx_scene_time (scene, created_at),
  KEY idx_ref (ref_no),
  KEY idx_accepted (accepted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用留痕表：模型输入输出与是否被采纳，只追加不修改';

-- 16b. 人工修正留痕表 --------------------------------------------------
-- 与 ai_log 的分工：ai_log 记「模型这一次跑得怎么样」，这张表记「人认不认这个值」。
-- 采纳率只说有没有被采用，这张表才说「哪个字段最常被改」——后者才指向该改哪句 prompt。
-- 两个采集点：票据 OCR 回填后员工手改（OCR_INVOICE）、填写助手建议值 vs 最终提交值（APPLY_ASSIST）。
-- old_value / new_value 一律用文本：票号是数字串、金额是小数、日期是文本，
-- 统一成文本才不用为每种类型开一列（金额比对时按数值比，会先抹掉尾零）。
CREATE TABLE ai_correction (
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

-- 17. 管理层月度洞察报告表 --------------------------------------------
-- 与 ai_log 的分工：ai_log 记「每一次模型调用」，一张必要的审计底稿；
-- 这张表存「这个月当前生效的那份报告」，每月一行，会被覆盖。
-- 为什么要单独一张：月报页要能「上次生成到哪儿了就来哪儿」，而不是每次打开重算一遍。
--
-- input_hash 是这套设计的关键：它是生成那一刻的入参指纹（场景 + prompt 版本 + 统计入参）。
-- 页面读出来时拿它跟当前算出来的指纹比，不一致就说明「报告生成之后数据变过」，
-- 前端会在标题上挂一个「数据已更新」，由人决定要不要点「重新分析」——
-- 系统不会偷偷重算，也不会装作没发现。
CREATE TABLE monthly_insight (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '报告主键 ID',
  month           CHAR(7)      NOT NULL COMMENT '所属月份 yyyy-MM',
  input_hash      VARCHAR(128) NOT NULL COMMENT '生成时的入参指纹（场景+prompt版本+入参hash），变了说明数据已更新',
  summary         VARCHAR(500) NULL     COMMENT '一句话总结',
  highlights_json TEXT         NULL     COMMENT '分级要点 JSON，形状同 AiPointVO[]',
  drivers_json    TEXT         NULL     COMMENT '归因分解 JSON，形状同 MonthlyInsightVO.DriverVO[]',
  actions_json    TEXT         NULL     COMMENT '下一步动作 JSON，形状同 MonthlyInsightVO.ActionVO[]，含是否被勾选',
  sources_json    TEXT         NULL     COMMENT '数字出处 JSON，字符串数组；后端自检与口径核对用，不上页面',
  ratio           DECIMAL(12,4) NULL    COMMENT '快照当时的费效比',
  prev_ratio      DECIMAL(12,4) NULL    COMMENT '快照当时的上月费效比',
  delta           DECIMAL(12,4) NULL    COMMENT '快照当时的费效比环比变化',
  prev_month      CHAR(7)      NULL     COMMENT '对比的上一个月',
  sample_note     VARCHAR(300) NULL     COMMENT '快照当时的可信度说明',
  sample_cnt      INT          NULL     COMMENT '快照当时的已付款单据数，即这份结论的样本量；页面写「样本 N 单」',
  confidence      DECIMAL(4,2) NULL     COMMENT '快照当时的置信度 0~1 = 样本充足度 × 数字校验通过率 × 产出回填完整度，不由模型自报',
  local_flag      TINYINT      NULL     DEFAULT 1 COMMENT '1 = 规则生成的本地版本，0 = 真走了模型',
  model           VARCHAR(40)  NULL     COMMENT '实际模型名',
  prompt_version  VARCHAR(20)  NULL     COMMENT '生成时的 prompt 版本号',
  generated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报告生成时间，页面上的「更新于」读这个',
  generated_by    VARCHAR(20)  NOT NULL DEFAULT 'AUTO' COMMENT '生成方式：AUTO 首次自动 / SCHEDULED 凌晨定时 / MANUAL 手动重新分析',
  PRIMARY KEY (id),
  UNIQUE KEY uk_month (month),
  KEY idx_generated (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理层月度洞察报告表：每月留一条当前生效的解读，与 ai_log 的逐次调用留痕互补';

-- 18. 理由字段草稿表 --------------------------------------------------
-- 审批意见、财务复核说明、付款备注、强制提交说明、超标说明这五处
-- 都是「一句话要斟酌很久」的文本框，写一半丢了的代价很高。
-- 它们所在的单据此时都处在某个确定的业务状态里（待审批 / 待复核 / 待付款），
-- 没有「整单存草稿」这一档可挂，于是单开一张通用草稿表。
--
-- 五个落点形状完全一样（某人 + 某张单 + 某个字段 = 一段文字），
-- 差别只在 biz_type 与 field_name，因此用四元组唯一键而不是铺五张表。
--
-- 与正式字段的关系：草稿只暂存，正式值永远在业务表上
-- （approval_record.opinion / payment_record.pay_remark /
--   expense_report.force_reason / expense_report.review_reason / trip_apply.exceed_reason）。
-- 提交成功那一刻草稿失效，由 Service 层顺手清掉。
CREATE TABLE field_draft (
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

-- =====================================================================
-- 外模式：三个常用视图
-- 注：MySQL 不支持给视图列加 COMMENT，因此视图列的含义写在下面注释里
-- =====================================================================

-- v_approval_todo 列：apply_no 申请号 / reason 事由 / est_total 预估合计 /
--   standard_result 差标结论 / status 状态 / applicant 申请人 / dept_name 部门 /
--   partner_name 往来对象 / avg_amount 同类均值 / min_amount 区间下限 /
--   max_amount 区间上限 / eval_level 评价档位 / total_score 评价总分
CREATE OR REPLACE VIEW v_approval_todo AS
SELECT a.apply_no, a.reason, a.est_total, a.standard_result, a.status,
       u.real_name AS applicant, d.dept_name, p.partner_name,
       c.avg_amount, c.min_amount, c.max_amount,
       e.level AS eval_level, e.total_score
FROM trip_apply a
JOIN sys_user u ON u.id = a.user_id
JOIN dept d     ON d.id = a.dept_id
JOIN partner p  ON p.id = a.partner_id
LEFT JOIN cost_ref c ON c.id = (
  SELECT c2.id FROM cost_ref c2
  WHERE c2.to_city = (SELECT i.to_city FROM trip_itinerary i WHERE i.apply_no = a.apply_no ORDER BY i.seq LIMIT 1)
  ORDER BY c2.sample_cnt DESC LIMIT 1
)
LEFT JOIN eval_result e ON e.apply_no = a.apply_no
WHERE a.status IN ('DEPT_REVIEW', 'LEADER_REVIEW');

-- v_finance_pending 列：report_no 报销单号 / apply_no 申请号 / total_amount 票据合计 /
--   offset_amount 冲抵额 / net_amount 净额 / precheck_result 预检结论 / applicant 申请人 /
--   invoice_cnt 本单票据数 / result_cnt 结果回填数
CREATE OR REPLACE VIEW v_finance_pending AS
SELECT r.report_no, r.apply_no, r.total_amount, r.offset_amount, r.net_amount,
       r.precheck_result, u.real_name AS applicant,
       (SELECT COUNT(*) FROM invoice i WHERE i.apply_no = r.apply_no) AS invoice_cnt,
       (SELECT COUNT(*) FROM biz_result b WHERE b.apply_no = r.apply_no) AS result_cnt
FROM expense_report r
JOIN trip_apply a ON a.apply_no = r.apply_no
JOIN sys_user u ON u.id = a.user_id
WHERE r.status = 'PENDING';

-- v_dept_monthly 列：dept_id 部门 / dept_name 部门名称 / ym 年月（yyyy-mm）/
--   total_pay 当月付款合计 / total_output 当月折算产出合计
CREATE OR REPLACE VIEW v_dept_monthly AS
SELECT a.dept_id, d.dept_name,
       DATE_FORMAT(pay.pay_at, '%Y-%m') AS ym,
       SUM(pay.pay_amount) AS total_pay,
       SUM(COALESCE(br.output_amount, 0)) AS total_output
FROM payment_record pay
JOIN expense_report r ON r.report_no = pay.report_no
JOIN trip_apply a ON a.apply_no = r.apply_no
JOIN dept d ON d.id = a.dept_id
LEFT JOIN (SELECT apply_no, SUM(output_amount) AS output_amount FROM biz_result GROUP BY apply_no) br
       ON br.apply_no = a.apply_no
GROUP BY a.dept_id, d.dept_name, DATE_FORMAT(pay.pay_at, '%Y-%m');

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 校验：
--   SHOW CREATE TABLE trip_apply;         -- 看字段中文备注
--   SELECT table_name, column_name, column_comment
--     FROM information_schema.columns
--    WHERE table_schema = 'travel_server';
-- =====================================================================
