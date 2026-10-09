// 后端接口封装。路径与 travel-server 的 Controller 一一对应，
// 返回体统一 R{code,msg,data}，request.js 里已解掉外壳，这里拿到的就是 data
import { get, post, put, del, uploadInvoice } from '../utils/request'

/* ---------------- 系统 ---------------- */

export const login = (username, password) => post('/system/login', { username, password })
export const logout = () => post('/system/logout')
export const me = () => get('/system/me')

/* ---------------- 申请单（员工） ---------------- */

export const applyPage = (params) => get('/apply/page', params)
export const applyDetail = (applyNo) => get(`/apply/${encodeURIComponent(applyNo)}`)

// 先存草稿拿 applyNo，票据要挂在单子上
export const saveDraft = (body) => post('/apply/draft', body)
export const submitApply = (body) => post('/apply/submit', body)
// 驳回后改完重提（PUT /apply/{applyNo}）
export const resubmitApply = (applyNo, body) => put(`/apply/${encodeURIComponent(applyNo)}`, body)
export const deleteDraft = (applyNo) => del(`/apply/${encodeURIComponent(applyNo)}`)
// 手工结案：限本人、限已通过、名下有在途报销单会报 2002
export const closeApply = (applyNo) => post(`/apply/${encodeURIComponent(applyNo)}/close`)
// 出差成果回填
export const backfillResult = (applyNo, body) =>
  post(`/apply/${encodeURIComponent(applyNo)}/result`, { applyNo, ...body })

// AI 填写建议（点「AI 分析」才跑，金额一律后端算）
export const applyAdvice = (body) => post('/apply/advice', body)
// 同类花费参考（实时聚合，填一项重查一次）
export const costRef = (params) => get('/apply/cost-ref', params)

/* ---------------- 票据 ---------------- */

export const invoiceList = (applyNo) => get('/invoice/list', { applyNo })
export const invoiceDelete = (id) => del(`/invoice/${id}`)
export const invoiceSearch = (params) => get('/invoice/search', params)
// 影像地址带过期时间，加载失败时调这个换一条新的
export const invoiceUrl = (id) => get(`/invoice/${id}/url`)
// 只问一句「这张票该挂到哪段」，只读不落库
export const invoiceMatch = (params) => get('/invoice/match', params)
export const invoiceConfirm = (body) => post('/invoice/confirm', body)

/* ---------------- 报销单 ---------------- */

export const expensePreview = (applyNo) => get('/expense/preview', { applyNo })
export const expenseSubmit = (body) => post('/expense/submit', body)
export const expensePage = (params) => get('/expense/page', params)
export const expenseDetail = (reportNo) => get(`/expense/${encodeURIComponent(reportNo)}`)

/* ---------------- 预借款 ---------------- */

export const loanGet = (applyNo) => get(`/loan/${encodeURIComponent(applyNo)}`)

/* ---------------- 审批 ---------------- */

// listType: todo 待我审批 / done 已办
export const approvalTodo = (params) => get('/approval/todo', params)
export const approvalDetail = (applyNo) => get(`/approval/${encodeURIComponent(applyNo)}`)
export const approvalRisk = (applyNo) => post(`/approval/${encodeURIComponent(applyNo)}/risk`)
export const approvalDecision = (applyNo, body) =>
  post(`/approval/${encodeURIComponent(applyNo)}/decision`, body)

/* ---------------- 财务 ---------------- */

export const financePending = (params) => get('/finance/pending', params)
export const financePayable = (params) => get('/finance/payable', params)
export const financeCheck = (reportNo) => get(`/finance/${encodeURIComponent(reportNo)}/check`)
export const financeSuspicion = (reportNo) =>
  get(`/finance/${encodeURIComponent(reportNo)}/suspicion`)
export const financeReview = (reportNo, body) =>
  post(`/finance/${encodeURIComponent(reportNo)}/review`, body)
export const financePay = (reportNo, body) =>
  post(`/finance/${encodeURIComponent(reportNo)}/pay`, body)

/* ---------------- 评价 ---------------- */

