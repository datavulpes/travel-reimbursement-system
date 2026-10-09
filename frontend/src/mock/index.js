/**
 * 本地 Mock 适配器 —— 后端没起来时接管全部请求
 * 只要 .env 里 VITE_USE_MOCK=true，request.js 就会把它挂到 axios 的 adapter 上
 * 后端接口一就绪，把开关改成 false，这里整体失效（不用删代码）
 */
import { MOCK_ACCOUNTS } from '@/config/menus'
import {
  applyDetailData, otherApplyDetail, applyListData,
  costRefData, standardHintsData, evalData,
  todoListData, historyListData, approvalDetailData,
  invoiceListData, ocrResultData, invoiceSearchData,
  loanData, expenseDetailData, expenseListData, precheckIssuesData,
  pendingListData, fourDimData,
  summaryData, monthlyData, ratioTrend, drillData, partnerIoData,
  deptListData, standardListData, configLogData
} from './data'

const routes = []

/** 注册一条 mock 路由，支持 /xxx/:id 形式的路径参数 */
function add (method, pattern, handler) {
  const keys = []
  const source = pattern.replace(/:(\w+)/g, (_, k) => {
    keys.push(k)
    return '([^/]+)'
  })
  routes.push({ method, keys, re: new RegExp('^' + source + '$'), handler })
}

/**
 * 成功返回体。形状对齐后端 R：code / msg / data 三件套。
 *
 * msg 可省略，缺省 'ok'。早点让它带上真实文案是因为——真后端的成功响应是有 msg 的
 * （「差标已更新，旧版本保留可追溯」这类），mock 一律回 'ok' 会让两种数据源长得不一样，
 * 一旦有页面直接把 res.msg 弹出来，mock 下看着的就是个莫名其妙的 "ok"。
 */
function ok (data, msg) {
  return { code: 0, msg: msg || 'ok', data }
}

function fail (code, msg, data) {
  return { code, msg, data: data || null }
}

function page (list, pageNum = 1, pageSize = 10) {
  const start = (Number(pageNum) - 1) * Number(pageSize)
  return {
    list: list.slice(start, start + Number(pageSize)),
    total: list.length,
    pageNum: Number(pageNum),
    pageSize: Number(pageSize)
  }
}

/* ============ 一、系统 ============ */

add('POST', '/system/login', ({ body }) => {
  const acc = MOCK_ACCOUNTS.find(
    (a) => a.username === body.username && a.password === body.password
  )
  if (!acc) {
    return fail(1001, '登录名或密码不正确')
  }
  return ok({
    token: 'mock-token-' + acc.role.toLowerCase() + '-' + Date.now(),
    userId: MOCK_ACCOUNTS.indexOf(acc) + 1,
    realName: acc.realName,
    role: acc.role,
    deptId: 2,
    deptName: acc.deptName
  })
})

add('POST', '/system/logout', () => ok(null))

add('GET', '/system/me', () => {
  const raw = localStorage.getItem('TE_USER')
  const user = raw ? JSON.parse(raw) : {}
  return ok({
    userId: user.userId || 1,
    username: user.username || 'wengshaogang',
    realName: user.realName || '翁绍刚',
    role: user.role || 'EMPLOYEE',
    deptId: user.deptId || 2,
    deptName: user.deptName || '销售部',
    bankAccount: '6222 **** **** 1234'
  })
})

/* ============ 二、出差申请 ============ */

/**
 * 草稿仓。带 applyNo 就改写原有那张（申请号不变），不带才发新号——
 * 对齐后端 ApplyService.save 的 resolveReusable 口径，
 * 不然 mock 下测不出来「同一个草稿反复存、单号不漂移」这件事。
 */
const drafts = []

function draftSeq () {
  return 700 + drafts.length
}

function draftDetail (d) {
  return {
    applyNo: d.applyNo,
    applicant: '翁绍刚',
    deptName: '销售部',
    partnerId: d.body.partnerId ?? 3,
    partnerName: '深圳市某某科技有限公司',
    reason: d.body.reason || '',
    tripType: d.body.tripType || '验货',
    // 只有选「其他」才有值；类型不是「其他」时前端会把它清掉再提交
    tripTypeNote: d.body.tripType === '其他' ? (d.body.tripTypeNote || '') : '',
    estTraffic: d.body.estTraffic ?? 0,
    estHotel: d.body.estHotel ?? 0,
    estTotal: d.body.estTotal ?? 0,
    standardResult: null,
    exceedReason: d.body.exceedReason || '',
    status: 'DRAFT',
    createdAt: d.createdAt,
    itineraries: (d.body.itineraries || []).map((it, i) => ({
      id: i + 1, seq: it.seq ?? i + 1, ...it, invoiceCnt: 0
    })),
    invoices: [],
    records: []
  }
}

add('POST', '/apply/draft', ({ body }) => {
  const hit = body.applyNo && drafts.find((d) => d.applyNo === body.applyNo)
  if (body.applyNo && !hit) {
    return fail(1001, '草稿不存在：' + body.applyNo)
  }
  const row = hit || {
    applyNo: 'TA20260918' + String(draftSeq()).padStart(4, '0'),
    body: {},
    createdAt: new Date().toLocaleString('zh-CN', { hour12: false })
  }
  row.body = { ...row.body, ...body }
  if (!hit) drafts.push(row)
  return ok({ applyNo: row.applyNo, status: 'DRAFT', estTotal: row.body.estTotal })
})

add('DELETE', '/apply/:applyNo', ({ params }) => {
  const no = decodeURIComponent(params.applyNo)
  const i = drafts.findIndex((d) => d.applyNo === no)
  if (i < 0) {
    return fail(1001, '草稿不存在：' + no)
  }
  drafts.splice(i, 1)
  return ok(null)
})

add('POST', '/apply/submit', ({ body }) => {
  const hints = standardHintsData
  const overLimit = hints.some((h) => h.result === 'EXCEED')
  if (overLimit && !body.exceedReason) {
    return fail(2003, '存在超出差标的行程段，请填写超标说明')
  }
  return ok({
    applyNo: 'TA202609180001',
    status: 'DEPT_REVIEW',
    standardResult: hints.some((h) => h.result === 'HIGH') ? 'HIGH' : 'NORMAL',
    standardHints: hints,
    evalPreview: {
      level: evalData.level,
      totalScore: evalData.totalScore,
      hints: [
        '该对象近 12 个月产出率 5.2，历史表现良好',
        '本次预估 2800 元，处于同类行程 1800~3400 元区间内',
        '事由未写明预期产出，建议补充'
      ]
    }
  })
})

add('GET', '/apply/page', ({ query }) => {
  // 草稿跟着第五组 list —— 把刚存的草稿也放进来，列表页才点得到「继续填写 / 删除」
  const draftRows = drafts.map((d) => ({
    applyNo: d.applyNo,
    reason: d.body.reason || '',
    applicant: '翁绍刚',
    deptName: '销售部',
    partnerName: '深圳市某某科技有限公司',
    estTotal: d.body.estTotal ?? 0,
    standardResult: null,
    status: 'DRAFT',
    createdAt: d.createdAt
  }))
  let scoped = [...draftRows, ...applyListData]
  if (query.status) {
    scoped = scoped.filter((row) => row.status === query.status)
  }
  return ok(page(scoped, query.pageNum, query.pageSize))
})

