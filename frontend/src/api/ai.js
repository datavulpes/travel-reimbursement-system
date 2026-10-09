/**
 * AI 能力层。
 *
 * <p>页面只认这里的函数签名与返回结构，不关心背后是模型还是本地规则。
 * 三种模式由 .env 的 VITE_AI_MODE 决定：
 * - mock（默认）本地启发式，页面上如实标注「本地演示」，不冒充模型输出
 * - remote    走后端真实 AI 接口（/eval/semantic、/apply/advice、/approval/{no}/risk、
 *             /finance/{no}/suspicion、/invoice/match、月报 conclusion）
 * - off       整个 AI 区块不渲染
 *
 * <p>remote 模式下的两条铁律：
 * <ol>
 *   <li><b>失败静默降级</b>：AI 不是主流程，一次调用挂掉就返回 null，页面把 AI 区块收起来，
 *       绝不弹错误条、绝不让提交之类的动作受影响。为此在 request.js 里加了 silence 开关。</li>
 *   <li><b>形状归一化</b>：后端 VO 里的要点字段叫 text，而页面语义卡历史上用的是 point。
 *       这里统一转一遍，契约以「页面看到的」为准，后端换字段名不用改页面。</li>
 * </ol>
 *
 * <p>契约按设计：AI 不直接打分，只输出语义调节 Δ（−10 ~ +5）与带证据的理由，
 * 最终分与档位仍由规则决定（见 aiSemanticEval 的返回值）。
 *
 * <p>全程不联网：这些建议的依据只能来自本库的差标与历史单据，模型没有「查一下外部信息」的通道。
 * 想引入外部数据（比如城市物价）的正确做法是让它入库并记来源，而不是让模型去搜。
 */
import request from './request'

const MODE = import.meta.env.VITE_AI_MODE || 'mock'

/** 关掉之后所有 AI 区块都不渲染 */
export const aiEnabled = MODE !== 'off'
/** true 表示结果是本地模拟的，页面要如实标注，别让人以为是模型输出 */
export const aiLocal = MODE === 'mock'
/** true 时才打后端接口 */
const REMOTE = MODE === 'remote'
/** true 表示当前会真的打后端 AI 接口。必须排在 REMOTE 之后声明：
 *  const 有暂时性死区，把 export 提到它前面会在模块求值阶段直接抛
 *  ReferenceError，整个 app 白屏——而 vite build 是抓不到的。 */
export const aiRemote = REMOTE

/** 模拟一次网络往返，让页面有 loading 态；remote 模式下由真请求承担 */
const delay = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 打一次后端 AI 接口。
 *
 * <p>任何失败都吞掉返回 null：网络断了、模型超时、后端降级 Δ=0、权限不够，
 * 页面上的表现都是「AI 区块不出现」。AI 是加分项，不是依赖项。
 */
async function call (fn) {
  try {
    return await fn()
  } catch (e) {
    console.warn('[ai] 调用失败，本次不展示 AI 结论：', e?.msg || e?.message || e)
    return null
  }
}

/** AI 请求统一走 silence：业务失败不弹红条 */
const SILENCE = { silence: true }

/**
 * 行程段取后端认识的字段。
 *
 * <p>ApplySubmitRequest.ItineraryItem 只有这七个字段，而页面上的行对象还挂着
 * id、key 之类的东西；后端默认开着 FAIL_ON_UNKNOWN_PROPERTIES，多传一个就 400。
 * 这里按白名单挑一遍，两边的对象形状互不绑架。
 */
function pickSegments (list) {
  return (list || []).map((i) => ({
    seq: i.seq,
    startDate: i.startDate,
    endDate: i.endDate,
    fromCity: i.fromCity,
    toCity: i.toCity,
    trafficType: i.trafficType,
    cityLevel: i.cityLevel
  })).filter((i) => i.startDate || i.endDate || i.toCity)
}

/**
 * 后端 AiPointVO 的 arguments 是 { level, text, evidence }，
 * 页面的语义卡读的是 point 字段。统一带上两个名字，改哪边都不会突然空掉。
 */
function normalizePoints (list) {
  return (list || []).map((p) => ({ ...p, point: p.point || p.text }))
}

// ---------- 语义判断用的小工具 ----------

const CITY_HINTS = [
  '北京', '上海', '广州', '深圳', '东莞', '长沙', '武汉', '杭州', '成都', '苏州',
  '南京', '重庆', '西安', '青岛', '天津', '佛山', '珠海', '厦门', '宁波', '无锡'
]

/** 从一段文字里认出城市。对象名、销售方、目的地都靠它对齐 */
function cityOf (text) {
  const s = String(text || '')
  return CITY_HINTS.find((c) => s.includes(c)) || ''
}

/** 行程天数（含首尾）。end 早于 start 时按 0 处理，不让它算出负数把判断带偏 */
function daysBetween (start, end) {
  if (!start || !end) return 0
  const a = new Date(String(start).replace(/-/g, '/'))
  const b = new Date(String(end).replace(/-/g, '/'))
  if (isNaN(a.getTime()) || isNaN(b.getTime())) return 0
  const d = Math.round((b - a) / 86400000) + 1
  return d > 0 ? d : 0
}

