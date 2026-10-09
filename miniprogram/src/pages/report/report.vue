<template>
  <view class="page page-tab">
    <!-- 月份是整页共用的：八个区块看的都是同一个月 -->
    <view class="hd">
      <text class="hd-t">经营概览</text>
      <picker mode="date" fields="month" @change="onMonth">
        <text class="month">{{ month }} ▾</text>
      </picker>
    </view>

    <view class="rp">
      <SideNav :items="SECTIONS" :current="cur" @switch="cur = $event" />

      <scroll-view class="rp-main" scroll-y>
        <!-- ① 总览 -->
        <view v-if="cur === 'overview'">
          <view class="card">
            <text class="sec">本月关键数字</text>
            <view class="grid" v-if="s">
              <view class="g-item"><text class="g-lbl">已付投入</text><text class="g-val money">¥ {{ fmtMoney(s.totalPay) }}</text></view>
              <view class="g-item"><text class="g-lbl">折算产出</text><text class="g-val">¥ {{ fmtMoney(s.totalOutput) }}</text></view>
              <view class="g-item"><text class="g-lbl">费效比</text><text :class="['g-val', ratioCls(s.ratio)]">{{ ratioText(s.ratio) }}</text></view>
              <view class="g-item"><text class="g-lbl">需关注</text><text class="g-val">{{ fmtInt(s.attentionCnt) }} 单</text></view>
              <view class="g-item"><text class="g-lbl">成果回填率</text><text class="g-val">{{ pct(s.fillRate) }}</text></view>
              <view class="g-item"><text class="g-lbl">事前拦截</text><text class="g-val">¥ {{ fmtMoney(s.blockedAmount) }}</text></view>
            </view>
            <text v-else class="dim">加载中…</text>
            <text class="note">费效比 = 投入 ÷ 产出，越低越好。投入取已付款金额，不是票面金额</text>
          </view>

          <view class="card" v-if="s && s.conclusion && s.conclusion.text">
            <text class="sec">本期结论</text>
            <text class="concl">{{ s.conclusion.text }}</text>
            <text v-for="(h, i) in (s.conclusion.highlights || [])" :key="i" :class="['ai-line', h.level]">· {{ h.text }}</text>
            <text class="note">结论由后端模板生成，不进模型，里面的数字都出自本页统计</text>
          </view>

          <view class="card" v-if="s">
            <text class="sec">这个月能不能信</text>
            <view class="kv"><text class="k">已付款单据</text><text class="v">{{ fmtInt(s.paidCnt) }} 单</text></view>
            <view class="kv"><text class="k">其中已回填成果</text><text class="v">{{ fmtInt(s.filledCnt) }} 单</text></view>
            <view class="kv"><text class="k">出差申请</text><text class="v">{{ fmtInt(s.applyCnt) }} 单</text></view>
            <view class="kv"><text class="k">需关注占比</text><text class="v">{{ pct(s.attentionRatio) }}</text></view>
            <text class="note">回填率 40% 和 90% 下算出来的费效比，可信度完全不同</text>
          </view>
        </view>

        <!-- ② 部门投入产出 -->
        <view v-if="cur === 'dept'">
          <view class="card">
            <text class="sec">各部门投入与产出</text>
            <ChartBars class="mt" :items="deptBars" :height="240" />
            <text class="note">投入取已付款金额；产出按业务结果折算。柱高按最大值等比，不是绝对值</text>
          </view>

          <view class="card">
            <view class="row1">
              <text class="sec">部门明细</text>
              <view class="fbtn-sm" @click="dDrawer = true">
                <text class="fbtn-ic">☰</text>
                <text class="fbtn-sm-t">筛选</text>
                <text v-if="dChips.length" class="fbadge-sm">{{ dChips.length }}</text>
              </view>
            </view>
            <view v-if="dChips.length" class="fchips">
              <view v-for="c in dChips" :key="c.k" class="fchip" @click="clearChip('d', c.k)">
                <text class="fchip-t">{{ c.t }}</text>
                <text class="fchip-x">×</text>
              </view>
            </view>
            <view v-if="LD.total === 0" class="ph">没有符合条件的部门，点上方的 × 放宽条件</view>
            <view v-for="(r, i) in LD.pagedRows" :key="i" class="drow" @click="drill(r.deptId)">
              <view class="drow-1">
                <text class="dept">{{ r.deptName }}</text>
                <text :class="['val', ratioCls(r.ratio)]">{{ ratioText(r.ratio) }}</text>
              </view>
              <view class="drow-2">
                <text class="meta">投入 ¥ {{ fmtMoney(r.totalPay) }} · 产出 ¥ {{ fmtMoney(r.totalOutput) }} · {{ fmtInt(r.applyCnt) }} 单</text>
              </view>
              <view class="iobar">
                <view class="io-out" :style="{ width: ioWidth(r, 'out') }" />
                <view class="io-in" :style="{ width: ioWidth(r, 'in') }" />
              </view>
            </view>
            <Pager
              v-if="LD.total > 0"
              :total="LD.total" :page-num="LD.pageNum" :page-size="LD.pageSize"
              @page="LD.onPage" @size="LD.onSize" />
            <text class="note">点一行看这个部门的单据构成。产出为 0 的部门不算「最好」，那是数据缺口</text>
          </view>
        </view>

        <!-- ③ 出差类型 -->
        <view v-if="cur === 'type'">
          <view class="card">
            <view class="row1">
              <text class="sec">各类出差的投入与产出</text>
              <view class="fbtn-sm" @click="tDrawer = true">
                <text class="fbtn-ic">☰</text>
                <text class="fbtn-sm-t">筛选</text>
                <text v-if="tChips.length" class="fbadge-sm">{{ tChips.length }}</text>
              </view>
            </view>
            <view v-if="tChips.length" class="fchips">
              <view v-for="c in tChips" :key="c.k" class="fchip" @click="clearChip('t', c.k)">
                <text class="fchip-t">{{ c.t }}</text>
                <text class="fchip-x">×</text>
              </view>
            </view>
            <view v-if="LT.total === 0" class="ph">没有符合条件的数据</view>
            <view v-for="(r, i) in LT.pagedRows" :key="i" class="trow">
              <view class="drow-1">
                <text class="dept">{{ r.label || r.tripType || '未填类型' }}</text>
                <text :class="['val', ratioCls(r.ratio)]">{{ ratioText(r.ratio) }}</text>
              </view>
              <text class="meta">投入 ¥ {{ fmtMoney(r.totalPay) }} · 产出 ¥ {{ fmtMoney(r.totalOutput) }} · {{ fmtInt(r.applyCnt) }} 单</text>
              <view class="tbar">
                <view class="tbar-fill" :style="{ width: typeWidth(r) }" />
              </view>
            </view>
            <Pager
              v-if="LT.total > 0"
              :total="LT.total" :page-num="LT.pageNum" :page-size="LT.pageSize"
              @page="LT.onPage" @size="LT.onSize" />
            <text class="note">条子是投入占比。部门是组织边界改不了，出差类型是可以调的</text>
          </view>
        </view>

        <!-- ④ 产出构成 -->
        <view v-if="cur === 'output'">
          <view class="card">
            <text class="sec">产出构成</text>
            <template v-if="structItems.length">
              <view v-for="it in structItems" :key="it.resultType" class="srow">
                <text class="s-name">{{ it.resultType }}</text>
                <view class="s-bar">
                  <view class="s-fill" :style="{ width: structWidth(it), background: it.resultType === '无进展' ? '#d83931' : '#10956a' }" />
                </view>
                <text class="s-cnt">{{ it.cnt }} 条</text>
                <text class="s-amt">¥ {{ fmtMoney(it.amount) }}</text>
              </view>
              <view class="sfoot">
                <text class="sfoot-l">无进展率</text>
                <text :class="['sfoot-v', npCls]">{{ pct(struct && struct.noProgressRate) }}</text>
                <text class="sfoot-s">共 {{ (struct && struct.totalCnt) || 0 }} 条成果</text>
              </view>
              <text class="note">费效比差可能是花多了，也可能是白跑了，这两种的处理方式完全不同</text>
            </template>
            <view v-else class="ph">本月还没有回填的业务结果，产出这一半是空的</view>
          </view>
        </view>

        <!-- ⑤ 趋势 -->
        <view v-if="cur === 'trend'">
          <view class="card">
            <text class="sec">费效比趋势</text>
            <text class="dim">近 6 个月，含本月</text>
            <view v-if="trendLoading" class="ph">加载中…</view>
            <template v-else>
              <ChartLine class="mt" :points="trendPoints" :width="470" :height="200" />
              <view v-for="(p, i) in trendPoints" :key="i" class="tline">
                <text class="tl-m">{{ p.label }}</text>
                <text :class="['tl-v', ratioCls(p.value)]">{{ ratioText(p.value) }}</text>
              </view>
              <text class="note">趋势要按月各查一次统计，所以只在进这一块时拉一次，不在进页面时一起拉</text>
            </template>
          </view>
        </view>

        <!-- ⑥ 往来对象 -->
        <view v-if="cur === 'partner'">
          <view class="card">
            <view class="row1">
              <text class="sec">往来对象投入产出</text>
              <view class="fbtn-sm" @click="rDrawer = true">
                <text class="fbtn-ic">☰</text>
                <text class="fbtn-sm-t">筛选</text>
                <text v-if="rChips.length" class="fbadge-sm">{{ rChips.length }}</text>
              </view>
            </view>
            <view v-if="rChips.length" class="fchips">
              <view v-for="c in rChips" :key="c.k" class="fchip" @click="clearChip('r', c.k)">
                <text class="fchip-t">{{ c.t }}</text>
                <text class="fchip-x">×</text>
              </view>
            </view>
            <view v-if="LR.total === 0" class="ph">没有符合条件的对象，点上方的 × 放宽条件</view>
            <view v-for="(r, i) in LR.pagedRows" :key="i" class="it">
              <text class="it-line">{{ r.partnerName }}（{{ r.partnerType || '—' }}）· {{ r.tripCnt }} 趟</text>
              <text class="it-sub">投入 ¥ {{ fmtMoney(r.totalInvest) }} → 产出 ¥ {{ fmtMoney(r.totalOutput) }}（{{ ratioText(r.ratio) }}）</text>
            </view>
            <Pager
              v-if="LR.total > 0"
              :total="LR.total" :page-num="LR.pageNum" :page-size="LR.pageSize"
              @page="LR.onPage" @size="LR.onSize" />
          </view>
        </view>

        <!-- ⑦ 月度洞察 -->
        <view v-if="cur === 'insight'">
          <view class="card">
            <view class="row1">
              <text class="sec">月度洞察</text>
              <text v-if="ins && ins.stale" class="tag tag-amber">数据已更新</text>
            </view>
            <text class="btn btn-ghost blk" :class="{ disabled: insLoading }" @click="refresh">
              {{ insLoading ? '重新分析中…' : '重新分析' }}
            </text>
            <template v-if="ins">
              <text class="ins-sum">{{ ins.summary || '—' }}</text>
              <view class="grid">
                <view class="g-item"><text class="g-lbl">本月费效比</text><text class="g-val">{{ ratioText(ins.ratio) }}</text></view>
                <view class="g-item"><text class="g-lbl">上月</text><text class="g-val">{{ ratioText(ins.prevRatio) }}</text></view>
              </view>
              <text v-for="(h, i) in (ins.highlights || [])" :key="'h' + i" :class="['ai-line', h.level]">· {{ h.text }}</text>
              <view v-for="(dv, i) in (ins.drivers || [])" :key="'d' + i" class="it">
                <text class="it-line">{{ dv.name || dv.dim }}：贡献 {{ dv.contribution }}</text>
              </view>
              <text class="note">{{ ins.local ? '本地规则生成' : '模型生成' }} · 更新于 {{ ins.generatedAt || '—' }}</text>
            </template>
            <text v-else class="note">这个月还没有洞察，点上面按钮生成一份</text>
            <text class="note">进页面读的是库里那份快照，同一天反复看是同一份；只有点「重新分析」才会真调一次模型</text>
          </view>
        </view>

        <!-- ⑧ 差标与预算 -->
        <view v-if="cur === 'config'">
          <view class="card">
            <text class="sec">差旅标准</text>
            <text class="meta" v-if="stdRows.length">{{ stdRows.length }} 档在生效 · 按「城市等级 + 交通方式」比对</text>
            <text class="dim" v-else>加载中…</text>
            <view v-for="(r, i) in stdRows.slice(0, 6)" :key="i" class="kv">
              <text class="k">{{ r.cityLevel }} · {{ r.trafficType }}</text>
              <text class="v money">¥ {{ fmtMoney(r.maxAmount) }}</text>
            </view>
            <text v-if="stdRows.length > 6" class="note">只列前 6 档，完整的去「差标维护」里看</text>
            <text class="btn btn-ghost blk" @click="goStd('standard')">差标维护 ›</text>
          </view>

          <view class="card">
            <text class="sec">部门预算使用</text>
            <view v-if="!deptRows.length" class="ph">加载中…</view>
            <view v-for="(d, i) in deptRows" :key="i" class="brow">
              <view class="drow-1">
                <text class="dept">{{ d.deptName }}</text>
                <text class="val">{{ d.monthlyBudget > 0 ? pct(d.usedBudget / d.monthlyBudget) : '未设预算' }}</text>
              </view>
              <text class="meta">预算 ¥ {{ fmtMoney(d.monthlyBudget) }} · 本月已付 ¥ {{ fmtMoney(d.usedBudget) }}</text>
              <view class="tbar">
                <view class="tbar-fill" :class="{ over: d.monthlyBudget > 0 && d.usedBudget / d.monthlyBudget > 0.9 }"
                  :style="{ width: budgetWidth(d) }" />
              </view>
            </view>
            <text class="btn btn-ghost blk" @click="goStd('budget')">部门与预算维护 ›</text>
            <text class="btn btn-ghost blk" @click="goStd('logs')">调整留痕 ›</text>
            <text class="note">已使用 = 本月实际付款额（dept 表没有「已用」这一列，不存冗余）</text>
          </view>
        </view>

        <view class="pad" />
      </scroll-view>
    </view>

    <!-- 三块各一个抽屉，条件互不干扰 -->
    <FilterDrawer
      v-if="dDrawer" :groups="dGroups" :values="LD.filters"
      local-note="这一页的数字后端一次算好，筛选与翻页都在已取回的结果里做"
      @close="dDrawer = false" @apply="onDeptFilter" />
    <FilterDrawer
      v-if="tDrawer" :groups="tGroups" :values="LT.filters"
      local-note="同上：出差类型这一块也是本地筛，不重新请求"
      @close="tDrawer = false" @apply="onTypeFilter" />
    <FilterDrawer
      v-if="rDrawer" :groups="rGroups" :values="LR.filters"
      local-note="同上：对象这一块也是本地筛，不重新请求"
      @close="rDrawer = false" @apply="onRoiFilter" />

    <TabBar current="report" />
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  analyticsMonthly, analyticsPartner, insight, insightRefresh, analyticsDrill,
  standards, depts
} from '../../api'
import { fmtMoney, fmtInt } from '../../utils/dict'
import { usePagedList, activeKeys } from '../../utils/paged'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import SideNav from '../../components/SideNav.vue'
import ChartBars from '../../components/ChartBars.vue'
import ChartLine from '../../components/ChartLine.vue'
import TabBar from '../../components/TabBar.vue'
import { hasTab } from '../../utils/tabs'