// 注册顺序是有讲究的：匹配按注册顺序取第一条命中，带 :applyNo 的路径排在前面就会
// 把 cost-ref 当成申请号吃掉，参考值接口返回一张假的详情对象。字面路径一律排前面。
add('GET', '/apply/cost-ref', ({ query }) => {
  // 与后端一致：填的交通方式凑不出样本就把这一维丢掉，并标 relaxed
  const trafficMatch = !query.trafficType || query.trafficType === costRefData.trafficType
  return ok({
    ...costRefData,
    toCity: query.toCity || costRefData.toCity,
    tripType: query.tripType || costRefData.tripType,
    trafficType: trafficMatch ? costRefData.trafficType : null,
    relaxed: !trafficMatch
  })
})

/**
 * 业务结果回填仓。key 是申请号，值是 BizResultVO 列表。
 *
 * <p>存下来是为了让 GET 详情能把刚回填的那条带回去——否则 mock 下点完「回填结果」，
 * 抽屉里的结果表还是空的，看着像后端没写进去。
 */
const backfilled = new Map()
let resultSeq = 500

function resultRows (applyNo) {
  return (backfilled.get(applyNo) || []).map((r) => ({ ...r }))
}

/** 在途报销单状态。必须与后端 ApplyService 的 OPEN_EXPENSE_STATUS 一字不差——
 *  RETURNED 也算在途：单子被退回后还压在员工手上，这时候关掉等于把没修好的账先盖上。 */
const OPEN_EXPENSE_STATUS = ['PENDING', 'APPROVED', 'PASS_WITH_ISSUE', 'RETURNED']

/** 这张单在假数据里当前处于什么状态。列表读 applyListData，详情读那两份样板对象 */
function statusOf (applyNo) {
  const row = applyListData.find((r) => r.applyNo === applyNo)
  if (row) return row.status
  if (applyNo === otherApplyDetail.applyNo) return otherApplyDetail.status
  if (applyNo === applyDetailData.applyNo) return applyDetailData.status
  return null
}

/** 改一张单的状态。列表和详情两处都要改，只改一处的话抽屉里还停在旧状态 */
function setApplyStatus (applyNo, status) {
  const row = applyListData.find((r) => r.applyNo === applyNo)
  if (row) row.status = status
  if (applyNo === otherApplyDetail.applyNo) otherApplyDetail.status = status
  if (applyNo === applyDetailData.applyNo) applyDetailData.status = status
}

add('GET', '/apply/:applyNo', ({ params }) => {
  const no = decodeURIComponent(params.applyNo)
  const draft = drafts.find((d) => d.applyNo === no)
  if (draft) {
    return ok(draftDetail(draft))
  }
  const detail = no === 'TA202609120003' ? otherApplyDetail : applyDetailData
  return ok({ ...detail, applyNo: no, results: resultRows(no) })
})

add('PUT', '/apply/:applyNo', ({ params }) =>
  ok({ applyNo: decodeURIComponent(params.applyNo), status: 'DEPT_REVIEW' })
)

/**
 * A-07 业务结果回填。
 *
 * <p>前置条件照抄后端 ApplyService.backfill：单子要存在，状态得是 APPROVED 或 CLOSED
 * （草稿和审批中的单还谈不上有结果）。必填校验跟着 ResultBackfillRequest 那两个注解走：
 * 结果类型必填，选了「无进展」可以不要产出金额——前端本来也只送 0。
 */
add('POST', '/apply/:applyNo/result', ({ params, body }) => {
  const no = decodeURIComponent(params.applyNo)
  const status = statusOf(no)
  if (!status) return fail(1001, '申请单不存在：' + no)
  if (status !== 'APPROVED' && status !== 'CLOSED') {
    return fail(2002, '申请未通过，不能回填业务结果')
  }
  if (!body.resultType) return fail(1001, '请选择结果类型')
  if (body.resultType !== '无进展' && body.outputAmount == null) {
    return fail(1001, '请填写折算产出金额')
  }
  const list = backfilled.get(no) || []
  list.push({
    id: resultSeq++,
    applyNo: no,
    resultType: body.resultType,
    resultDesc: body.resultDesc || '',
    outputAmount: Number(body.outputAmount || 0),
    filledBy: '翁绍刚',
    filledAt: now()
  })
  backfilled.set(no, list)
  return ok(null)
})

/**
 * 手工关闭申请单。
 *
 * <p>判定顺序与后端 ApplyService.close 一致，包括「已经是 CLOSED 也算成功」那条幂等：
 * 重复点、列表与抽屉前后脚触发都不该报错。整套判断不放前端，mock 也照旧把原因写进 msg，
 * 好让切换数据源时页面上弹出来的是同一套话。
 */
add('POST', '/apply/:applyNo/close', ({ params }) => {
  const no = decodeURIComponent(params.applyNo)
  const status = statusOf(no)
  if (!status) return fail(1001, '申请单不存在：' + no)
  if (status === 'CLOSED') return ok(null)
  if (status !== 'APPROVED') return fail(2002, '只有已通过的申请单才能手工关闭')
  const openCnt = expenseListData.filter(
    (r) => r.applyNo === no && OPEN_EXPENSE_STATUS.includes(r.status)
  ).length
  if (openCnt > 0) {
    return fail(2002, `这张单还有 ${openCnt} 张报销单没走完，请先把报销处理完再关闭`)
  }
  setApplyStatus(no, 'CLOSED')
  return ok(null)
})

/* ============ 三、审批 ============ */

add('GET', '/approval/todo', ({ query }) => {
  // listType=todo 待办 / done 已办
  const list = query.listType === 'done' ? historyListData : todoListData
  // 两级审批：部门审批人只见初审单，管理层只见复审单，口径与后端 ApprovalService.todo() 一致。
  // 不这么过滤，管理层进待办会看到本不属于自己这一级的单。
  let role = ''
  try {
    role = JSON.parse(localStorage.getItem('TE_USER') || 'null')?.role || ''
  } catch (e) {
    role = ''
  }
  const scoped = query.listType === 'done' || !role
    ? list
    : list.filter((row) => (role === 'MANAGER'
      ? row.status === 'FINAL_REVIEW'
      : row.status === 'DEPT_REVIEW'))
  return ok(page(scoped, query.pageNum, query.pageSize))
})

add('GET', '/approval/:applyNo', ({ params }) =>
  ok(approvalDetailData(decodeURIComponent(params.applyNo)))
)

add('POST', '/approval/:applyNo/decision', ({ params, body }) => {
  if (body.result === 'REJECTED' && !body.opinion) {
    return fail(1001, '驳回必须填写审批意见')
  }
  // 后端 B-03 返回 R<Void>，data 为 null；前端本来也不读返回值，只弹提示后跳列表
  // 顺手把状态机推一格，否则 mock 模式下批完还是 DEPT_REVIEW，
  // 管理层登进来永远看不到复审单。链路对齐后端 decision()：
  //   初审通过 DEPT_REVIEW → FINAL_REVIEW；复审通过 FINAL_REVIEW → APPROVED；驳回一律 REJECTED
  const row = todoListData.find((item) => item.applyNo === decodeURIComponent(params.applyNo))
  if (row) {
    if (body.result === 'REJECTED') {
      row.status = 'REJECTED'
    } else if (row.status === 'DEPT_REVIEW') {
      row.status = 'FINAL_REVIEW'
    } else if (row.status === 'FINAL_REVIEW') {
      row.status = 'APPROVED'
    }
  }
  return ok(null)
})

/* ============ 四、票据 ============ */