/** 事由里有没有交代「这趟要拿到什么」 */
const OUTPUT_WORDS = ['签单', '订单', '验收', '交付', '样品', '报价', '合同', '回款', '节点', '审厂', '试产', '对账']
/** 事由里有没有交代「去干什么」 */
const PURPOSE_WORDS = ['拜访', '验货', '审核', '展会', '交付', '培训', '谈判', '对接', '巡店', '驻场']

// ---------- 1. 填写页：事由补全与缺项提示（A-09） ----------

/**
 * 读一遍事由与已填内容，指出缺什么，并列出可以一键填进表单的项。
 *
 * <p>fields 里每一条都带 source（依据从哪来）和 overwrite（会不会盖掉已填的值），
 * 页面按 overwrite 决定默认勾不勾：AI 悄悄改掉人已经填好的东西最招人烦。
 *
 * <p>金额类建议本地实现也照着后端那套口径算（同类均值 → 按住宿天数摊 → 取整到百位），
 * 两种模式切出来的数要能对得上，否则联调时会以为是后端算错了。
 *
 * @returns {Promise<{missing:string[], items:{level:string,text:string}[], rewrite:string,
 *                    fields:Array, local:boolean}|null>}
 */
export async function aiDraftAdvice (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    const data = await call(() => request.post('/apply/advice', {
      // applyNo 带上是为了让后端把这份建议挂到具体某一单上；新建单没号，
      // 后端会落到 NEW + 本人 id 这个槽位，与提交后的采纳回执用同一个号
      applyNo: ctx.applyNo || '',
      reason: ctx.reason || '',
      tripType: ctx.tripType || '',
      partnerId: ctx.partnerId ?? null,
      itineraries: pickSegments(ctx.itineraries),
      estTraffic: ctx.estTraffic ?? null,
      estHotel: ctx.estHotel ?? null,
      exceedReason: ctx.exceedReason || ''
    }, SILENCE))
    if (!data) return null
    // 后端已经带了 items/missing/rewrite/fields，形状与本地实现一致，页面不用分辨来源
    return {
      missing: data.missing || [],
      items: normalizePoints(data.items),
      rewrite: data.rewrite || '',
      fields: data.fields || [],
      anchorless: !!data.anchorless,
      sourceNote: data.sourceNote || '',
      local: !!data.local
    }
  }

  await delay(320)

  const reason = String(ctx.reason || '').trim()
  const missing = []
  const items = []

  if (!reason) {
    missing.push('出差事由')
    items.push({ level: 'mid', text: '事由还没填。写清「去做什么 + 预期拿到什么」这趟才讲得清' })
  } else {
    if (reason.length < 15) {
      missing.push('事由过短')
      items.push({ level: 'mid', text: `事由只有 ${reason.length} 个字，审批人看不出这趟的目的` })
    }
    if (!PURPOSE_WORDS.some((w) => reason.includes(w))) {
      missing.push('出行目的')
      items.push({ level: 'mid', text: '没写明去做什么（拜访 / 验货 / 审核 / 交付），建议补上' })
    }
    if (!OUTPUT_WORDS.some((w) => reason.includes(w))) {
      missing.push('预期产出')
      items.push({ level: 'high', text: '没写预期产出。写清「确认 3 月订单节点」这类结果，合理性评分会明显上去' })
    }
  }

  /*
   * 目的地直接取行程里填的城市，不走 CITY_HINTS：那张词表是语义评价判断指针词条用的，
   * 只有二十个城市，拿它判「员工有没有填目的地」会把合肥这类城市误判成没填。
   */
  const dest = (ctx.itineraries || []).map((i) => i.toCity).filter(Boolean)[0] || ''
  const days = (ctx.itineraries || []).reduce((s, i) => s + daysBetween(i.startDate, i.endDate), 0)
  if (!dest) {
    missing.push('行程目的地')
    items.push({ level: 'mid', text: '还没填目的地，同类花费参考查不出来，金额建议也只能先给个大概' })
  }
  if (days <= 0) {
    missing.push('行程日期')
    items.push({ level: 'mid', text: '日期还没填。住宿要按住几晚摊，补上后金额会换成按你行程算的数' })
  }
  if (!ctx.partnerId) {
    missing.push('关联对象')
    items.push({ level: 'mid', text: '还没选关联对象，历史产出率因子算不出来' })
  }

  /*
   * 本地模式下「库里有没有数据」这件事只能看页面上已经查回来的东西：
   * 同类参考、对象、目的地这三样都没有，就说明这一趟还没有任何可比对的参照。
   * 这时候照样给建议，但要如实说出来，别让它看起来像是有数据支撑的结论。
   */
  const anchorless = !ctx.costRef && !ctx.partnerId && !dest

  // 事由从模板句走：本地只串员工真填过的词，一个字都不往上补
  const rewrite = reason ? localRewrite(ctx, reason, dest, days) : ''
  return {
    missing,
    items,
    rewrite,
    fields: localFields(ctx, rewrite, days),
    anchorless,
    sourceNote: anchorless
      ? '库里暂时没有能对标的数据。下面是通用填写提示，把目的地与日期填上，再点一次分析就能给出具体金额'
      : '',
    local: true
  }
}