export const evalPreview = (body) => post('/eval/preview', body)
export const evalGet = (applyNo) => get(`/eval/${encodeURIComponent(applyNo)}`)
export const evalSemantic = (body) => post('/eval/semantic', body)

/* ---------------- 管理层分析 ---------------- */

export const analyticsSummary = (month) => get('/analytics/summary', { month })
export const analyticsMonthly = (month) => get('/analytics/monthly', { month })
export const analyticsDrill = (deptId, month) => get('/analytics/drill', { deptId, month })
export const analyticsPartner = (month) => get('/analytics/partner', { month })
// 月度洞察：GET 有就直出，POST 强制重算
export const insight = (month) => get('/analytics/insight', { month })
export const insightRefresh = (month) => post('/analytics/insight/refresh', { month })

/* ---------------- AI 助手（与网页端同源，后端接口已实装） ---------------- */

/**
 * 员工 AI 助手：问一句差旅 / 报销规定，系统照库里现行的规定答，答不上来就说答不上来。
 * 返回 { question, answer, answerable, facts[], local, allFacts[] }
 */
export const policyQa = (question) => post('/ai/policy/qa', { question })

/**
 * 管理层 AI 助手：用大白话问经营数据，系统把真实数字算出来讲成一句人话。
 * 返回 { question, answer, answerable, data:{ rows?/items?/byStatus?/标量字段 } }
 */
export const dataQa = (question) => post('/ai/data/qa', { question })

/**
 * AI 助手面板每页暂存：每人每页一份，退出面板时写一条，下次进同一页原样恢复。
 * 存后端不存本地——需求是「下次登录还在」。存不上不打扰人（silence）。
 */
export const panelGet = (pageKey) => get('/ai/panel/get', { pageKey }, { silence: true })
export const panelSave = (pageKey, content) =>
  post('/ai/panel/save', { pageKey, content }, { silence: true })
export const panelClear = (pageKey) => del('/ai/panel', { pageKey }, { silence: true })

/**
 * 系统管理员：AI 运行状况（最近调用量 / 成功率 / 降级次数 / 采纳率 + 分场景 + 留痕）。
 * days 统计窗口，默认 7 天。
 */
export const aiStatus = (days = 7) => get('/admin/ai/status', { days })

/* ---------------- 配置：差标与部门预算（/config 整体限管理层） ---------------- */

export const standards = () => get('/config/standard')
// 新增：这个组合从来没配过标准时用它
export const createStandard = (body) => post('/config/standard', body)
// 调整：插一条新的生效日期行，旧版本留着给旧单据追溯
export const updateStandard = (body) => put('/config/standard', body)
// 删除原因走 query：DELETE 带 body 在部分网关上会被丢掉
export const deleteStandard = (id, reason) => del(`/config/standard/${id}`, { reason: reason || '' })

export const depts = () => get('/config/depts')
export const createDept = (body) => post('/config/dept', body)
export const updateBudget = (body) => put('/config/budget', body)
export const deleteDept = (id, reason) => del(`/config/dept/${id}`, { reason: reason || '' })

// 调整留痕：只追加，谁也不能改
export const configLogs = (params) => get('/config/logs', params)

/* ---------------- 字段级草稿 ---------------- */

export const fieldDraftSave = (body) => post('/draft/field/save', body)
export const fieldDraftGet = (params) => get('/draft/field/get', params)
export const fieldDraftDelete = (params) => del('/draft/field')

/* ---------------- 关联对象 ---------------- */

export const partnerList = () => get('/partner/list')

/* ---------------- 系统管理员（只有 SYS_ADMIN 能调） ---------------- */

export const adminPartners = (params) => get('/admin/partners', params)
export const adminCreatePartner = (body) => post('/admin/partner', body)
export const adminUpdatePartner = (id, body) => put(`/admin/partner/${id}`, body)
// 删除原因走 query：DELETE 带 body 在部分网关上会被丢掉
export const adminDeletePartner = (id, reason) =>
  del(`/admin/partner/${id}`, { reason: reason || '' })
export const adminUsers = (params) => get('/admin/users', params)

export { uploadInvoice }