// C-01 上传：字段对齐后端 InvoiceUploadVO —— tempPath / fileName / fileSize / ocr / ocrSuccess
// 之前这里写 imgPath，前端按 tempPath 取值拿到 undefined，票据挂接后就没有影像了
add('POST', '/invoice/upload', () =>
  ok({
    tempPath: '2026/09/invoice_12345680.jpg',
    fileName: 'invoice_12345680.jpg',
    fileSize: 214528,
    ocr: ocrResultData,
    ocrSuccess: true
  })
)

add('POST', '/invoice/confirm', ({ body }) => {
  if (body.invoiceNo === '24412000000012345678') {
    return fail(2001, '发票重复：该发票号已被 TA202608120003 使用', { dupOf: 'TA202608120003' })
  }
  return ok({ id: 903, ...ocrResultData })
})

add('GET', '/invoice/list', ({ query }) => ok(invoiceListData))

// I-05 影像查询：路径必须写得比 /invoice/:id 靠前，否则会被参数路由吃掉
// （/apply/cost-ref 被 /apply/:applyNo 吃掉过一次，有断言盯着顺序）
add('GET', '/invoice/search', () => ok(invoiceSearchData))

// I-06 重新取链：预签名 30 分钟过期，前端拿到 403/加载失败时用它换一条新的
add('GET', '/invoice/:id/url', ({ params }) =>
  ok(invoiceSearchData.list.find((r) => String(r.id) === String(params.id)) || null)
)

add('DELETE', '/invoice/:id', () => ok(null))

/* ============ 五、报销与预借 ============ */

add('GET', '/loan/:applyNo', () => ok(loanData))

add('POST', '/expense/submit', ({ body }) => {
  const blocking = precheckIssuesData.some((i) => i.level === 'FAIL')
  if (blocking && !body.forceReason) {
    return fail(2004, '预检未通过，需填写强制提交说明')
  }
  return ok({
    reportNo: 'ER202609250001',
    status: 'PENDING',
    precheckResult: 'WARN',
    issues: precheckIssuesData
  })
})

add('GET', '/expense/preview', ({ query }) => {
  // 口径对齐后端 computeChecks：有 FORCE 项就要求强制说明，明细原样带回
  const hasForce = precheckIssuesData.some((i) => i.level === 'FORCE' || i.level === 'FAIL')
  const hasWarn = precheckIssuesData.some((i) => i.level === 'WARN')
  return ok({
    precheckResult: hasForce ? 'FORCE' : (hasWarn ? 'WARN' : 'PASS'),
    checks: precheckIssuesData,
    needForceReason: hasForce
  })
})

add('GET', '/expense/page', ({ query }) => ok(page(expenseListData, query.pageNum, query.pageSize)))

add('GET', '/expense/:reportNo', () => ok(expenseDetailData))

/* ============ 六、财务复核 ============ */

add('GET', '/finance/pending', ({ query }) => ok(page(pendingListData, query.pageNum, query.pageSize)))

add('GET', '/finance/payable', ({ query }) => ok(page(pendingListData, query.pageNum, query.pageSize)))

add('GET', '/finance/:reportNo/check', ({ params }) => ok(fourDimData(decodeURIComponent(params.reportNo))))

add('POST', '/finance/:reportNo/review', ({ body }) => {
  if (body.result !== 'APPROVED' && !body.reason) {
    return fail(1001, '带异常通过或退回时必须填写说明')
  }
  return ok({ reportNo: 'ER202609250001', status: body.result, reviewedAt: '2026-09-26 11:20:03' })
})

add('POST', '/finance/:reportNo/pay', ({ body }) =>
  ok({
    id: 77,
    reportNo: 'ER202609250001',
    payAmount: body.payAmount,
    loanId: body.loanId,
    payAt: '2026-09-26 16:40:05',
    operator: '吴思挺'
  })
)

/* ============ 七、费效分析 ============ */

add('GET', '/analytics/summary', ({ query }) => ok({ ...summaryData, month: query.month || summaryData.month }))

add('GET', '/analytics/monthly', ({ query }) =>
  ok({ ...monthlyData, month: query.month || monthlyData.month, trend: query.withTrend === 'true' ? ratioTrend : undefined })
)

add('GET', '/analytics/drill', ({ query }) => ok({ ...drillData, deptId: Number(query.deptId) || drillData.deptId }))

add('GET', '/analytics/partner', ({ query }) => ok({ ...partnerIoData, month: query.month || partnerIoData.month }))

/* ============ 八、合理性评价 ============ */

add('GET', '/eval/:applyNo', ({ params }) => ok({ ...evalData, applyNo: decodeURIComponent(params.applyNo) }))

add('POST', '/eval/preview', ({ body }) =>
  ok({
    level: body?.reason && body.reason.length > 30 ? 'SUGGEST' : 'NOTICE',
    totalScore: 82.5,
    hints: [
      '关联对象近 12 个月产出率 5.2，处于良好水平',
      '本次预估占该对象产出比例正常',
      '事由字数偏少，补充预期产出后可提升评价'
    ]
  })
)

/* ============ 九、差标与预算配置 ============ */

// 页面上做增删改，mock 里也得跟着变，不然点完按钮一刷新又回到初始数据。
// 这三条副本承担「假数据库」的角色，字段与后端 VO / config_log 表保持一致。
const standardRows = standardListData.map((r) => ({ ...r }))
const deptRows = deptListData.map((r) => ({ ...r }))
const logRows = configLogData.map((r) => ({ ...r }))
let logSeq = 100
let rowSeq = 100

function pad (n) {
  return String(n).padStart(2, '0')
}

