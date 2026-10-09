// 与前端 Web 版 utils/dict.js 同源维护，值域必须逐字一致
// （tripType 是 cost_ref 联合匹配键之一，写错名字该组参考永远匹配不上且不报错）
export const TRIP_TYPES = ['验货', '商务拜访', '项目交付', '供应商审核', '展会', '其他']
export const TRAFFIC_TYPES = ['高铁', '飞机', '自驾', '长途汽车', '轮船']
export const CITY_LEVELS = ['一级', '二级', '三级', '四级', '五级']

export const APPLY_STATUS = {
  DRAFT: '草稿',
  DEPT_REVIEW: '部门审批中',
  FINAL_REVIEW: '复审中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CLOSED: '已关闭'
}

export const STANDARD_RESULT = {
  NORMAL: { text: '符合差标', cls: 'tag-green' },
  HIGH: { text: '接近上限', cls: 'tag-amber' },
  EXCEED: { text: '超出差标', cls: 'tag-red' }
}

// 报销单：状态与预检结论分开，别混着显示
export const EXPENSE_STATUS = {
  PENDING: { text: '待复核', cls: 'tag-amber' },
  APPROVED: { text: '待付款', cls: 'tag-blue' },
  PASS_WITH_ISSUE: { text: '带异常通过', cls: 'tag-amber' },
  RETURNED: { text: '已退回', cls: 'tag-red' },
  PAID: { text: '已付款', cls: 'tag-green' }
}

export const PRECHECK_RESULT = {
  PASS: { text: '预检通过', cls: 'tag-green' },
  WARN: { text: '有提醒', cls: 'tag-amber' },
  FORCE: { text: '需填强制说明', cls: 'tag-red' }
}

export const ROLE = {
  EMPLOYEE: '员工',
  APPROVER: '部门主管',
  FINANCE: '财务',
  MANAGER: '管理层',
  SYS_ADMIN: '系统管理员'
}

// 审批结论（B-03）：REJECTED 时 opinion 必填
export const DECISION_RESULT = {
  APPROVED: '通过',
  REJECTED: '驳回'
}

// 出差成果类型（FR-0108 回填）。取值来自后端 BizResult.TYPE_*，别自己造词
export const RESULT_TYPES = ['签单', '验收', '交付', '无进展']

export const EVAL_LEVEL = {
  A: 'A 优先', B: 'B 正常', C: 'C 观察', D: 'D 暂缓'
}

// 配置调整留痕：变更类型与动作。与后端 ConfigLog.TYPE_* / ACTION_* 对齐，留痕只追加不可改
export const CONFIG_TYPE = {
  STANDARD: '差标',
  BUDGET: '部门预算',
  DEPT: '部门'
}
export const CONFIG_ACTION = {
  INSERT: '新增',
  UPDATE: '修改',
  DELETE: '删除'
}

// 状态 → 标签配色，页面统一调这个
export function tagOf (map, key) {
  const hit = map[key]
  return hit ? hit.cls : 'tag-gray'
}

export function textOf (map, key) {
  const hit = map[key]
  if (!hit) return key || '—'
  return typeof hit === 'string' ? hit : (hit.text || '—')
}

// 金额：空值统一横杠，不写「待填」这类文字
export function fmtMoney (n) {
  if (n === null || n === undefined || n === '') return '—'
  return Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function fmtInt (n) {
  if (n === null || n === undefined || n === '') return '—'
  return String(Math.round(Number(n)))
}

export function dash (v) {
  return (v === null || v === undefined || v === '') ? '—' : v
}
