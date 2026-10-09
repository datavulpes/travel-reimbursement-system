// 底部导航：按角色给不同的功能区。
// 微信小程序原生 tabBar 在 pages.json 里是写死的（不能按角色增删项），
// 所以这里改成自研的 TabBar 组件读这份配置渲染，「工作台」「我的」每个角色都有，
// 中间那几项只给真正干这活的角色——不属于本角色的功能连入口都不出现。
import { role } from './session'

const HOME = {
  key: 'home', text: '工作台', url: '/pages/home/home',
  icon: '/static/tab/home.png', on: '/static/tab/home_on.png'
}
const MINE = {
  key: 'mine', text: '我的', url: '/pages/mine/mine',
  icon: '/static/tab/mine.png', on: '/static/tab/mine_on.png'
}
const APPLY = {
  key: 'apply', text: '申请单', url: '/pages/list/list',
  icon: '/static/tab/apply.png', on: '/static/tab/apply_on.png'
}
const EXPENSE = {
  key: 'expense', text: '报销', url: '/pages/expense/list',
  icon: '/static/tab/expense.png', on: '/static/tab/expense_on.png'
}
const APPROVAL = {
  key: 'approval', text: '审批', url: '/pages/approval/todo',
  icon: '/static/tab/approval.png', on: '/static/tab/approval_on.png'
}
// 管理层走的是复审环节，同一个页面，说法不同
const REVIEW = { ...APPROVAL, text: '复审' }
const FINANCE = {
  key: 'finance', text: '财务', url: '/pages/finance/list',
  icon: '/static/tab/finance.png', on: '/static/tab/finance_on.png'
}
const REPORT = {
  key: 'report', text: '概览', url: '/pages/report/report',
  icon: '/static/tab/report.png', on: '/static/tab/report_on.png'
}
const ADMIN = {
  key: 'admin', text: '档案', url: '/pages/admin/data',
  icon: '/static/tab/admin.png', on: '/static/tab/admin_on.png'
}

const ROLE_TABS = {
  // 员工：自己填单、自己报账
  EMPLOYEE: [HOME, APPLY, EXPENSE, MINE],
  // 部门主管：只审自己部门交上来的
  APPROVER: [HOME, APPROVAL, MINE],
  // 财务：复核票据、付款
  FINANCE: [HOME, FINANCE, MINE],
  // 管理层：复审 + 看经营概览（差标与预算从概览页进去）
  MANAGER: [HOME, REVIEW, REPORT, MINE],
  // 系统管理员：维护往来对象与用户档案
  SYS_ADMIN: [HOME, ADMIN, MINE]
}

/** 当前角色的导航项；认不出的角色只给工作台和我的，不给任何业务入口 */
export function tabsOf (r) {
  return ROLE_TABS[r || role()] || [HOME, MINE]
}

/** 这个角色有没有某个功能区。页面用它做兜底守卫，防止手输路径进到别人的页面 */
export function hasTab (key, r) {
  return tabsOf(r).some((t) => t.key === key)
}

/** 角色进不去某页时的落点：他有权限的第一个 tab */
export function homeOf (r) {
  return tabsOf(r)[0].url
}