function today () {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function now () {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 追加一条留痕。字段对齐 ConfigLogVO：type / action / targetId / targetName / before / after / reason */
function pushLog (type, action, targetId, targetName, before, after, reason) {
  const fmt = (v) => (v === null || v === undefined || v === '' ? null : Number(v).toFixed(2))
  logRows.unshift({
    id: logSeq++,
    type,
    action,
    targetId,
    targetName,
    before: fmt(before),
    after: fmt(after),
    reason: reason || null,
    operator: '管理层',
    operateAt: now()
  })
}

/** 该组当前生效的上限：生效日期不晚于今天的最后一版 */
function currentMax (cityLevel, trafficType) {
  const same = standardRows
    .filter((r) => r.cityLevel === cityLevel && r.trafficType === trafficType && r.effectiveAt <= today())
    .sort((a, b) => (a.effectiveAt < b.effectiveAt ? 1 : -1))
  return same.length ? same[0].maxAmount : null
}

add('GET', '/config/standard', () => ok(standardRows.map((r) => ({ ...r }))))

add('POST', '/config/standard', ({ body }) => {
  if (!body.reason) return fail(1001, '调整差标必须填写原因')
  const exists = standardRows.some((r) => r.cityLevel === body.cityLevel && r.trafficType === body.trafficType)
  if (exists) {
    return fail(1001, `${body.cityLevel}-${body.trafficType} 已经配过标准了，要改金额或日期请点「调整」`)
  }
  const row = {
    id: rowSeq++,
    cityLevel: body.cityLevel,
    trafficType: body.trafficType,
    maxAmount: body.maxAmount,
    effectiveAt: body.effectiveAt || today(),
    updatedBy: '管理层'
  }
  standardRows.push(row)
  pushLog('STANDARD', 'INSERT', `${row.cityLevel}-${row.trafficType}`, `${row.cityLevel}-${row.trafficType}`,
    null, row.maxAmount, body.reason)
  return ok({ ...row })
})

add('PUT', '/config/standard', ({ body }) => {
  if (!body.reason) return fail(1001, '调整差标必须填写原因')
  const effectiveAt = body.effectiveAt || today()
  const dup = standardRows.find((r) =>
    r.cityLevel === body.cityLevel && r.trafficType === body.trafficType && r.effectiveAt === effectiveAt)
  if (dup) {
    return fail(1001, `${body.cityLevel}-${body.trafficType} 在 ${effectiveAt} 已有标准，换一个生效日期即可`)
  }
  const before = currentMax(body.cityLevel, body.trafficType)
  const row = {
    id: rowSeq++,
    cityLevel: body.cityLevel,
    trafficType: body.trafficType,
    maxAmount: body.maxAmount,
    effectiveAt,
    updatedBy: '管理层'
  }
  standardRows.push(row)
  pushLog('STANDARD', 'UPDATE', `${row.cityLevel}-${row.trafficType}`, `${row.cityLevel}-${row.trafficType}`,
    before, row.maxAmount, body.reason)
  return ok({ ...row })
})

add('DELETE', '/config/standard/:id', ({ params, query }) => {
  const idx = standardRows.findIndex((r) => String(r.id) === params.id)
  if (idx < 0) return fail(1001, '差标记录不存在，可能已经被删掉了')
  const row = standardRows[idx]
  const latest = standardRows
    .filter((r) => r.cityLevel === row.cityLevel && r.trafficType === row.trafficType)
    .sort((a, b) => (a.effectiveAt < b.effectiveAt ? 1 : -1))[0]
  if (latest && String(latest.id) !== String(row.id)) {
    return fail(1001, `只能删最新一档（${latest.cityLevel}-${latest.trafficType}，生效日期 ${latest.effectiveAt}）`)
  }
  standardRows.splice(idx, 1)
  pushLog('STANDARD', 'DELETE', `${row.cityLevel}-${row.trafficType}`, `${row.cityLevel}-${row.trafficType}`,
    row.maxAmount, null, query.reason)
  return ok(null)
})

add('GET', '/config/depts', () => ok(deptRows.map((r) => ({ ...r }))))

add('POST', '/config/dept', ({ body }) => {
  if (!body.deptName) return fail(1001, '请填写部门名称')
  if (deptRows.some((d) => d.deptName === body.deptName)) {
    return fail(1001, `已经有一个叫「${body.deptName}」的部门了`)
  }
  const row = {
    deptId: Math.max(...deptRows.map((d) => d.deptId), 0) + 1,
    deptName: body.deptName,
    monthlyBudget: body.monthlyBudget,
    standardLevel: body.standardLevel,
    leaderId: body.leaderId || 4,
    approverName: '管理层',
    memberCount: 0,
    applyCount: 0
  }
  deptRows.push(row)
  pushLog('BUDGET', 'INSERT', `dept:${row.deptId}`, row.deptName, null, row.monthlyBudget, body.reason)
  return ok({ ...row })
})

add('PUT', '/config/budget', ({ body }) => {
  if (!body.reason) return fail(1001, '调整预算必须填写原因')
  const row = deptRows.find((d) => d.deptId === Number(body.deptId))
  if (!row) return fail(1001, '部门不存在')
  const before = row.monthlyBudget
  row.monthlyBudget = body.monthlyBudget
  pushLog('BUDGET', 'UPDATE', `dept:${row.deptId}`, row.deptName, before, row.monthlyBudget, body.reason)
  return ok({ deptId: row.deptId, monthlyBudget: row.monthlyBudget })
})

add('DELETE', '/config/dept/:id', ({ params, query }) => {
  const row = deptRows.find((d) => String(d.deptId) === params.id)
  if (!row) return fail(1001, '部门不存在')
  if (row.memberCount > 0) {
    return fail(1001, `「${row.deptName}」下还有 ${row.memberCount} 名员工，先把人调到其他部门再删`)
  }
  if (row.applyCount > 0) {
    return fail(1001, `「${row.deptName}」名下有 ${row.applyCount} 张出差申请单，这个部门不能删`)
  }
  deptRows.splice(deptRows.indexOf(row), 1)
  pushLog('BUDGET', 'DELETE', `dept:${row.deptId}`, row.deptName, row.monthlyBudget, null, query.reason)
  return ok(null)
})

add('GET', '/config/logs', ({ query }) => {
  let rows = logRows
  if (query.type) rows = rows.filter((r) => r.type === query.type)
  if (query.keyword) {
    rows = rows.filter((r) =>
      String(r.targetId || '').includes(query.keyword) || String(r.reason || '').includes(query.keyword))
  }
  return ok(page(rows, query.pageNum, query.pageSize))
})

/**
 * 手动重算同类花费参考。后端返回的是受影响的行数。
 *
 * <p>mock 里只维护了 costRefData 那一条（深圳 / 验货），所以如实返回 1，
 * 不假装重算了一大片——页面上只是弹一句「已重算」，数字本身没有展示。
 */
add('POST', '/config/cost-ref/refresh', () => ok(1))

/* 供管理层页面使用的部门列表（页面直接从 mock 数据读，非后端接口） */
export { deptListData, ratioTrend }

/* ============ 十、AI 能力 ============ */

/**
 * 这一组只在 VITE_AI_MODE=remote 且 VITE_USE_MOCK=true 时才会打到
 * ——也就是「想看 AI 区块真实渲染出来的样子，但后端没起来」这种场合。
 * VITE_AI_MODE=mock 时 ai.js 在本地就把结果算出来，根本不会发请求。
 *
 * <p>返回值一律带 local: true。这些话是本地编的，不是模型写的，
 * 页面会如实标「本地演示」——不冒充模型输出是全局口径，mock 也没有豁免权。
 */
const MOCK_MODEL = 'mock-local'
const MOCK_PROMPT_VERSION = '2026-09-28-2'

/** 语义判断用的关键词。与 ai.js 本地实现保持一致，mock 与本地两条路给出的结论才不会互相打架 */
const OUTPUT_WORDS = ['签单', '订单', '验收', '交付', '样品', '报价', '合同', '回款', '节点', '审厂', '试产', '对账']

/** 行程天数（含首尾）。end 早于 start 时按 0 处理，不让它算出负数把判断带偏 */
function daysBetween (start, end) {
  if (!start || !end) return 0
  const diff = (new Date(end) - new Date(start)) / 86400000
  return diff >= 0 ? Math.floor(diff) + 1 : 0
}

add('POST', '/apply/advice', ({ body }) => {
  const reason = String(body.reason || '')
  const missing = []
  if (!reason) missing.push('出差事由')
  if (reason && !OUTPUT_WORDS.some((w) => reason.includes(w))) missing.push('预期产出')

  /*
   * 与后端 AiAdviceService 同一口径：<b>填得多不多只影响建议的构成，不影响出不出建议</b>。
   * 早先是「事由不满四个汉字或没填目的地就不给改写」，
   * 结果员工刚打开表单、最需要帮忙的那一刻，AI 只回一句「先把这几项填上」。
   * 现在照样给：素材多就多给值，素材少就多给方向，缺什么由 items 说。
   */
  const its = body.itineraries || []
  const dest = its.map((i) => i.toCity).filter(Boolean)[0] || ''
  const days = its.reduce((s, i) => s + daysBetween(i.startDate, i.endDate), 0)
  if (!dest) missing.push('行程目的地')
  if (days <= 0) missing.push('行程日期')
  if (!body.partnerId) missing.push('关联对象')

  /*
   * 改写只串员工真写过的词：整句里哪怕有一个词不是他写的，一键采用之后那就成了他本人的承诺。
   * 所以这里不看出差类型往下编，也不补「目的地」「该客户」这类占位词。
   */
  const rewrite = reason
    ? [
      body.tripType ? `${body.tripType}：` : '',
      dest ? `赴${dest}` : '',
      body.partnerName ? `${dest ? '，' : ''}与${body.partnerName}` : '',
      days > 0 ? `，行程 ${days} 天` : ''
    ].filter(Boolean).join('') + `。${reason}`
    : ''
  // 前缀一项都没有时这句就等于原文（还会多一个句号），不给——
  // 「。111」这种东西比留空更像是系统出了错
  const rewriteText = rewrite.startsWith('。') ? '' : rewrite

  const items = []
  if (!reason) {
    items.push({ level: 'mid', text: '事由还没填。写清「去做什么 + 预期拿到什么」这趟才讲得清' })
  } else if (reason.length < 15) {
    items.push({ level: 'mid', text: `事由只有 ${reason.length} 个字，审批人看不出这趟的目的` })
  }
  if (missing.includes('预期产出')) {
    items.push({
      level: 'high',
      text: '没写预期产出，补一句「确认 3 月订单节点」这类能验收的结果，合理性评分会上去',
      evidence: { field: 'reason', value: reason.slice(0, 24) || '(空)' }
    })
  }
  if (!dest) {
    items.push({ level: 'mid', text: '还没填目的地，同类花费参考查不出来，金额建议也只能先给个大概' })
  }
  if (days <= 0) {
    items.push({ level: 'mid', text: '日期还没填。住宿要按住几晚摊，补上后金额会换成按你行程算的数' })
  }
  if (!body.partnerId) {
    items.push({ level: 'mid', text: '还没选关联对象，历史产出率因子算不出来' })
  }
  if (days >= 4 && its.length === 1) {
    items.push({
      level: 'mid',
      text: `行程 ${days} 天却只有一段，跨城市的话要按实际到过的城市拆成多段`,
      evidence: { field: 'itinerary', value: String(days) }
    })
  }

  // 一条比对依据都没有时如实标注，别让通用提示看起来像是有数据支撑
  const anchorless = !dest && !body.partnerId
  const sourceNote = anchorless
    ? '库里暂时没有能对标的数据。下面是通用填写提示，把目的地与日期填上，再点一次分析就能给出具体金额'
    : ''

  /*
   * 金额建议拆成交通、住宿两项，与后端 amountFields 同口径。
   * 早先这里给的是 estTotal，而 ApplyCreate.writeField 只认 estTraffic / estHotel 那五个字段，
   * 于是这条建议看着在，点了「采用」什么都不会发生。
   *
   * <p>日期没填时按两天估住宿占比：短途最常见，也比干等着有用。
   * 这一层假设要写进文案，否则员工改完日期也想不起来再点一次分析。
   */
  const round100 = (n) => Math.round(Number(n || 0) / 100) * 100
  const refAvg = 2600
  const assumed = days <= 0
  const effDays = assumed ? 2 : days
  const nights = Math.max(0, effDays - 1)
  const share = assumed
    ? `行程日期还没填，暂按 ${effDays} 天 ${nights} 晚估`
    : `${effDays} 天 ${nights} 晚，住宿按 ${nights}/${effDays} 摊`
  const hotel = round100((refAvg * nights) / effDays)
  const traffic = round100(refAvg - (refAvg * nights) / effDays)

  const fields = []
  if (dest) {
    if (traffic !== Number(body.estTraffic || 0)) {
      fields.push({
        field: 'estTraffic',
        label: '预估交通费',
        currentValue: body.estTraffic == null ? '' : String(body.estTraffic),
        suggestValue: String(traffic),
        reason: `同类均值 ¥${refAvg} 减去住宿，交通约 ¥${traffic}；${share}`,
        source: 'DB_COST_REF',
        sourceText: '同类花费',
        confidence: assumed ? 0.55 : 0.7,
        overwrite: false,
        sort: 1
      })
    }
    if (hotel !== Number(body.estHotel || 0)) {
      fields.push({
        field: 'estHotel',
        label: '预估住宿费',
        currentValue: body.estHotel == null ? '' : String(body.estHotel),
        suggestValue: String(hotel),
        reason: `同类均值 ¥${refAvg}，${share}，住宿约 ¥${hotel}`,
        source: 'DB_COST_REF',
        sourceText: '同类花费',
        confidence: assumed ? 0.55 : 0.7,
        overwrite: false,
        sort: 2
      })
    }
  }
  if (!body.tripType && body.partnerType) {
    const supplier = body.partnerType === 'SUPPLIER'
    fields.push({
      field: 'tripType',
      label: '出差类型',
      currentValue: '',
      suggestValue: supplier ? '供应商审核' : '商务拜访',
      reason: `关联对象是${supplier ? '供应商' : '客户'}，同对象的单历史上多按这个类型走`,
      source: 'DB_PARTNER',
      sourceText: '往来对象',
      confidence: 0.5,
      overwrite: false,
      sort: 3
    })
  }
  if (rewriteText && rewriteText !== reason) {
    fields.push({
      field: 'reason',
      label: '出差事由',
      currentValue: reason,
      suggestValue: rewriteText,
      reason: '照着改两个字就能用，句子里没有你没写过的东西',
      source: 'LOCAL_TEMPLATE',
      sourceText: '本地模板：按已填内容拼的模板句，不含编造成分',
      confidence: 0.5,
      overwrite: true,
      sort: 4
    })
  }

  return ok({
    missing,
    items,
    rewrite: rewriteText,
    fields,
    anchorless,
    sourceNote,
    local: true,
    model: MOCK_MODEL,
    promptVersion: MOCK_PROMPT_VERSION
  })
})

add('POST', '/eval/semantic', ({ body }) => {
  const reason = String(body.reason || '')
  const hasOutput = OUTPUT_WORDS.some((w) => reason.includes(w))
  const delta = hasOutput ? 0 : -3
  return ok({
    grade: delta >= 5 ? 'A' : delta === 0 ? 'B' : delta >= -3 ? 'C' : delta >= -7 ? 'D' : 'E',
    delta,
    reasons: hasOutput
      ? [{ level: 'low', text: '事由交代了预期产出，语义上无需下调', evidence: { field: 'reason', value: reason.slice(0, 24) } }]
      : [{ level: 'mid', text: '事由没写预期产出，判断不了这趟值不值', evidence: { field: 'reason', value: reason.slice(0, 24) || '(空)' } }],
    missing: hasOutput ? [] : ['预期产出'],
    confidence: 0.8,
    local: true,
    enhanced: false,
    note: '',
    model: MOCK_MODEL,
    promptVersion: MOCK_PROMPT_VERSION
  })
})

add('POST', '/approval/:applyNo/risk', ({ params }) =>
  ok({
    applyNo: decodeURIComponent(params.applyNo),
    points: [{
      level: 'mid',
      text: '预估比同类均值高 8%（同类参考均值 ¥2600）',
      evidence: { field: 'costRef.avgAmount', value: '2600' }
    }],
    summary: '整体在合理区间，金额略高于同类均值',
    local: true
  })
)

add('GET', '/invoice/match', () =>
  ok({
    itineraryId: 101,
    confidence: 0.92,
    reason: '开票日期落在第 1 段行程区间（2026-09-20 ~ 2026-09-22）'
  })
)

add('GET', '/finance/:reportNo/suspicion', ({ params }) =>
  ok({
    reportNo: decodeURIComponent(params.reportNo),
    invoiceCnt: 3,
    points: [{
      level: 'mid',
      text: '存在 2 张连号发票，确认不是重复报销',
      evidence: { field: 'invoice.invoiceNo', value: '24412000000012345678' }
    }],
    summary: '未见硬性违规，有 1 处值得复核',
    local: true
  })
)

/**
 * 月度洞察。GET 是「有旧的就用旧的」，POST 是「我就要一份新的」，
 * 后端两者的差别只在于会不会再调一次模型，mock 里返回同一份内容即可。
 *
 * <p>drivers 用演示数据的口径（9 月 0.3849、8 月 0.2819、环比 +0.1030），
 * 月报里那几张卡读的是 summaryData，数字对得上才不会看着自相矛盾。
 */
/**
 * 各月的样本量与置信度。刻意让三个月落在三档上（充足 / 尚可 / 偏低），
 * 这样不连后端也能看到标签的三种样子。
 */
const MOCK_CONFIDENCE = {
  '2026-08': { sampleCnt: 9, confidence: 0.54, confidenceLevel: 'FAIR' },
  '2026-09': { sampleCnt: 12, confidence: 0.74, confidenceLevel: 'AMPLE' },
  '2026-10': { sampleCnt: 3, confidence: 0.18, confidenceLevel: 'THIN' }
}

function mockInsight (month) {
  const conf = MOCK_CONFIDENCE[month] || MOCK_CONFIDENCE['2026-09']
  return ok({
    month: month || '2026-09',
    prevMonth: '2026-08',
    highlights: [{
      level: 'high',
      text: '费效比 0.3849 环比上升 0.1030，已越过 0.30 的绿灯区间',
      evidence: { field: 'ratio', value: '0.3849' },
      source: 'MODEL'
    }, {
      level: 'mid',
      text: '技术部投入 ¥7200、产出 ¥6000，是本月唯一倒挂的部门',
      evidence: { field: 'partnerIo.input', value: '7200' },
      source: 'MODEL'
    }],
    // 动作代码只能取六个固定值之一，target 必须是入参里真实存在的部门名
    actions: [{
      code: 'REVIEW_COST',
      codeText: '复核费用明细',
      target: '技术部',
      detail: '费效比 1.20，先看这个月的费用明细',
      adopted: false,
      basis: 'dept.技术部.ratio'
    }, {
      code: 'CHECK_OUTPUT',
      codeText: '补录产出',
      target: '',
      detail: '产出回填率偏低，先补齐再判断',
      adopted: false,
      basis: 'ratio'
    }],
    sources: [
      'payment_record 2026-09 已付款 12 单，投入 ¥38400',
      'biz_result 2026-09 产出登记，产出 ¥99800，其中已回填 9 单',
      'trip_apply 2026-09 申请 15 单，事前拦截 ¥2600',
      'eval_result 2026-09 评价「关注」 2 单'
    ],
    summary: '本月费效比走高主要由技术部的投入产出倒挂推动，其余部门保持在合理区间。',
    drivers: [{
      dimension: 'DEPT',
      dimensionText: '部门',
      name: '技术部',
      contribution: 0.1030,
      pay: 7200,
      output: 6000,
      payDelta: 1200,
      outputDelta: -800,
      text: '技术部投入增加 ¥1200、产出减少 ¥800，推高整体费效比约 0.1030'
    }],
    ratio: 0.3849,
    prevRatio: 0.2819,
    delta: 0.1030,
    sampleNote: null,
    sampleCnt: conf.sampleCnt,
    confidence: conf.confidence,
    confidenceLevel: conf.confidenceLevel,
    local: true,
    model: MOCK_MODEL,
    promptVersion: MOCK_PROMPT_VERSION,
    generatedAt: now(),
    generatedBy: 'MANUAL',
    stale: false
  })
}

add('GET', '/analytics/insight', ({ query }) => mockInsight(query.month))

add('POST', '/analytics/insight/refresh', ({ query }) => mockInsight(query.month))

// 勾掉 / 取消一条动作。mock 只回成功，不真的记住——本地演示没有报告可写
add('POST', '/analytics/insight/action', () => ok(true))

add('POST', '/analytics/drill/explain', ({ query }) => ok({
  deptId: Number(query.deptId) || 1,
  deptName: '技术部',
  month: query.month || '2026-09',
  prevMonth: '2026-08',
  highlights: [{
    level: 'high',
    text: '费效比 1.20 较上月 0.85 变差，投入 ¥7200 只换回 ¥6000 产出',
    evidence: { field: 'ratio', value: '0.85 → 1.20' },
    source: 'MODEL'
  }],
  summary: '这个月的投入产出倒挂集中在两张展会单上，其余出差保持在合理区间。',
  sampleCnt: 4,
  ratio: 1.2,
  prevRatio: 0.85,
  delta: 0.35,
  local: true,
  model: MOCK_MODEL,
  promptVersion: MOCK_PROMPT_VERSION,
  sources: [
    'payment_record 2026-09 该部门已付款 4 单，投入 ¥7200',
    'biz_result 2026-09 该部门产出登记，产出 ¥6000，其中已回填 3 单',
    'trip_apply 2026-09 该部门申请 5 单'
  ]
}))

/* ============ 七、系统管理员：基础档案与只读浏览 ============ */

/**
 * 往来对象。前四个取自对象投入产出页那份数据，引用数沿用它的 tripCnt，
 * 后两个是没接过单的新对象——删按钮得有一行能点才有得测。
 */
const partnerRows = partnerIoData.rows.map((r) => ({
  id: r.partnerId,
  partnerName: r.partnerName,
  partnerType: r.partnerType,
  totalInvest: r.totalInvest,
  totalOutput: r.totalOutput,
  applyCount: r.tripCnt
})).concat([
  { id: 7, partnerName: '杭州某某供应链有限公司', partnerType: 'SUPPLIER', totalInvest: 0, totalOutput: 0, applyCount: 0 },
  { id: 8, partnerName: '厦门某某检测中心', partnerType: 'SUPPLIER', totalInvest: 0, totalOutput: 0, applyCount: 0 }
])

/** 用户账号。id 与登录处理里的 index + 1 口径一致，改一处要改两处 */
const userRows = MOCK_ACCOUNTS.map((acc, i) => ({
  id: i + 1,
  username: acc.username,
  realName: acc.realName,
  role: acc.role,
  deptId: acc.deptName === '财务部' ? 4 : 1,
  deptName: acc.deptName,
  bankAccountMasked: acc.role === 'EMPLOYEE' ? '6222 **** 1234' : null,
  refCount: acc.role === 'EMPLOYEE' ? 3 : 0,
  self: false,
  createdAt: '2026-09-01 09:00:00'
})).concat([
  { id: 6, username: 'chenxiao', realName: '陈晓', role: 'EMPLOYEE', deptId: 1, deptName: '销售部', bankAccountMasked: '6222 **** 2345', refCount: 1, self: false, createdAt: '2026-09-02 10:20:00' },
  { id: 7, username: 'zhaopeng', realName: '赵鹏', role: 'EMPLOYEE', deptId: 3, deptName: '技术部', bankAccountMasked: null, refCount: 0, self: false, createdAt: '2026-09-03 14:05:00' }
])

/** 当前登录者，/system/me 也是这么取的，两边要对得上 */
function currentUserId () {
  const raw = localStorage.getItem('TE_USER')
  if (!raw) return 1
  try {
    return JSON.parse(raw).userId || 1
  } catch (e) {
    return 1
  }
}

add('GET', '/admin/partners', ({ query }) => {
  const kw = (query.keyword || '').trim()
  const list = kw ? partnerRows.filter((r) => r.partnerName.includes(kw)) : partnerRows
  return ok(page(list, query.pageNum, query.pageSize))
})

/*
 * 填单页的往来对象下拉。数据源就是 partnerRows，跟系统管理员那份档案同一份，
 * 那边改名这边立刻跟着变——下拉写死在前端时改了名对不上，选出来的 id 落到审批页是另一个对象。
 * 只回 id / 名称 / 类型：投入产出是 AnalyticsService 直接读的统计列，选对象时用不上。
 */
add('GET', '/partner/list', () => ok(partnerRows
  .slice()
  .sort((a, b) => String(a.partnerName).localeCompare(String(b.partnerName), 'zh-CN'))
  .map((r) => ({ id: r.id, partnerName: r.partnerName, partnerType: r.partnerType }))))

add('POST', '/admin/partner', ({ body }) => {
  if (!body.partnerName || !body.partnerName.trim()) return fail(1001, '请填写对象名称')
  const name = body.partnerName.trim()
  if (partnerRows.some((r) => r.partnerName === name)) {
    return fail(1001, `已经有一个叫「${name}」的往来对象了`)
  }
  const row = {
    id: Math.max(...partnerRows.map((r) => r.id), 0) + 1,
    partnerName: name,
    partnerType: body.partnerType || 'CUSTOMER',
    totalInvest: 0,
    totalOutput: 0,
    applyCount: 0
  }
  partnerRows.push(row)
  return ok('往来对象已新增', row)
})

add('PUT', '/admin/partner/:id', ({ params, body }) => {
  const row = partnerRows.find((r) => String(r.id) === String(params.id))
  if (!row) return fail(1001, '往来对象不存在，可能已经被删掉了')
  const name = (body.partnerName || '').trim()
  if (name && partnerRows.some((r) => r.partnerName === name && r.id !== row.id)) {
    return fail(1001, `已经有一个叫「${name}」的往来对象了`)
  }
  if (name) row.partnerName = name
  if (body.partnerType) row.partnerType = body.partnerType
  return ok('往来对象已更新', row)
})

add('DELETE', '/admin/partner/:id', ({ params }) => {
  const idx = partnerRows.findIndex((r) => String(r.id) === String(params.id))
  if (idx < 0) return fail(1001, '往来对象不存在，可能已经被删掉了')
  const row = partnerRows[idx]
  if (row.applyCount > 0) {
    return fail(1001, `「${row.partnerName}」已被 ${row.applyCount} 张单据引用，删了这些单据的对象就找不回来了，不能删`)
  }
  partnerRows.splice(idx, 1)
  return ok('往来对象已删除，留痕里仍能看到这条记录', null)
})

add('GET', '/admin/users', ({ query }) => {
  const me = currentUserId()
  let list = userRows.map((r) => ({ ...r, self: r.id === me }))
  if (query.role) list = list.filter((r) => r.role === query.role)
  if (query.deptId) list = list.filter((r) => String(r.deptId) === String(query.deptId))
  if (query.keyword) {
    const kw = String(query.keyword)
    list = list.filter((r) => r.realName.includes(kw) || r.username.includes(kw))
  }
  return ok(page(list, query.pageNum, query.pageSize))
})

add('POST', '/admin/user', ({ body }) => {
  const username = (body.username || '').trim()
  if (!username) return fail(1001, '请填写登录名')
  if (userRows.some((r) => r.username === username)) {
    return fail(1001, `登录名「${username}」已经被占用了，换一个`)
  }
  if (!body.password || body.password.length < 6) return fail(1001, '新增账号必须设置初始密码，至少 6 位')
  const row = {
    id: Math.max(...userRows.map((r) => r.id), 0) + 1,
    username,
    realName: (body.realName || '').trim(),
    role: body.role || 'EMPLOYEE',
    deptId: body.deptId || 1,
    deptName: (deptListData.find((d) => d.deptId === (body.deptId || 1)) || {}).deptName || '销售部',
    bankAccountMasked: body.bankAccount ? '6222 **** 8888' : null,
    refCount: 0,
    self: false,
    createdAt: new Date().toISOString().slice(0, 19).replace('T', ' ')
  }
  userRows.push(row)
  return ok('用户已新增', row)
})

add('PUT', '/admin/user/:id', ({ params, body }) => {
  const row = userRows.find((r) => String(r.id) === String(params.id))
  if (!row) return fail(1001, '用户不存在，可能已经被删掉了')
  const username = (body.username || '').trim()
  if (username && userRows.some((r) => r.username === username && r.id !== row.id)) {
    return fail(1001, `登录名「${username}」已经被占用了`)
  }
  if (body.role && body.role !== row.role && row.id === currentUserId()) {
    return fail(1001, '不能在这里改自己的角色，改完就没人能进这个页面了')
  }
  if (username) row.username = username
  if (body.realName) row.realName = body.realName.trim()
  if (body.role) row.role = body.role
  if (body.deptId) {
    row.deptId = body.deptId
    row.deptName = (deptListData.find((d) => d.deptId === body.deptId) || {}).deptName || row.deptName
  }
  return ok('用户已更新', { ...row })
})

add('DELETE', '/admin/user/:id', ({ params }) => {
  const row = userRows.find((r) => String(r.id) === String(params.id))
  if (!row) return fail(1001, '用户不存在，可能已经被删掉了')
  if (row.id === currentUserId()) return fail(1001, '不能删掉自己')
  if (row.refCount > 0) {
    return fail(1001, `「${row.realName}」名下有 ${row.refCount} 条单据或审批留痕，改部门或改姓名都行，删除不行`)
  }
  const idx = userRows.indexOf(row)
  userRows.splice(idx, 1)
  return ok('用户已删除，留痕里仍能看到这条记录', null)
})

add('PUT', '/admin/dept/:id', ({ params, body }) => {
  const row = deptRows.find((d) => String(d.deptId) === String(params.id))
  if (!row) return fail(1001, '部门不存在')
  const name = (body.deptName || '').trim()
  if (name && deptRows.some((d) => d.deptName === name && d.deptId !== row.deptId)) {
    return fail(1001, `已经有一个叫「${name}」的部门了`)
  }
  if (body.leaderId === null || body.leaderId === undefined) {
    return fail(1001, '请选择部门负责人')
  }
  if (!userRows.some((u) => String(u.id) === String(body.leaderId))) {
    return fail(1001, '指定的负责人不存在')
  }
  if (name) row.deptName = name
  const leader = userRows.find((u) => String(u.id) === String(body.leaderId))
  row.leaderId = leader.id
  row.approverName = leader.realName
  return ok('部门档案已更新', { ...row })
})

/**
 * 可浏览的表清单，与后端 BrowseService 的注册表一致。
 * 只有三张表给得出真数据，其余返回空行——宁可让人看到「这张表当前没有数据」，
 * 也不要编一堆假单据糊在数据浏览页上（那正是这个页面要防的事）。
 */
const BROWSE_TABLES = [
  { name: 'partner', label: '往来对象', group: '档案', pk: 'id', columns: ['id', 'partner_name', 'partner_type', 'total_invest', 'total_output'], rowCount: partnerRows.length },
  { name: 'dept', label: '部门', group: '档案', pk: 'id', columns: ['id', 'dept_name', 'monthly_budget', 'standard_level', 'leader_id'], rowCount: deptRows.length },
  { name: 'sys_user', label: '用户账号', group: '档案', pk: 'id', columns: ['id', 'username', 'real_name', 'role', 'dept_id', 'created_at'], rowCount: userRows.length },
  { name: 'trip_standard', label: '差标版本', group: '标准', pk: 'id', columns: ['id', 'city_level', 'traffic_type', 'max_amount', 'effective_at'], rowCount: 0 },
  { name: 'trip_apply', label: '出差申请单', group: '单据', pk: 'id', columns: ['id', 'apply_no', 'user_id', 'dept_id', 'status', 'est_total'], rowCount: 0 },
  { name: 'config_log', label: '配置留痕', group: '留痕', pk: 'id', columns: ['id', 'item_type', 'item_key', 'action', 'operate_at'], rowCount: 0 }
]

const BROWSE_NOTES = {
  sys_user: '密码不以任何形式返回；银行账号按财务侧口径只显示前 3 后 4',
  dept: '月度预算与差标等级的调整归管理层，这里不让改',
  partner: '累计投入与产出是回填时增量累加的结果，不是随时重算的数，别照着它校对单据',
  trip_apply: 'status 只能由业务流转写入（草稿提交、审批、付款结案、手工关闭），不提供修改',
  config_log: '应用层只追加，不修改不删除'
}

function browseRows (name) {
  if (name === 'partner') {
    return partnerRows.map((r) => ({
      id: r.id, partner_name: r.partnerName, partner_type: r.partnerType,
      total_invest: r.totalInvest, total_output: r.totalOutput
    }))
  }
  if (name === 'dept') {
    return deptRows.map((r) => ({
      id: r.deptId, dept_name: r.deptName, monthly_budget: r.monthlyBudget,
      standard_level: r.standardLevel, leader_id: r.leaderId
    }))
  }
  if (name === 'sys_user') {
    return userRows.map((r) => ({
      id: r.id, username: r.username, real_name: r.realName,
      role: r.role, dept_id: r.deptId, created_at: r.createdAt
    }))
  }
  return []
}

add('GET', '/admin/tables', () => {
  return ok(BROWSE_TABLES.map((t) => ({ ...t, note: BROWSE_NOTES[t.name] || null })))
})

add('GET', '/admin/tables/:name', ({ params, query }) => {
  const name = decodeURIComponent(params.name)
  const meta = BROWSE_TABLES.find((t) => t.name === name)
  if (!meta) return fail(1001, `没有叫「${name}」的表，可以浏览的范围见 /admin/tables`)
  const all = browseRows(name)
  const id = query.id ? String(query.id) : null
  const list = id ? all.filter((r) => String(r.id) === id) : all
  const p = page(list, query.pageNum, query.pageSize || 20)
  return ok({
    table: { ...meta, note: BROWSE_NOTES[name] || null },
    columns: meta.columns,
    rows: p.list,
    total: p.total,
    pageNum: p.pageNum,
    pageSize: p.pageSize
  })
})

/* ============ 理由字段草稿 ============
 *
 * 后端为它单开了一张 field_draft，键是（场景 + 单号 + 字段名 + 本人）。
 * mock 里放内存即可：草稿是暂存，页面关掉就没了也不影响任何业务数据，
 * 真后端那份是落在库里的，换台机器打开还在。
 *
 * 键里带上 userId，是为了让 mock 也遵守「只认本人草稿」这条——
 * 换成别人的账号进来读不到自己写的东西，跟真后端表现一致。
 */
const fieldDrafts = new Map()

/** 当前登录人。取不到就当 0，本地演示只有一个人来回切账号 */
function draftOwner () {
  return JSON.parse(localStorage.getItem('TE_USER') || 'null')?.userId || 0
}

function draftKey (bizType, bizNo, fieldName) {
  return `${bizType}|${bizNo}|${fieldName}|${draftOwner()}`
}

function draftTime () {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 缺任一段就不存也不读：没挂上单据的草稿键是没有意义的 */
function draftArgs (a, b) {
  const src = a && a.bizType ? a : b
  return src && src.bizType && src.bizNo && src.fieldName ? src : null
}

add('POST', '/draft/field/save', ({ body }) => {
  const arg = draftArgs(body)
  if (!arg) return fail(1001, '草稿缺少场景、单号或字段名')
  const key = draftKey(arg.bizType, arg.bizNo, arg.fieldName)
  const content = (arg.content || '').trim()
  if (!content) {
    fieldDrafts.delete(key)
    return ok({ bizType: arg.bizType, bizNo: arg.bizNo, fieldName: arg.fieldName, content: null, updatedAt: null })
  }
  const row = { bizType: arg.bizType, bizNo: arg.bizNo, fieldName: arg.fieldName, content, updatedAt: draftTime() }
  fieldDrafts.set(key, row)
  return ok({ ...row })
})

add('GET', '/draft/field/get', ({ query }) => {
  const arg = draftArgs(query)
  if (!arg) return fail(1001, '草稿缺少场景、单号或字段名')
  const row = fieldDrafts.get(draftKey(arg.bizType, arg.bizNo, arg.fieldName))
  return ok(row
    ? { ...row }
    : { bizType: arg.bizType, bizNo: arg.bizNo, fieldName: arg.fieldName, content: null, updatedAt: null })
})

add('DELETE', '/draft/field', ({ query }) => {
  const arg = draftArgs(query)
  if (!arg) return fail(1001, '草稿缺少场景、单号或字段名')
  fieldDrafts.delete(draftKey(arg.bizType, arg.bizNo, arg.fieldName))
  return ok(null, '草稿已清除')
})

/* ============ 适配器本体 ============ */

function normalizePath (config) {
  let url = config.url || ''
  const base = config.baseURL || ''
  if (/^https?:/i.test(url)) url = url.replace(/^https?:\/\/[^/]+/, '')
  if (base && !url.startsWith(base)) {
    url = base.replace(/\/$/, '') + '/' + url.replace(/^\//, '')
  }
  url = url.replace(/^\/api/, '')
  const qIndex = url.indexOf('?')
  return qIndex >= 0 ? url.slice(0, qIndex) : url
}

function parseBody (config) {
  if (!config.data) return {}
  if (typeof config.data === 'string') {
    try {
      return JSON.parse(config.data)
    } catch {
      return {}
    }
  }
  if (config.data instanceof FormData) {
    const obj = {}
    config.data.forEach((v, k) => { obj[k] = v })
    return obj
  }
  return config.data
}

export async function mockAdapter (config) {
  const path = normalizePath(config)
  const method = (config.method || 'get').toUpperCase()
  const body = parseBody(config)

  await new Promise((r) => setTimeout(r, 180 + Math.random() * 220))

  for (const route of routes) {
    const matched = route.re.exec(path)
    if (!matched) continue
    if (route.method !== method) continue

    const params = {}
    route.keys.forEach((k, i) => { params[k] = decodeURIComponent(matched[i + 1]) })

    const result = route.handler({ params, query: config.params || {}, body })

    return {
      data: result,
      status: 200,
      statusText: 'OK',
      headers: { 'content-type': 'application/json' },
      config,
      request: { mock: true }
    }
  }

  return {
    data: fail(5000, `Mock 未覆盖该接口：${method} ${path}`),
    status: 200,
    statusText: 'OK',
    headers: { 'content-type': 'application/json' },
    config,
    request: { mock: true }
  }
}

export default mockAdapter