/**
 * 本地模板句。<b>只串员工已经填出来的东西，一个词都不往上补。</b>
 *
 * <p>早先这里会看出差类型往下编：填「验货」就补「现场验货并确认交付节点」，
 * 整句里没一个词来自表单，员工一键采用之后那些没写过的承诺就成了他本人写的。
 * 同理「该客户」「目的地」两个占位词也早撤了：占位词看着无害，
 * 可拼出来的句子主谓齐全却没有任何信息，比不给改写更让人放松警惕。
 *
 * <p>不设素材门槛：事由短或者没写目的地照样给，只不过拼出来的句子会短一些。
 * 早先是「不满四个汉字或没目的地就不给」，结果员工刚打开表单最需要帮忙时 AI 只会回一句「先把这几项填上」。
 */
function localRewrite (ctx, reason, dest, days) {
  const parts = []
  if (ctx.tripType) parts.push(`${ctx.tripType}：`)
  if (dest) parts.push(`赴${dest}`)
  if (ctx.partnerName) parts.push(`${dest ? '，' : ''}与${ctx.partnerName}`)
  if (days > 0) parts.push(`，行程 ${days} 天`)
  // 前缀一项都没有时拼出来是「。原文」，比不给更像系统出错
  return parts.length ? parts.join('') + `。${reason}` : ''
}

/*
 * 这里原来有一张 SRC_* → 中文名的映射表（差标 / 同类花费 / 历史单据……），
 * 给每条建议印一行「依据 · 同类花费｜近 12 个月 12 单均值 ¥2400」。
 * 页面不再显示出处之后它就没人用了，删掉：留着会跟后端 AiFieldAdviceVO 的常量悄悄走散，
 * 而没有任何一处会因此报错。
 * source / sourceText 字段本身照旧由后端返回，别动数据契约——那是防编造的锚。
 */

/** 取整到百位，与后端 round100 同一口径 */
const round100 = (n) => Math.round(Number(n || 0) / 100) * 100

/**
 * 本地模式下的可填充项。只做后端那套里不依赖库的那几件：
 * 出差类型按对象类型推、金额按已查回来的同类参考摊、事由按模板句。
 * 依赖库内明细的（本人历史最常用类型、差标逐段上限）本地拿不到，就不给。
 *
 * <p>日期没填时按两天估住宿占比，与后端同一口径（后端会先试本人平均天数，本地没有那份数据）。
 * 这一层假设写进 reason：不然员工改完日期也想不起来要点一次重新分析。
 */
function localFields (ctx, rewrite, days) {
  const list = []

  if (!ctx.tripType && ctx.partnerType) {
    const supplier = ctx.partnerType === 'SUPPLIER'
    list.push({
      field: 'tripType',
      label: '出差类型',
      currentValue: '',
      suggestValue: supplier ? '供应商审核' : '商务拜访',
      reason: `关联对象是${supplier ? '供应商' : '客户'}，同对象的单历史上多按这个类型走`,
      source: 'DB_PARTNER',
      sourceText: '',
      confidence: 0.5,
      overwrite: false
    })
  }

  const ref = ctx.costRef
  const avg = Number(ref?.avgAmount || 0)
  const assumed = days <= 0
  const effDays = assumed ? 2 : days
  if (ref && !ref.lowSample && avg > 0) {
    const nights = Math.max(0, effDays - 1)
    const share = assumed
      ? `行程日期还没填，暂按 ${effDays} 天 ${nights} 晚估`
      : `${effDays} 天 ${nights} 晚，住宿按 ${nights}/${effDays} 摊`
    const hotel = round100((avg * nights) / effDays)
    const traffic = round100(avg - (avg * nights) / effDays)
    if (hotel !== Number(ctx.estHotel || 0)) {
      list.push({
        field: 'estHotel',
        label: '预估住宿费',
        currentValue: ctx.estHotel == null ? '' : String(round100(ctx.estHotel)),
        suggestValue: String(hotel),
        reason: `同类「${ref.toCity} · ${ref.tripType || '全部类型'}」均值 ¥${round100(avg)}，${share}，住宿约 ¥${hotel}`,
        source: 'DB_COST_REF',
        sourceText: `近 12 个月 ${ref.sampleCnt} 单`,
        confidence: 0.75,
        overwrite: Number(ctx.estHotel || 0) > 0
      })
    }
    if (traffic !== Number(ctx.estTraffic || 0)) {
      list.push({
        field: 'estTraffic',
        label: '预估交通费',
        currentValue: ctx.estTraffic == null ? '' : String(round100(ctx.estTraffic)),
        suggestValue: String(traffic),
        reason: `同口径下交通约 ¥${traffic}，与住宿合计 ¥${hotel + traffic}`,
        source: 'DB_COST_REF',
        sourceText: `近 12 个月 ${ref.sampleCnt} 单`,
        confidence: 0.75,
        overwrite: Number(ctx.estTraffic || 0) > 0
      })
    }
  }

  const reason = String(ctx.reason || '').trim()
  if (reason && rewrite && rewrite !== reason) {
    list.push({
      field: 'reason',
      label: '出差事由',
      currentValue: reason,
      suggestValue: rewrite,
      reason: '把「去做什么 + 预期拿到什么」写成一句，审批人一眼能看出这趟的目的',
      source: 'LOCAL_TEMPLATE',
      sourceText: '按已填内容拼的模板句，不含编造成分',
      confidence: 0.5,
      overwrite: true
    })
  }
  return list
}

