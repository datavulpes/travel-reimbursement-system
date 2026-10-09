// 全系统枚举的中文口径，统一在这里维护，避免各页面各写一套

export const APPLY_STATUS = {
  DRAFT: { text: '草稿', type: 'info' },
  DEPT_REVIEW: { text: '部门审批中', type: 'warning' },
  FINAL_REVIEW: { text: '复审中', type: 'warning' },
  APPROVED: { text: '已通过', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
  CLOSED: { text: '已关闭', type: 'info' }
}

export const STANDARD_RESULT = {
  NORMAL: { text: '符合差标', type: 'success' },
  HIGH: { text: '接近上限', type: 'warning' },
  EXCEED: { text: '超出差标', type: 'danger' }
}

// 业务结果回填（FR-0108）。后端 BizResult 的 TYPE_* 常量用中文，这里保持一致
export const RESULT_TYPE = {
  签单: { text: '签单', type: 'success' },
  验收: { text: '验收', type: 'success' },
  交付: { text: '交付', type: 'success' },
  无进展: { text: '无进展', type: 'info' }
}

export const EVAL_LEVEL = {
  SUGGEST: { text: '建议出行', type: 'success' },
  NOTICE: { text: '值得关注', type: 'warning' },
  ATTENTION: { text: '重点关注', type: 'danger' }
}

export const PRECHECK_RESULT = {
  PASS: { text: '预检通过', type: 'success' },
  WARN: { text: '有提醒', type: 'warning' },
  FORCE: { text: '需强制说明', type: 'danger' }
}

export const EXPENSE_STATUS = {
  PENDING: { text: '待复核', type: 'warning' },
  APPROVED: { text: '复核通过', type: 'success' },
  PASS_WITH_ISSUE: { text: '带异常通过', type: 'warning' },
  RETURNED: { text: '已退回', type: 'danger' },
  PAID: { text: '已付款', type: 'info' }
}

export const LOAN_STATUS = {
  ACTIVE: { text: '有效', type: 'success' },
  OFFSET: { text: '部分冲抵', type: 'warning' },
  SETTLED: { text: '已结清', type: 'info' }
}

// 流转节点。SUBMIT 与 CLOSE 不是审批环节，是「谁动了这张单」的动作留痕，
// 但一样要有中文名——时间轴上摆一个裸英文，看着像漏了字典
export const APPROVAL_STEP = {
  DEPT_REVIEW: '部门初审',
  FINAL_REVIEW: '管理层复审',
  SUBMIT: '提交申请',
  CLOSE: '手工关闭'
}

export const APPROVAL_RESULT = {
  APPROVED: { text: '通过', type: 'success' },
  REJECTED: { text: '驳回', type: 'danger' },
  CLOSED: { text: '已关闭', type: 'info' }
}

// 城市等级与 trip_standard.city_level 的取值严格一致（初始化数据里是「一级」不是「一线」）。
// 之前这里写的一线/新一线/其他，员工填行程时选完落到库里是没有对应差标的档位，
// currentMax 查不到标准，整单被跳过校验，超标也判成 NORMAL。
export const CITY_LEVELS = ['一级', '二级', '三级', '四级', '五级']
/*
 * 交通方式。同时是行程段的取值与差标的匹配键（trip_standard.traffic_type），两处共用一张表。
 *
 * 原先把「住宿」也列在这里，理由是差标表单独有一档住宿上限。实际上：
 * 差标校验按行程段逐段比对 currentMax(city_level, traffic_type)，住宿费单独占 est_hotel 一列、
 * 从来不按段走差标，初始化数据里也没有任何 traffic_type = '住宿' 的行。
 * 于是员工选了「住宿」等于这一段查不到差标、被静默跳过校验，看起来是选到了，实际什么都没判。
 * 抽掉这个取值比留着诚实：差标表里加了这一档之前，它就不该出现在选项里。
 */
export const TRAFFIC_TYPES = ['高铁', '飞机', '自驾', '长途汽车', '轮船']
// 出差类型。这几个字同时出现在 cost_ref.trip_type 里，是同类花费参考的联合匹配键之一。
// 改名等于让那一组参考再也匹配不上，还会连带把 AI 给出的出差类型建议变成下拉框里没有的值。
// 改动必须同步：后端 TripApply 注释、schema.sql 两处 comment、本行，共四处。
export const TRIP_TYPES = ['验货', '商务拜访', '项目交付', '供应商审核', '展会', '其他']
// 六个里只有这一个要额外补一句说明。单独拎出来是因为「选了它就弹补充框」这个判断
// 散在填写页、提交组装、详情展示三处，写死字符串的话改一次值域要改三遍
export const TRIP_TYPE_OTHER = '其他'
export const PARTNER_TYPES = { CUSTOMER: '客户', SUPPLIER: '供应商' }

/**
 * 配置调整留痕里的动作类型，对应后端 ConfigLog.ACTION_*。
 * 留痕表只追加不修改，页面上也只能看，不做任何编辑入口。
 */
export const CONFIG_ACTION = {
  INSERT: { text: '新增', type: 'success' },
  UPDATE: { text: '修改', type: 'warning' },
  DELETE: { text: '删除', type: 'danger' }
}

/**
 * 配置调整留痕里的变更类型，对应后端 ConfigLog.TYPE_*。
 * 留痕表只追加不修改，页面上也只能看。
 *
 * 前两项由管理层产生，后三项由系统管理员产生，都进同一张表，
 * 所以管理层留痕页要认得全，不能只认自己写的那两种。
 */
export const CONFIG_TYPE = {
  STANDARD: { text: '差标', type: 'primary' },
  BUDGET: { text: '部门预算', type: 'warning' },
  DEPT: { text: '部门', type: 'warning' },
  PARTNER: { text: '往来对象', type: 'info' },
  USER: { text: '用户账号', type: 'info' },
  DEPT_INFO: { text: '部门档案', type: 'warning' }
}

// 票据/pdf 显示金额
export function money (v) {
  if (v === null || v === undefined || v === '') return '-'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/**
 * 费效比显示。口径是「投入 ÷ 产出」，值通常很小（花 700 赚 12 万 → 0.0058），
 * 一律两位小数会糊成 0.01，看不出差别，所以小于 0.1 的多给一位。
 */
export function ratio (v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (!isFinite(n)) return '-'
  return n.toFixed(n !== 0 && Math.abs(n) < 0.1 ? 3 : 2)
}

/**
 * 占比显示。后端一律存小数（0.425），页面显示整数百分比。
 * 空值显示「—」而不是 0%——「回填率是 0%」和「没有可统计的单据」是两回事。
 */
export function percent (v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (!isFinite(n)) return '-'
  return Math.round(n * 100) + '%'
}

// 取字典的 tag 类型，找不到就当普通标签
export function dictType (dict, key) {
  return (dict[key] || { text: key || '-', type: 'info' }).type
}

export function dictText (dict, key) {
  return (dict[key] || { text: key || '-' }).text
}