// 进页面默认落在第一块，跟「先看总盘、再往下钻」的顺序一致
const SECTIONS = [
  { key: 'overview', text: '总览' },
  { key: 'dept', text: '部门' },
  { key: 'type', text: '出差类型' },
  { key: 'output', text: '产出构成' },
  { key: 'trend', text: '趋势' },
  { key: 'partner', text: '往来对象' },
  { key: 'insight', text: '月度洞察' },
  { key: 'config', text: '差标预算' }
]
const cur = ref('overview')

const month = ref(nowMonth())
const s = ref(null)
const roi = ref(null)
const ins = ref(null)
const insLoading = ref(false)
const dDrawer = ref(false)
const tDrawer = ref(false)
const rDrawer = ref(false)

// 差标与预算：只在切到那一块时才拉，进页面时为它多发两个请求不值
const stdRows = ref([])
const deptRows = ref([])

function nowMonth () {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

const ratioText = (r) => (r == null ? '—' : Number(r).toFixed(4))
// ≤0.30 绿 / ≤0.45 黄 / >0.45 红
const ratioCls = (r) => {
  if (r == null) return ''
  const v = Number(r)
  return v <= 0.30 ? 'good' : (v <= 0.45 ? 'warn' : 'bad')
}
const pct = (v) => (v == null ? '—' : (Number(v) * 100).toFixed(1) + '%')
// 与 ratioCls 同一档：绿 / 黄 / 红 / 没有产出（算不出比值）
const levelOf = (r) => {
  if (r == null || r === '') return 'none'
  const v = Number(r)
  return v <= 0.30 ? 'green' : (v <= 0.45 ? 'amber' : 'red')
}

/* ---------------- 图表数据 ---------------- */

const deptBars = computed(() => ((s.value && s.value.rows) || []).map((r) => ({
  label: r.deptName,
  values: [Number(r.totalPay) || 0, Number(r.totalOutput) || 0]
})))

// 投入产出对比条：一根条里两套宽度，谁长谁短一眼看得出
function ioMax () {
  let m = 1
  for (const r of (s.value && s.value.rows) || []) {
    m = Math.max(m, Number(r.totalPay) || 0, Number(r.totalOutput) || 0)
  }
  return m
}
function ioWidth (r, which) {
  const v = which === 'out' ? Number(r.totalOutput) || 0 : Number(r.totalPay) || 0
  return ((v / ioMax()) * 100).toFixed(1) + '%'
}

function typeWidth (r) {
  let m = 1
  for (const x of (s.value && s.value.typeRows) || []) m = Math.max(m, Number(x.totalPay) || 0)
  return (((Number(r.totalPay) || 0) / m) * 100).toFixed(1) + '%'
}

const struct = computed(() => (s.value && s.value.outputStruct) || null)
const structItems = computed(() => (struct.value && struct.value.items) || [])
const npCls = computed(() => {
  const v = Number((struct.value && struct.value.noProgressRate) || 0)
  return v >= 0.3 ? 'bad' : (v >= 0.15 ? 'warn' : 'good')
})
function structWidth (it) {
  let m = 1
  for (const x of structItems.value) m = Math.max(m, Number(x.cnt) || 0)
  return (((Number(it.cnt) || 0) / m) * 100).toFixed(1) + '%'
}

/* ---------------- 趋势：近 6 个月各查一次 ---------------- */

const trendLoading = ref(false)
const trendPoints = ref([])
let trendMonth = ''

async function loadTrend () {
  trendLoading.value = true
  const months = last6(month.value)
  const reps = await Promise.all(months.map((m) => analyticsMonthly(m).catch(() => null)))
  trendPoints.value = months.map((m, i) => ({
    label: m.slice(5) + '月',
    value: reps[i] ? Number(reps[i].ratio || 0) : 0
  }))
  trendMonth = month.value
  trendLoading.value = false
}

function last6 (m) {
  const [y, mo] = m.split('-').map(Number)
  const out = []
  for (let i = 5; i >= 0; i--) {
    const d = new Date(y, mo - 1 - i, 1)
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  return out
}

/* ---------------- 按部门：筛费效比区间 ---------------- */

const LEVEL = { '': '不限', green: '绿（≤ 0.30）', amber: '黄（0.30 – 0.45）', red: '红（> 0.45）', none: '产出为 0，算不出' }
const DSORT = { '': '默认（后端顺序）', ratioDesc: '费效比高 → 低', ratioAsc: '费效比低 → 高' }

function filterDept (list, f) {
  let out = list.slice()
  if (f.keyword) {
    const k = f.keyword.trim()
    out = out.filter((r) => (r.deptName || '').indexOf(k) >= 0)
  }
  if (f.level) out = out.filter((r) => levelOf(r.ratio) === f.level)
  const byRatio = (a, b) => (Number(a.ratio) || 0) - (Number(b.ratio) || 0)
  // 产出为 0 的比值是 null，排序时一律沉到最后，不跟真值混排
  const rank = (r) => (r.ratio == null || r.ratio === '' ? 1 : 0)
  if (f.sort === 'ratioDesc') out = out.sort((a, b) => rank(a) - rank(b) || byRatio(b, a))
  if (f.sort === 'ratioAsc') out = out.sort((a, b) => rank(a) - rank(b) || byRatio(a, b))
  return out
}

const LD = usePagedList(filterDept, { keyword: '', level: '', sort: '' })

const dGroups = [
  { key: 'keyword', label: '部门名称', type: 'text', placeholder: '部门名关键字' },
  { key: 'level', label: '费效比区间', type: 'select', options: Object.keys(LEVEL).map((k) => ({ value: k, label: LEVEL[k] })) },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(DSORT).map((k) => ({ value: k, label: DSORT[k] })) }
]
const dChips = computed(() => activeKeys(LD.filters, [
  { key: 'keyword', label: '部门' },
  { key: 'level', label: '费效比', text: (v) => LEVEL[v] || v },
  { key: 'sort', label: '排序', text: (v) => DSORT[v] || v }
]))

/* ---------------- 出差类型 ---------------- */

const TSORT = { '': '默认（后端顺序）', payDesc: '投入高 → 低', ratioAsc: '费效比低 → 高' }

function filterType (list, f) {
  let out = list.slice()
  if (f.keyword) {
    const k = f.keyword.trim()
    out = out.filter((r) => ((r.label || r.tripType || '') + '').indexOf(k) >= 0)
  }
  if (f.level) out = out.filter((r) => levelOf(r.ratio) === f.level)
  if (f.sort === 'payDesc') out = out.sort((a, b) => (Number(b.totalPay) || 0) - (Number(a.totalPay) || 0))
  if (f.sort === 'ratioAsc') out = out.sort((a, b) => (Number(a.ratio) || 0) - (Number(b.ratio) || 0))
  return out
}

const LT = usePagedList(filterType, { keyword: '', level: '', sort: '' })

const tGroups = [
  { key: 'keyword', label: '出差类型', type: 'text', placeholder: '类型名关键字' },
  { key: 'level', label: '费效比区间', type: 'select', options: Object.keys(LEVEL).map((k) => ({ value: k, label: LEVEL[k] })) },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(TSORT).map((k) => ({ value: k, label: TSORT[k] })) }
]
const tChips = computed(() => activeKeys(LT.filters, [
  { key: 'keyword', label: '类型' },
  { key: 'level', label: '费效比', text: (v) => LEVEL[v] || v },
  { key: 'sort', label: '排序', text: (v) => TSORT[v] || v }
]))