// ---------- 2. 填写页：预估费用的人话解释 ----------

/**
 * 把「本次预估 vs 同类区间」这组已有数字翻成人话。
 *
 * <p>只有这一项是<b>无论哪种模式都在前端算</b>的刻意选择：
 * 它消费的是 A-06 已经查回来的同一份数据，再为它往返一次后端没有新信息，
 * 而且让模型去复述数字反而多了说错的可能。脱机演示时这一行也照常出。
 */
export async function aiCostExplain (ctx) {
  if (!aiEnabled) return null
  await delay(REMOTE ? 0 : 260)

  const ref = ctx.costRef
  const estTotal = Number(ctx.estTotal || 0)
  if (!ref || !estTotal) return null

  const avg = Number(ref.avgAmount || 0)
  const min = Number(ref.minAmount || 0)
  const max = Number(ref.maxAmount || 0)
  const hotel = Number(ctx.estHotel || 0)
  const traffic = Number(ctx.estTraffic || 0)
  const diff = avg ? (estTotal - avg) / avg : 0
  const level = diff > 0.2 ? 'high' : diff < -0.2 ? 'low' : 'normal'

  const parts = []
  if (avg) {
    parts.push(`本次预估 ¥${estTotal.toLocaleString('zh-CN')}，同类均值 ¥${avg.toLocaleString('zh-CN')}，${diff >= 0 ? '高' : '低'} ${Math.abs(Math.round(diff * 100))}%`)
  } else {
    parts.push(`本次预估 ¥${estTotal.toLocaleString('zh-CN')}`)
  }
  if (max && estTotal > max) {
    parts.push(`已高于区间上限 ¥${max.toLocaleString('zh-CN')}`)
  } else if (min && estTotal < min) {
    parts.push(`低于区间下限 ¥${min.toLocaleString('zh-CN')}，属于偏省的一趟`)
  }
  if (hotel > 0) {
    parts.push(`住宿 ¥${hotel.toLocaleString('zh-CN')} 占 ${Math.round((hotel / estTotal) * 100)}%`)
  }
  if (traffic > 0 && hotel > 0 && traffic < hotel / 2) {
    parts.push('住宿明显高于交通，通常意味着天数偏长或酒店档位偏高')
  }

  return { level, text: parts.join('；') + '。' }
}

// ---------- 3. AI 合理性评价：语义调节 Δ（G-03） ----------

/**
 * 规则模型读不懂的那一维：事由与行程匹不匹配、说不说得通。
 *
 * <p>只输出 Δ（−10 ~ +5）和带证据的理由，最终分与档位仍由规则切：
 * 总分 = clamp(规则加权分 + Δ, 0, 100)。AI 挂了 Δ 取 0 就是原模型。
 *
 * <p>evidence 是关键：每条理由必须指向一个真实字段，页面会原样展示，
 * 后端也会校验引用值，对不上就整条丢弃（见 AiSemanticEvaluator）。
 */
