/**
 * 后端接口总表 —— 与桌面《企业差旅管理工具 接口文档(对齐后端).openapi.json》一致。
 *
 * 全量 37 个接口，这里 36 个有对应方法（I-04 影像不发 axios 请求，用下面的
 * invoiceFileUrl 拼地址，通常直接用后端返回的 imgUrl）。
 * A-07 结果回填已在「我的申请」列表与详情抽屉做了入口；H-05 重算参考后端已实现，页面上还没做入口，方法先备着。
 *
 * 路径、方法、字段名全部按接口文档约定，把 .env 里 VITE_USE_MOCK 改成 false 即可直连。
 */
import request, { TOKEN_KEY } from './request'

/* ============ 一、系统 I-01 ~ I-04 ============ */

/** [I-01] 登录，返回 token 与角色，前端按 role 进入对应端口 */
export const login = (data) => request.post('/system/login', data)

/** [I-02] 退出登录 */
export const logout = () => request.post('/system/logout')

/** [I-03] 当前用户信息 */
export const getMe = () => request.get('/system/me')

/**
 * [I-04] 读取票据影像。
 *
 * 后端已经把带签名、有过期时间的完整地址放在 InvoiceVO.imgUrl / InvoiceUploadVO 里直接返回了，
 * 前端不要自己拼。这里只在「只有裸路径、没有签名」时兜底拼一下（mock 数据、老数据场景），
 * path 里有斜杠，必须走 query 参数，不能拼成 /invoice/file/2026/09/xxx.jpg。
 */
export const invoiceFileUrl = (path, exp, sign) => {
  const base = import.meta.env.VITE_API_BASE || '/api'
  if (!path) return ''
  if (exp && sign) return `${base}/invoice/file?path=${encodeURIComponent(path)}&exp=${exp}&sign=${encodeURIComponent(sign)}`
  return `${base}/invoice/file?path=${encodeURIComponent(path)}`
}

/* ============ 二、出差申请 A-01 ~ A-08 ============ */

/**
 * [A-01] 保存草稿。
 * data 里带 applyNo 就是改写那张已有草稿（申请号不变），不带则新建一张。
 */
export const saveDraft = (data) => request.post('/apply/draft', data)

/**
 * [A-02] 提交出差申请，同步返回差标结论与事前合理性提示。
 * 直接提交草稿时带上 applyNo，后端把 DRAFT 推到 DEPT_REVIEW，申请号不变。
 */
export const submitApply = (data) => request.post('/apply/submit', data)

/**
 * [A-08] 删除草稿。只有本人、还停在 DRAFT 的单能删。
 */
export const deleteApply = (applyNo) => request.delete(`/apply/${applyNo}`)

/** [A-03] 我的申请列表（分页） */
export const myApplyPage = (params) => request.get('/apply/page', { params })

/** [A-04] 申请详情（含行程段、票据、流转记录） */
export const applyDetail = (applyNo) => request.get(`/apply/${applyNo}`)

/** [A-05] 驳回后修改重新提交 */
export const updateApply = (applyNo, data) => request.put(`/apply/${applyNo}`, data)

/** [A-06] 同类花费参考 */
export const costRef = (params) => request.get('/apply/cost-ref', { params })

/**
 * 往来对象下拉数据，直接读 partner 表。
 * 以前这个下拉是前端写死的四家常客，库里新增或改了名字下拉就对不上，
 * 员工能选到一个数据库里查不到的对象，审批页关联对象那一栏只能空着。
 */
export const partnerOptions = () => request.get('/partner/list')

/** [A-07] 结果回填：出差带回了什么（签单/验收/交付/无进展），费效分析的产出来源 */
export const backfillResult = (applyNo, data) => request.post(`/apply/${applyNo}/result`, data)

/**
 * 手工关闭申请单。补「批了但没成行、也没有费用要报」这类单的出口，
 * 否则它们会一直停在 APPROVED，永远走不到付款自动结案那一步。
 *
 * 后端会在名下有在途报销单（PENDING / APPROVED / PASS_WITH_ISSUE / RETURNED）时直接拒掉（2002），
 * 前端不做这道判断——这里查不到报销单，重复实现一遍规则早晚会对不上。
 */