/* ---------------- 往来对象：筛名称与类型 ---------------- */

const RTYPE = { '': '不限', CUSTOMER: '客户', SUPPLIER: '供应商' }
const RSORT = { '': '默认（后端顺序）', investDesc: '投入高 → 低', investAsc: '投入低 → 高', ratioAsc: '费效比低 → 高' }

function filterRoi (list, f) {
  let out = list.slice()
  if (f.keyword) {
    const k = f.keyword.trim()
    out = out.filter((r) => (r.partnerName || '').indexOf(k) >= 0)
  }
  if (f.type) out = out.filter((r) => r.partnerType === f.type)
  const byInvest = (a, b) => (Number(a.totalInvest) || 0) - (Number(b.totalInvest) || 0)
  const byRatio = (a, b) => (Number(a.ratio) || 0) - (Number(b.ratio) || 0)
  if (f.sort === 'investDesc') out = out.sort((a, b) => byInvest(b, a))
  if (f.sort === 'investAsc') out = out.sort(byInvest)
  if (f.sort === 'ratioAsc') out = out.sort(byRatio)
  return out
}

const LR = usePagedList(filterRoi, { keyword: '', type: '', sort: '' })

const rGroups = [
  { key: 'keyword', label: '对象名称', type: 'text', placeholder: '对象名关键字' },
  { key: 'type', label: '类型', type: 'select', options: Object.keys(RTYPE).map((k) => ({ value: k, label: RTYPE[k] })) },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(RSORT).map((k) => ({ value: k, label: RSORT[k] })) }
]
const rChips = computed(() => activeKeys(LR.filters, [
  { key: 'keyword', label: '对象' },
  { key: 'type', label: '类型', text: (v) => RTYPE[v] || v },
  { key: 'sort', label: '排序', text: (v) => RSORT[v] || v }
]))