export async function aiSemanticEval (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    const data = await call(() => request.post('/eval/semantic', {
      reason: ctx.reason || '',
      tripType: ctx.tripType || '',
      // 传 id 不传名字：对象名称由后端按 id 查，避免把客户端的字符串喂给模型
      partnerId: ctx.partnerId ?? null,
      estTraffic: ctx.estTraffic ?? null,
      estHotel: ctx.estHotel ?? null,
      itineraries: pickSegments(ctx.itineraries)
    }, SILENCE))
    if (!data) return null
    return {
      grade: data.grade,
      delta: data.delta ?? 0,
      reasons: normalizePoints(data.reasons),
      missing: data.missing || [],
      confidence: data.confidence ?? 0.85,
      // 后端自己在 local 字段里说清了这一条到底是不是模型输出的
      local: !!data.local,
      enhanced: !!data.enhanced,
      note: data.note || ''
    }
  }

  await delay(300)

  const reason = String(ctx.reason || '').trim()
  const list = ctx.itineraries || []
  const days = list.reduce((s, i) => s + daysBetween(i.startDate, i.endDate), 0)
  const dest = cityOf(list.map((i) => i.toCity).join(''))
  const partnerCity = cityOf(ctx.partnerName)

  const reasons = []
  const missing = []
  let delta = 0

  if (!OUTPUT_WORDS.some((w) => reason.includes(w))) {
    missing.push('预期产出')
    delta -= 3
    reasons.push({
      point: '事由没写预期产出，判断不了这趟值不值',
      evidence: { field: 'reason', value: reason.slice(0, 24) || '(空)' }
    })
  }
  if (days >= 4 && ['验货', '商务拜访'].includes(ctx.tripType)) {
    delta -= 7
    reasons.push({
      point: `${ctx.tripType}一般 1~2 天，本次行程 ${days} 天偏长`,
      evidence: { field: 'itinerary', value: `共 ${days} 天` }
    })
  }
  if (partnerCity && dest && partnerCity !== dest) {
    delta -= 3
    reasons.push({
      point: `对象在${partnerCity}，行程目的地是${dest}，事由里没交代为什么`,
      evidence: { field: 'itinerary[0].toCity', value: dest }
    })
  }
  if (reason && !PURPOSE_WORDS.some((w) => reason.includes(w))) {
    delta -= 3
    reasons.push({
      point: '事由里看不出具体去做什么',
      evidence: { field: 'reason', value: reason.slice(0, 24) }
    })
  }

  // 说得通的加分：写明产出 + 天数合理 + 长度够
  const good = OUTPUT_WORDS.some((w) => reason.includes(w)) && days > 0 && days <= 3 && reason.length >= 15
  if (good) {
    delta = Math.min(delta + 5, 5)
    reasons.unshift({
      point: '事由写明了目的与预期产出，行程天数也匹配',
      evidence: { field: 'reason', value: reason.slice(0, 24) }
    })
  }

  delta = Math.max(-10, Math.min(5, delta))
  const grade = delta >= 5 ? 'A' : delta === 0 ? 'B' : delta >= -3 ? 'C' : delta >= -7 ? 'D' : 'E'

  return {
    grade,
    delta,
    reasons,
    missing,
    // 本地模拟给低置信度，页面会提示「低置信度，仅供参考」
    confidence: aiLocal ? 0.6 : 0.85,
    local: aiLocal
  }
}

// ---------- 4. 审批页：风险清单（B-04） ----------

/**
 * 把散落在参考卡、差标卡、评价卡、流转记录里的信号汇总成几条关注点。
 * 只做归纳，不替审批人下结论。
 *
 * <p>remote 模式下后端已经有了这五份数据，不需要前端再传一遍——
 * 传多了反而可能在页面上造出一个与单据不符的版本。
 */
export async function aiApprovalRisk (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    const applyNo = ctx.applyNo
    if (!applyNo) return null
    const data = await call(() => request.post(`/approval/${applyNo}/risk`, null, SILENCE))
    if (!data) return null
    return {
      applyNo: data.applyNo,
      points: normalizePoints(data.points),
      summary: data.summary || '',
      local: !!data.local
    }
  }

  await delay(340)

  const points = []
  const apply = ctx.apply || {}
  const ref = ctx.costRef
  const estTotal = Number(apply.estTotal || 0)

  if (ref && estTotal) {
    const max = Number(ref.maxAmount || 0)
    const avg = Number(ref.avgAmount || 0)
    if (max && estTotal > max) {
      points.push({
        level: 'high',
        text: `预估 ¥${estTotal.toLocaleString('zh-CN')} 高于同类区间上限 ¥${max.toLocaleString('zh-CN')}，超出 ${Math.round(((estTotal - max) / max) * 100)}%`
      })
    } else if (avg && estTotal > avg * 1.2) {
      points.push({
        level: 'mid',
        text: `预估比同类均值高 ${Math.round(((estTotal - avg) / avg) * 100)}%（均值 ¥${avg.toLocaleString('zh-CN')}）`
      })
    }
  }

  const exceed = (ctx.standardHints || []).filter((h) => h.result === 'EXCEED')
  if (exceed.length) {
    points.push({
      level: 'high',
      text: `${exceed.length} 段行程超出差标${apply.exceedReason ? '' : '，且未填写超标说明'}`
    })
  }

  const level = ctx.eval?.level
  if (level === 'ATTENTION') {
    points.push({ level: 'high', text: `合理性评价为「重点关注」，加权总分 ${ctx.eval.totalScore ?? '—'}` })
  } else if (level === 'NOTICE') {
    points.push({ level: 'mid', text: `合理性评价为「值得关注」，加权总分 ${ctx.eval.totalScore ?? '—'}` })
  }

  const rejected = (ctx.records || []).some((r) => r.result === 'REJECTED')
  if (rejected) {
    points.push({ level: 'mid', text: '这单此前被驳回过，当前是修改后重新提交' })
  }

  const reasonLen = String(apply.reason || '').length
  if (reasonLen && reasonLen < 15) {
    points.push({ level: 'mid', text: `事由只有 ${reasonLen} 个字，信息不足以判断必要性` })
  }

  if (!points.length) {
    points.push({ level: 'low', text: '没发现需要特别关注的点：预估在同类区间内，差标与评价结论都正常' })
  }

  const high = points.filter((p) => p.level === 'high').length
  return {
    points,
    summary: high ? `有 ${high} 条建议重点确认` : '整体平稳，按常规判断即可'
  }
}