export const closeApply = (applyNo) => request.post(`/apply/${applyNo}/close`, {})

/**
 * 催办。只记一笔，不改状态、不发消息——待办列表上多一个「已催 N 次」。
 * 后端按「同一人同一单 24 小时一次」节流，重复催会被拒（2002），照着弹就行。
 */
export const remindApply = (applyNo, note) =>
  request.post(`/apply/${applyNo}/remind`, {}, { params: { note } })

/** AI助手：问一句差旅 / 报销规定，系统照库里现行的规定答，答不上来就说答不上来 */
export const policyQa = (question) => request.post('/ai/policy/qa', { question })

/**
 * [AI] AI助手：管理层用大白话问业务数据，系统把真实数字算出来、讲成一句人话。
 * 数字来自本库，模型只做叙事；模型没接上时直接陈列真实数字（result.local = true）。
 */
export const dataQa = (question) => request.post('/ai/data/qa', { question })

/**
 * AI 助手侧边栏的暂存：每人每页一份，退出面板时写一条，下次进同一页原样恢复。
 * 存后端不存浏览器本地——需求是「下次登录还在」，换台机器本地存储就没了。
 * 存不上不弹红条：面板内容存不下不该打断人填单。
 */
export const panelGet = (pageKey) =>
  request.get('/ai/panel/get', { params: { pageKey }, silence: true })

export const panelSave = (pageKey, content) =>
  request.post('/ai/panel/save', { pageKey, content }, { silence: true })

export const panelClear = (pageKey) =>
  request.delete('/ai/panel', { params: { pageKey }, silence: true })

/* ============ 三、审批 B-01 ~ B-03 ============ */

/** [B-01] 待审列表 */
export const approvalTodo = (params) => request.get('/approval/todo', { params })

/** [B-02] 审批详情（申请 + 参考 + 差标 + 评价 + 流转） */
export const approvalDetail = (applyNo) => request.get(`/approval/${applyNo}`)

/** [B-03] 提交审批结论，后端写依据快照 */
export const approvalDecision = (applyNo, data) =>
  request.post(`/approval/${applyNo}/decision`, data)