function onDeptFilter (f) {
  LD.filters = f
  dDrawer.value = false
  LD.resetPaging()
}
function onTypeFilter (f) {
  LT.filters = f
  tDrawer.value = false
  LT.resetPaging()
}
function onRoiFilter (f) {
  LR.filters = f
  rDrawer.value = false
  LR.resetPaging()
}
function clearChip (which, k) {
  const L = which === 'd' ? LD : (which === 't' ? LT : LR)
  const next = { ...L.filters }
  next[k] = ''
  L.filters = next
  L.resetPaging()
}

/* ---------------- 取数 ---------------- */

async function loadAll () {
  try { s.value = await analyticsMonthly(month.value) } catch (e) { /* toast 已弹 */ }
  try { roi.value = await analyticsPartner(month.value) } catch (e) { /* toast 已弹 */ }
  try { ins.value = await insight(month.value) } catch (e) { ins.value = null }
  // 三块列表的原料，筛与翻页都在本地做（后端一次就把整月算完了）
  LD.setList((s.value && s.value.rows) || [])
  LT.setList((s.value && s.value.typeRows) || [])
  LR.setList((roi.value && roi.value.rows) || [])
  LD.resetPaging()
  LT.resetPaging()
  LR.resetPaging()
  // 月份变了，趋势得重算
  if (trendMonth && trendMonth !== month.value) loadTrend()
}