// ---------- 5. 票据：自动挂到行程段（C-05） ----------

/**
 * 按开票日期、销售方城市推荐挂接段，带置信度。
 * 置信度低的必须让人自己选——这是「AI 真做一次决策」的点，兜底要留给人。
 */
export async function aiInvoiceMatch (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    if (!ctx.applyNo) return null
    const data = await call(() => request.get('/invoice/match', {
      params: { applyNo: ctx.applyNo, invoiceDate: ctx.invoiceDate || '', seller: ctx.seller || '' },
      ...SILENCE
    }))
    // 后端没有行程段时返回 null / 空对象，页面就不显示「AI 推荐」那一行
    if (!data || !data.itineraryId) return null
    return { itineraryId: data.itineraryId, confidence: data.confidence ?? 0, reason: data.reason || '' }
  }

  await delay(240)

  const list = ctx.itineraries || []
  if (!list.length) return null

  const date = ctx.invoiceDate || ''
  const sellerCity = cityOf(ctx.seller)

  // 开票日期落在哪一段的行程区间里，最硬的证据
  let hit = null
  if (date) {
    hit = list.find((it) => it.startDate && it.endDate && date >= it.startDate && date <= it.endDate)
  }
  if (hit) {
    return {
      itineraryId: hit.id,
      confidence: 0.92,
      reason: `开票日期 ${date} 落在第 ${hit.seq} 段行程区间（${hit.startDate} ~ ${hit.endDate}）`
    }
  }

  // 日期对不上就靠销售方城市认
  const byCity = sellerCity ? list.find((it) => cityOf(it.toCity) === sellerCity || cityOf(it.fromCity) === sellerCity) : null
  if (byCity) {
    return {
      itineraryId: byCity.id,
      confidence: 0.7,
      reason: `开票日期不在行程区间内，但销售方城市与第 ${byCity.seq} 段（${byCity.fromCity}→${byCity.toCity}）吻合`
    }
  }

  const first = list[0]
  return {
    itineraryId: first.id,
    confidence: 0.4,
    reason: '日期与城市都对不上，先挂到第一段，请人工确认'
  }
}

// ---------- 6. 财务复核：规则之外的可疑模式（E-05） ----------

/**
 * 四维比对抓的是「不一致」，这里抓的是「太巧了」：
 * 同日同商户多张票、连号发票、金额显著偏离本单均值。
 * 只提示，不判违规——系统没有权力替业务认定这张票该不该报。
 *
 * <p>remote 模式只传报销单号：后端自己就有这张单的票据清单，
 * 再把清单从前端传回去是绕远路，还可能在传输途中丢几条。
 */
export async function aiExpenseSuspicion (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    if (!ctx.reportNo) return null
    const data = await call(() => request.get(`/finance/${ctx.reportNo}/suspicion`, { ...SILENCE }))
    if (!data) return null
    return {
      reportNo: data.reportNo,
      invoiceCnt: data.invoiceCnt ?? 0,
      points: normalizePoints(data.points),
      summary: data.summary || '',
      local: !!data.local
    }
  }

  await delay(300)

  const list = (ctx.invoices || []).filter((i) => i && i.invoiceNo)
  if (!list.length) return null

  const points = []
  const yuan = (n) => Number(n || 0).toLocaleString('zh-CN')

  // 同一天、同一销售方开了好几张：可能是拆分开票
  const byDay = {}
  list.forEach((i) => {
    const key = `${i.invoiceDate || ''}|${i.seller || ''}`
    if (!byDay[key]) byDay[key] = []
    byDay[key].push(i)
  })
  Object.keys(byDay).forEach((key) => {
    const arr = byDay[key]
    if (arr.length < 2) return
    const [date, seller] = key.split('|')
    const sum = arr.reduce((s, i) => s + Number(i.amount || 0), 0)
    points.push({
      level: 'mid',
      text: `${date || '同一天'} 在「${seller || '同一销售方'}」开了 ${arr.length} 张票，合计 ¥${yuan(sum)}，确认是不是拆分开票`,
      evidence: { field: 'invoice.invoiceDate', value: date || '(空)' }
    })
  })

  // 连号发票：不违规，但值得一起看一眼
  const bySeller = {}
  list.forEach((i) => {
    const s = i.seller || ''
    if (!bySeller[s]) bySeller[s] = []
    bySeller[s].push(i)
  })
  Object.keys(bySeller).forEach((seller) => {
    // 发票号是 20 位，Number 装不下（超过 MAX_SAFE_INTEGER 会丢精度，
    // 相邻两个号转成数字后差值为 0，连号就永远抓不到），这里用 BigInt 比
    const nums = bySeller[seller]
      .map((i) => String(i.invoiceNo || ''))
      .filter((s) => /^\d+$/.test(s))
      .sort((a, b) => (BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0))
    let seq = 0
    for (let i = 1; i < nums.length; i++) {
      if (BigInt(nums[i]) - BigInt(nums[i - 1]) === 1n) seq++
    }
    if (seq >= 1) {
      points.push({
        level: 'mid',
        text: `「${seller || '同一销售方'}」有 ${seq + 1} 张发票号码连号，连号本身不违规，建议一并核对`,
        evidence: { field: 'invoice.invoiceNo', value: nums[0] }
      })
    }
  })

  // 单张金额显著高于本单均值
  const amounts = list.map((i) => Number(i.amount || 0))
  const avg = amounts.reduce((a, b) => a + b, 0) / amounts.length
  const big = avg > 0 ? list.find((i) => Number(i.amount || 0) > avg * 3) : null
  if (big) {
    points.push({
      level: 'low',
      text: `有一张 ¥${yuan(big.amount)}（${big.seller || '—'}）明显高于本单均值 ¥${yuan(Math.round(avg))}`,
      evidence: { field: 'invoice.amount', value: String(big.amount) }
    })
  }

  if (!points.length) {
    points.push({ level: 'low', text: '没发现规则之外的可疑模式，按常规复核即可' })
  }

  const mid = points.filter((p) => p.level === 'mid').length
  return { points, summary: mid ? `有 ${mid} 条建议人工确认` : '整体正常' }
}