/** [C-01] 上传票据影像并 OCR（multipart） */
export const uploadInvoice = (formData) =>
  request.post('/invoice/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })

/** [C-02] 确认票面字段并挂接行程段 */
export const confirmInvoice = (data) => request.post('/invoice/confirm', data)

/** [C-03] 本单票据清单与缺票推算 */
export const invoiceList = (params) => request.get('/invoice/list', { params })

/** [C-04] 删除未提交票据 */
export const deleteInvoice = (id) => request.delete(`/invoice/${id}`)

/** [I-05] 票据影像查询：按申请号、票号/销售方、日期、金额筛，返回带缩略图的票据 */
export const invoiceSearch = (params) => request.get('/invoice/search', { params })

/**
 * [I-06] 重新取一张票的影像链接。
 * 影像地址带过期时间，页面开久了会加载失败，调这个换一条新的，不用整页刷新。
 */
export const invoiceUrl = (id) => request.get(`/invoice/${id}/url`)

/**
 * 取票据影像的 blob 地址，给预览用。
 *
 * 影像接口要登录态，而 <img src> 不会带 Authorization 头，直接挂上去会 401，
 * 所以这里自己带着 token 去取，转成本地 objectURL 再喂给 img。
 * 用完记得 URL.revokeObjectURL，不然页面开着就一直占着内存。
 */
export async function fetchInvoiceImage (url) {
  const res = await fetch(url, {
    headers: { Authorization: localStorage.getItem(TOKEN_KEY) || '' }
  })
  if (!res.ok) throw new Error('影像加载失败 HTTP ' + res.status)
  return URL.createObjectURL(await res.blob())
}

/* ============ 四、报销 D-01 ~ D-04 ============ */

/** [D-01] 预借额度查询 */
export const loanInfo = (applyNo) => request.get(`/loan/${applyNo}`)

/** [D-02] 提交报销单 */
/** [D-02] 前置预检：提交前看结论，FORCE 时亮出强制说明输入框 */
export const expensePreview = (params) => request.get('/expense/preview', { params })

/** [D-02] 提交报销 */
export const submitExpense = (data) => request.post('/expense/submit', data)

/** [D-03] 我的报销列表 */
export const myExpensePage = (params) => request.get('/expense/page', { params })

/** [D-04] 报销单详情 */
export const expenseDetail = (reportNo) => request.get(`/expense/${reportNo}`)

/* ============ 五、财务复核 E-01 ~ E-04 ============ */

/** [E-01] 待复核列表 */
export const financePending = (params) => request.get('/finance/pending', { params })

/** [E-04] 待付款列表：复核通过但还没打款的单 */
export const financePayable = (params) => request.get('/finance/payable', { params })

/** [E-02] 四维比对结果 */
export const fourDimCheck = (reportNo) => request.get(`/finance/${reportNo}/check`)

/** [E-03] 提交复核结论 */
export const financeReview = (reportNo, data) => request.post(`/finance/${reportNo}/review`, data)

/** [E-04] 付款并冲抵预借 */
export const financePay = (reportNo, data) => request.post(`/finance/${reportNo}/pay`, data)

/* ============ 六、费效分析 F-01 ~ F-04 ============ */

/** [F-01] 部门费效比月报 */
export const monthlyReport = (params) => request.get('/analytics/monthly', { params })

/** [F-02] 归因钻取 */
export const drillDown = (params) => request.get('/analytics/drill', { params })

/** [F-03] 对象投入产出对照 */
export const partnerIo = (params) => request.get('/analytics/partner', { params })

/** [F-04] 月报四个关键数字 */
export const analyticsSummary = (params) => request.get('/analytics/summary', { params })

/* ============ 七、合理性评价 G-01 ~ G-02 ============ */

/** [G-01] 评价结论与依据明细 */
export const evalResult = (applyNo) => request.get(`/eval/${applyNo}`)

/** [G-02] 事前提示预演：填写过程中实时算一次 */
export const evalPreview = (data) => request.post('/eval/preview', data)

/* ============ 八、差标与预算配置 H-01 ~ H-05 ============ */

/** [H-01] 差标列表 */
export const standardList = (params) => request.get('/config/standard', { params })

/**
 * 新增一档差标。这个「城市等级 + 交通方式」组合从来没配过标准时走这条，
 * 配过的请走下面的调整——后端会分别往留痕里写 INSERT 和 UPDATE，两种动作混不了。
 */
export const createStandard = (data) => request.post('/config/standard', data)

/** [H-02] 调整差标：插一条新版本，旧版本保留 */
export const updateStandard = (data) => request.put('/config/standard', data)

/**
 * 删除某一版差标，后端只认该组最新的一档。
 * 删除原因走 query：axios 的 DELETE 带 body 在部分网关上会被丢掉。
 */
export const deleteStandard = (id, reason) =>
  request.delete(`/config/standard/${id}`, { params: { reason } })

/** [H-03] 调整部门预算 */
export const updateBudget = (data) => request.put('/config/budget', data)

/** [H-04] 调整留痕查询（只读），type 传 STANDARD / BUDGET 可筛选 */
export const configLogs = (params) => request.get('/config/logs', { params })

/**
 * 部门清单与预算，替掉 config/depts.js 里写死的四个部门。
 * 返回带 memberCount / applyCount，为 0 的部门才允许删除。
 */
export const deptList = () => request.get('/config/depts')

/** 新增部门，顺带给定初始月度预算 */
export const createDept = (data) => request.post('/config/dept', data)

/**
 * 删除部门。名下有员工或申请单的部门后端会直接拒掉（1001），
 * 前端靠 DeptVO 的 memberCount / applyCount 提前把按钮置灰。
 */
export const deleteDept = (id, reason) =>
  request.delete(`/config/dept/${id}`, { params: { reason } })

/** [H-05] 重算同类花费参考（改完差标或新增票据后手动刷新样本） */
export const refreshCostRef = () => request.post('/config/cost-ref/refresh')

/* ============ 系统管理员：基础档案 ============
 * 这一组只有 SYS_ADMIN 角色能调，后端 AdminController 整个加了角色限制。
 * 差标金额与部门预算不在这组里——那两项参与运行时计算（预借额度上限、超标判定），
 * 归管理层在 /config 下调。
 */

/** 往来对象分页列表，keyword 按名称过滤。applyCount 大于 0 的不允许删除 */
export const adminPartnerPage = (params) => request.get('/admin/partners', { params })

/** 新增往来对象。累计投入与产出一律从 0 起，后端不收这两个字段 */
export const createPartner = (data) => request.post('/admin/partner', data)

/** 修改往来对象的名称与类型 */
export const updatePartner = (id, data) => request.put(`/admin/partner/${id}`, data)

/** 删除往来对象，被单据引用过后端会拒掉（1001） */
export const deletePartner = (id, reason) =>
  request.delete(`/admin/partner/${id}`, { params: { reason } })

/** 用户账号分页列表，可按 role / deptId / keyword 过滤。self 为 true 的那行是自己 */
export const adminUserPage = (params) => request.get('/admin/users', { params })

/** 新增用户账号，初始密码必填，BCrypt 存密文 */
export const createAccount = (data) => request.post('/admin/user', data)

/** 修改用户。password 留空表示不重置；自己的角色改不了 */
export const updateAccount = (id, data) => request.put(`/admin/user/${id}`, data)

/** 删除用户（逻辑删）。有单据留痕或还是部门负责人的会被拦下 */
export const deleteAccount = (id, reason) =>
  request.delete(`/admin/user/${id}`, { params: { reason } })

/** 改部门名称与负责人。月度预算与差标等级不在参数里，那两项归管理层 */
export const updateDeptInfo = (id, data) => request.put(`/admin/dept/${id}`, data)

/* ============ 理由字段草稿 ============
 *
 * 审批意见、财务复核说明、付款备注、强制提交说明、超标说明这五处
 * 都是「一句话要斟酌很久」的框，写到一半丢了的代价很高，
 * 而它们所在的单据此时已有确定状态，没有「整单存草稿」这一档可挂。
 * 后端为此单开了一张 field_draft，键是（场景 + 单号 + 字段名 + 本人）。
 *
 * 三个接口统一 silence：草稿是锦上添花的能力，存不上时不弹红条吓人，
 * 由 DraftTextarea 自己在框下面写一句「草稿没存上，内容还在框里」。
 */

/** 保存草稿。content 留空表示清空（后端按删除处理） */
export const saveFieldDraft = (data) => request.post('/draft/field/save', data, { silence: true })

/** 读回草稿。没存过返回 content 为 null */
export const getFieldDraft = (params) =>
  request.get('/draft/field/get', { params, silence: true })

/** 删除草稿。提交成功后后端自己会清，这里给前端主动清用 */
export const deleteFieldDraft = (params) =>
  request.delete('/draft/field', { params, silence: true })

/* ============ 系统管理员：全表只读浏览 ============ */

/** 可以浏览哪些表，返回每张表的行数、列清单与注意事项 */
export const browseTables = () => request.get('/admin/tables')

/**
 * 浏览一张表的一页数据。name 取自 browseTables 的返回，别自己拼表名。
 * id 可选，填了只取这一行。
 */
export const browseTable = (name, params) => request.get(`/admin/tables/${name}`, { params })

/* ============ 系统管理员：AI 运行状况 ============ */

/**
 * AI 运行状况：最近几天的调用量、成功率、降级次数与失败原因。
 *
 * <p>数字全部由后端实时聚合 ai_log 得出，没有统计表，
 * 所以「没有数据」就是真没跑过，不是没统计到。
 *
 * @param days 统计窗口，默认 7 天
 */
export const aiStatus = (days = 7) => request.get('/admin/ai/status', { params: { days } })