async function loadConfig () {
  try { stdRows.value = (await standards()) || [] } catch (e) { /* toast 已弹 */ }
  try {
    const list = (await depts()) || []
    // 已用额度 = 本月各部门实际付款额，与 Web 端同一口径
    const paid = {}
    for (const r of (s.value && s.value.rows) || []) paid[r.deptId] = Number(r.totalPay) || 0
    deptRows.value = list.map((d) => ({ ...d, usedBudget: paid[d.deptId] || 0 }))
  } catch (e) { /* toast 已弹 */ }
}

function budgetWidth (d) {
  if (!(Number(d.monthlyBudget) > 0)) return '0%'
  const p = (Number(d.usedBudget) || 0) / Number(d.monthlyBudget) * 100
  return Math.min(p, 100).toFixed(1) + '%'
}

async function refresh () {
  if (insLoading.value) return
  insLoading.value = true
  try { ins.value = await insightRefresh(month.value) } catch (e) { /* toast 已弹 */ }
  insLoading.value = false
}

function onMonth (e) {
  month.value = e.detail.value
  loadAll()
}

async function drill (deptId) {
  try {
    const v = await analyticsDrill(deptId, month.value)
    const pays = (v.applies || []).reduce((t, x) => t + Number(x.payAmount || 0), 0)
    const outs = (v.applies || []).reduce((t, x) => t + Number(x.outputAmount || 0), 0)
    uni.showModal({
      title: `${v.deptName} · ${month.value}`,
      content: `单据 ${(v.applies || []).length} 张\n投入 ¥${pays.toFixed(2)}\n产出 ¥${outs.toFixed(2)}`,
      showCancel: false
    })
  } catch (e) { /* toast 已弹 */ }
}