// ---------- 7. 月报：结论生成 ----------

/**
 * 把月报表里的数字写成一段结论。
 *
 * <p>remote 模式下这一句由后端随月报表一起算好（MonthlyReportVO.conclusion），
 * 不再单独往返一次：月报本身就是一次全表统计，它的结论必须和表里的数字同源，
 * 让前端拿着行数据再算一遍等于承认两边可能有两套口径。
 */
export async function aiMonthlySummary (ctx) {
  if (!aiEnabled) return null

  if (REMOTE) {
    const conclusion = ctx.conclusion
    if (!conclusion) return null
    return {
      highlights: normalizePoints(conclusion.highlights),
      text: conclusion.text || ''
    }
  }

  await delay(320)

  const rows = (ctx.rows || []).filter((r) => r && r.ratio != null)
  if (!rows.length) return null

  // 费效比口径是「投入 ÷ 产出」，越低越划算：升序排，头是最好、尾是最差
  const sorted = [...rows].sort((a, b) => Number(a.ratio) - Number(b.ratio))
  const best = sorted[0]
  const worst = sorted[sorted.length - 1]
  const highlights = []

  if (best) {
    highlights.push({ level: 'low', text: `${best.deptName || best.name || '—'} 费效比最好（${best.ratio}），投入产出最划算` })
  }
  if (worst && worst !== best) {
    // 0.45 与月报页面的色阶一致：超过它标红，这里也按 high 提示
    highlights.push({
      level: Number(worst.ratio) > 0.45 ? 'high' : 'mid',
      text: `${worst.deptName || worst.name || '—'} 费效比最差（${worst.ratio}），花得多产出少，下次出差前建议先看历史产出`
    })
  }
  if (ctx.overBudget?.length) {
    highlights.push({ level: 'high', text: `有 ${ctx.overBudget.length} 个部门超出月度预算` })
  }

  return { highlights, text: highlights.map((h) => h.text).join('；') + '。' }
}

/**
 * 月度洞察：月报数字之上的一层解读，带环比归因分解。
 *
 * <p>与 aiMonthlySummary 的分工：那份是「把表里数字复述成几句话」，永远有输出；
 * 这份调用后端模型，输出可能被数字白名单校验整条丢弃，也可能模型不可用，
 * 所以返回 null 是常态之一——页面拿到 null 就回落到月报自带的结论，不显错误。
 *
 * <p>mock 模式下直接返回 null：本地启发式归纳不出比模板更好的措辞，
 * 硬凑一份只会让人误以为那是模型写的。
 */
export async function aiMonthlyInsight (ctx) {
  if (!aiEnabled) return null
  if (!REMOTE) return null

  const raw = await call(() => request.get('/analytics/insight', {
    params: { month: ctx.month },
    ...SILENCE
  }))
  if (!raw) return null

  return shapeInsight(raw)
}

/**
 * 手动重新分析。
 *
 * <p>和上面那个 GET 的区别要说清：GET 是「有旧的就用旧的」，
 * 这个 POST 是「我就要一份新的」，后端会如实再调一次模型，
 * 哪怕入参跟上次一字不差。所以只有人点了按钮才调它，
 * 绝不放进页面加载流程里。
 */
export async function aiMonthlyInsightRefresh (ctx) {
  if (!aiEnabled) return null
  if (!REMOTE) return null

  const raw = await call(() => request.post('/analytics/insight/refresh', null, {
    params: { month: ctx.month },
    ...SILENCE
  }))
  if (!raw) return null

  return shapeInsight(raw)
}

function shapeInsight (raw) {
  return {
    month: raw.month,
    prevMonth: raw.prevMonth,
    highlights: normalizePoints(raw.highlights),
    summary: raw.summary || '',
    drivers: raw.drivers || [],
    // 建议动作。code 是六个固定值之一，target 可能是空串（面向全公司，页面显示横杠）
    actions: (raw.actions || []).map((a) => ({
      code: a.code,
      codeText: a.codeText || '',
      target: a.target || '',
      detail: a.detail || '',
      adopted: a.adopted === true,
      basis: a.basis || ''
    })),
    // 数字出处。页面不展示——印成一行小字就是讲解型文案，看了也不知道该怎么办。
    // 留着是为了被追问「这个数哪来的」时能答，也供后端自检口径。
    sources: raw.sources || [],
    ratio: raw.ratio,
    prevRatio: raw.prevRatio,
    delta: raw.delta,
    sampleNote: raw.sampleNote,
    // 样本量与置信度档位。confidence 这个 0~1 的数刻意不上页面：
    // 写「置信度 0.82」是讲解型小字，看了也不知道该怎么办，页面只要档位来选颜色。
    sampleCnt: raw.sampleCnt == null ? null : raw.sampleCnt,
    confidence: raw.confidence == null ? null : raw.confidence,
    confidenceLevel: raw.confidenceLevel || null,
    local: raw.local === true,
    model: raw.model,
    promptVersion: raw.promptVersion,
    // 报告是哪一天几点出的、怎么出的、出了之后数据是不是又变过
    generatedAt: raw.generatedAt || null,
    generatedBy: raw.generatedBy || null,
    stale: raw.stale === true
  }
}

/**
 * 标记 / 撤销一条「建议」的跟进状态。
 *
 * <p>这个勾选必须落库：只在前端本地改一下的话刷新就没了，等于没记。
 * 而「这份报告有没有真的推动过一件事」靠的就是它。
 *
 * <p>失败静默：勾不上不弹红条，页面上把这一项恢复成原样即可。
 */
export async function aiInsightAction ({ month, code, target = '', adopted = true }) {
  if (!aiEnabled || !REMOTE) return false
  const res = await call(() => request.post('/analytics/insight/action', null, {
    params: { month, code, target, adopted },
    ...SILENCE
  }))
  return res === true
}

/**
 * 这份洞察有没有用：人看完点一下「有帮助 / 没帮助」。
 *
 * <p>与「建议的跟进状态」是两件事，别混：那边记的是报告让去查的那件事做没做，
 * 这里记的是这份报告本身值不值得看。落的地方也不同——写回当月那条 AI 留痕的「采纳」。
 *
 * <p>失败静默：记不上不弹红条，只是这一次反馈没了。
 */
export async function aiInsightFeedback ({ month, helpful = true }) {
  if (!aiEnabled || !REMOTE) return false
  const res = await call(() => request.post('/analytics/insight/feedback', null, {
    params: { month, helpful },
    ...SILENCE
  }))
  return res === true
}

/**
 * 填写助手的采纳回执：提交成功后把每条建议的最终取值发回后端。
 *
 * <p>出建议那一刻人是还没做决定的，采纳与否只能事后补。
 * 少了这一步，ai_log 就只是「调用日志」，算不出「建议采纳率」。
 *
 * <p>失败静默：这是记账，不是业务主流程，不能因为记不上让提交失败。
 */
export async function aiAdviceAdopt ({ bizNo, items = [] }) {
  if (!aiEnabled || !REMOTE || !bizNo || !items.length) return false
  const res = await call(() => request.post('/ai/advice/adopt', { bizNo, items }, SILENCE))
  return res === true
}

/**
 * 钻取解释：这个部门这个月为什么是这个样子。
 *
 * <p>与月度洞察的两点区别，决定了它的调用方式：
 * 一是<b>按次触发</b> —— 只有人点了「看分析」才调，切部门切月份都不自动跑，
 * 主动冒出来的解释等于又一块没人要的文字；
 * 二是<b>不落库</b> —— 单次问答，页面切走就没了，下次要看再问一次。
 *
 * <p>mock 模式下返回 null：本地启发式答不出「为什么」，硬凑一份就成了冒充模型的文字。
 */
export async function aiDrillExplain ({ deptId, month }) {
  if (!aiEnabled) return null
  if (!REMOTE) return null

  const raw = await call(() => request.post('/analytics/drill/explain', null, {
    params: { deptId, month },
    ...SILENCE
  }))
  if (!raw) return null

  return {
    deptId: raw.deptId,
    deptName: raw.deptName || '',
    month: raw.month,
    prevMonth: raw.prevMonth,
    highlights: normalizePoints(raw.highlights),
    summary: raw.summary || '',
    sampleCnt: raw.sampleCnt == null ? null : raw.sampleCnt,
    ratio: raw.ratio,
    prevRatio: raw.prevRatio,
    delta: raw.delta,
    local: raw.local === true,
    model: raw.model,
    promptVersion: raw.promptVersion,
    sources: raw.sources || []
  }
}