const goStd = (tab) => uni.navigateTo({ url: `/pages/standard/standard?tab=${tab}` })

// 切到哪一块才拉哪一块的数据：趋势 6 次请求、差标 2 次，都不该在进页面时白跑
watch(cur, (k) => {
  if (k === 'trend' && (!trendPoints.value.length || trendMonth !== month.value)) loadTrend()
  if (k === 'config') loadConfig()
})

onLoad(() => {
  // 概览只挂管理层的导航，别的角色手输路径进来送回工作台
  if (!hasTab('report')) {
    uni.showToast({ title: '经营概览是管理层的功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  loadAll()
})

// 从差标维护页改完回来，这一块要能看到新数
onShow(() => { if (cur.value === 'config') loadConfig() })
</script>

<style>
.hd { display: flex; justify-content: space-between; align-items: center;
  height: 76rpx; margin-bottom: 16rpx; }
.hd-t { font-size: 34rpx; font-weight: 600; color: #1f2328; }
.month { font-size: 28rpx; color: #2f6fed; }

.rp { display: flex; gap: 16rpx; height: calc(100vh - 300rpx); }
.rp-main { flex: 1; height: 100%; }
.pad { height: 40rpx; }

.concl { display: block; margin-top: 12rpx; font-size: 28rpx; color: #1f2328; }
.it { margin-top: 16rpx; }
.it-line { display: block; font-size: 27rpx; color: #1f2328; }
.it-sub { display: block; font-size: 24rpx; color: #8a919c; margin-top: 4rpx; }
.meta { display: block; font-size: 24rpx; color: #8a919c; margin-top: 4rpx; }
.dept { font-size: 28rpx; color: #1f2328; }
.val { font-size: 28rpx; }
.good { color: #2e7d32; }
.warn { color: #ad6800; }
.bad { color: #d83931; }
.dim { font-size: 24rpx; color: #8a919c; }
.note { display: block; margin-top: 8rpx; font-size: 22rpx; color: #8a919c; }
.ph { padding: 28rpx 0; text-align: center; font-size: 24rpx; color: #9aa2ad; }

.kv { display: flex; justify-content: space-between; align-items: center;
  min-height: 68rpx; border-bottom: 1rpx solid #f0f1f3; }
.k { font-size: 27rpx; color: #5f6773; }
.v { font-size: 27rpx; color: #1f2328; }

.drow { padding: 18rpx 0; border-bottom: 1rpx solid #f0f1f3; }
.drow-1 { display: flex; justify-content: space-between; align-items: center; }
.drow-2 { margin-top: 4rpx; }
.iobar { position: relative; height: 10rpx; background: #f0f1f3;
  border-radius: 5rpx; overflow: hidden; margin-top: 12rpx; }
.io-out { position: absolute; left: 0; top: 0; height: 100%; background: #10956a; opacity: .55; }
.io-in { position: absolute; left: 0; top: 0; height: 100%; background: #c0392b; opacity: .35; }

.trow { padding: 18rpx 0; border-bottom: 1rpx solid #f0f1f3; }
.tbar { height: 10rpx; background: #f0f1f3; border-radius: 5rpx; overflow: hidden; margin-top: 12rpx; }
.tbar-fill { height: 100%; background: #c0392b; opacity: .5; }
.tbar-fill.over { background: #d83931; opacity: .8; }

.srow { display: flex; align-items: center; margin-top: 18rpx; }
.s-name { width: 88rpx; flex: none; font-size: 25rpx; color: #1f2328; }
.s-bar { flex: 1; height: 14rpx; background: #f0f1f3; border-radius: 7rpx; overflow: hidden; }
.s-fill { height: 100%; opacity: .65; }
.s-cnt { width: 68rpx; flex: none; text-align: right; font-size: 22rpx; color: #8a919c; }
.s-amt { width: 130rpx; flex: none; text-align: right; font-size: 24rpx; color: #1f2328; }
.sfoot { display: flex; align-items: center; margin-top: 24rpx; }
.sfoot-l { font-size: 25rpx; color: #5f6773; }
.sfoot-v { font-size: 28rpx; font-weight: 500; margin-left: 12rpx; }
.sfoot-s { font-size: 22rpx; color: #8a919c; margin-left: 12rpx; }

.tline { display: flex; justify-content: space-between; align-items: center;
  min-height: 60rpx; border-bottom: 1rpx solid #f0f1f3; }
.tl-m { font-size: 25rpx; color: #5f6773; }
.tl-v { font-size: 26rpx; }

.brow { padding: 18rpx 0; border-bottom: 1rpx solid #f0f1f3; }

.ins-sum { display: block; margin-top: 16rpx; font-size: 28rpx; color: #1f2328; }
.ai-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 8rpx; }
.ai-line.high { color: #d83931; }
.ai-line.mid { color: #ad6800; }
.ai-line.low { color: #5f6773; }
.disabled { opacity: .5; }
/* text 是行内元素，不撑开就没有按钮的高度和点击区 */
.blk { display: block; }

.fbtn-sm { display: flex; align-items: center; background: #eef3fe; border-radius: 999rpx;
  padding: 8rpx 20rpx; position: relative; }
.fbtn-ic { font-size: 26rpx; color: #2f6fed; margin-right: 8rpx; }
.fbtn-sm-t { font-size: 24rpx; color: #2f6fed; }
.fbadge-sm { margin-left: 8rpx; min-width: 28rpx; height: 28rpx; border-radius: 14rpx;
  background: #d83931; color: #fff; font-size: 18rpx; display: flex; align-items: center;
  justify-content: center; padding: 0 6rpx; box-sizing: border-box; }
.fchips { display: flex; flex-wrap: wrap; gap: 12rpx; margin: 16rpx 0 4rpx; }
.fchip { display: flex; align-items: center; background: #eef3fe; color: #2f6fed;
  border-radius: 999rpx; padding: 8rpx 20rpx; font-size: 22rpx; }
.fchip-x { margin-left: 8rpx; font-size: 26rpx; }
</style>
