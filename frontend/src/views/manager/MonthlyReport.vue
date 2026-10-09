<template>
  <div class="page-wrap" v-loading="loading">
    <!-- 筛选 -->
    <div class="card">
      <div class="card-bd">
        <span class="muted" style="margin-right:10px">统计月份</span>
        <el-date-picker
          v-model="month" type="month" value-format="YYYY-MM"
          style="width:150px" @change="load"
        />
      </div>
    </div>

    <!-- KPI：前三个是「花了多少、换回多少、划不划算」，后三个是「这些数字能不能信」 -->
    <div class="grid-3 mt-16">
      <div class="kpi-card">
        <div class="kpi-label">本月差旅投入</div>
        <div class="kpi-value amount">¥ {{ money(summary.totalPay) }}</div>
        <div class="kpi-foot">全司口径 · 已付款</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">本月折算产出</div>
        <div class="kpi-value amount">¥ {{ money(summary.totalOutput) }}</div>
        <div class="kpi-foot">按业务结果回填折算</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">费效比</div>
        <div class="kpi-value" :style="{ color: ratioColor(summary.ratio) }">{{ ratio(summary.ratio) }}</div>
        <div class="kpi-foot">投入 ÷ 产出，越低越好</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">重点关注单</div>
        <div class="kpi-value" style="color: var(--c-red)">{{ summary.attentionCnt }}</div>
        <div class="kpi-foot">评价档位 ATTENTION · 占本月 {{ percent(summary.attentionRatio) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">产出回填率</div>
        <div class="kpi-value" :style="{ color: fillRateColor(summary.fillRate) }">{{ percent(summary.fillRate) }}</div>
        <div class="kpi-foot">{{ summary.filledCnt ?? 0 }} / {{ summary.paidCnt ?? 0 }} 单已回填</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">事前拦截金额</div>
        <div class="kpi-value amount">¥ {{ money(summary.blockedAmount) }}</div>
        <div class="kpi-foot">差标超限且最终未成行</div>
      </div>
    </div>

    <!-- AI 月报结论：把表里的数字写成几句话，数字仍以表为准 -->
    <div class="mt-16" v-if="aiEnabled && (aiLoading || insightPoints.length)">
      <AiAdvice
        title="本月结论"
        :points="insightPoints"
        :summary="insightSummary"
        :loading="aiLoading"
        :local="insightLocal"
        empty-text="本月数据不足，给不出结论"
      >
        <!--
          标题右侧这一排是这份报告的"身份"：什么时候出的、怎么出的、
          出了之后数据有没有变过。放在这里而不是卡片底部，是因为它交代的是
          报告本身的出处，跟下面的结论属于同一层信息。
        -->
        <template #extra>
          <el-tag
            v-if="insightLevelText" size="small" :type="insightLevelType" effect="plain"
          >{{ insightLevelText }}</el-tag>
          <el-tag v-if="insightStale" size="small" type="warning" effect="plain">数据已更新</el-tag>
          <span v-if="insightGenAt" class="gen-at">
            更新于 {{ insightGenAt }}<template v-if="insightGenLabel"> · {{ insightGenLabel }}</template>
          </span>
          <el-button
            v-if="aiRemote" link type="primary" size="small"
            :disabled="aiLoading" @click="rerunInsight"
          >重新分析</el-button>
        </template>
        <template #default>
          <!--
            这份报告有没有用，只有看的人说了算。记下来是为了算出「洞察采纳率」——
            不然 AI 有没有价值只能靠一句「我觉得挺好用」。
            可以改主意，以最后一次为准；记不上去也不提示，这不是业务操作。
          -->
          <div class="fb-row" v-if="aiRemote && insightPoints.length">
            <span class="fb-label">这份分析</span>
            <el-button
              link size="small" :type="feedback === true ? 'success' : 'info'"
              @click="sendFeedback(true)"
            >有帮助</el-button>
            <el-button
              link size="small" :type="feedback === false ? 'danger' : 'info'"
              @click="sendFeedback(false)"
            >没帮助</el-button>
          </div>
        </template>
        <!-- 归因分解：整张卡里唯一「算」出来的部分，自成一块，跟模型的措辞分开 -->
        <template #body>
          <InsightDrivers :drivers="drivers" :prev-month="insightPrevMonth" :note="sampleNote" />
          <!--
            处方层：highlights 说的是「发现了什么」，这里说「建议去查什么」。
            动作代码是固定的六个之一、对象必须是真实存在的部门或出差类型，
            所以这里不存在「模型编了个处置动作让人去执行」的风险。
            状态一列是给人记台账的：标了「已处理」只是说这条有人在跟进，
            系统不会因此去查任何东西、也不会改单据；写回那个月的报告，
            只在本地改一下刷新就没了，等于没记。
          -->
          <div v-if="insightActions.length" class="act-box">
            <div class="act-hd">建议</div>
            <div class="act-table">
              <div class="act-th">
                <span>建议动作</span>
                <span>对象</span>
                <span>依据</span>
                <span>跟进状态</span>
              </div>
              <div v-for="(a, i) in insightActions" :key="i" class="act-tr">
                <span class="act-tag">{{ a.codeText }}</span>
                <span class="act-target">{{ a.target || '—' }}</span>
                <span class="act-detail">{{ a.detail }}</span>
                <span class="act-op">
                  <template v-if="a.adopted">
                    <el-tag size="small" type="success" effect="plain">已跟进</el-tag>
                    <el-button
                      v-if="aiRemote" link type="info" size="small"
                      @click="toggleAction(a, false)"
                    >撤销</el-button>
                  </template>
                  <el-button
                    v-else link type="primary" size="small"
                    :disabled="!aiRemote" @click="toggleAction(a, true)"
                  >标记已跟进</el-button>
                </span>
              </div>
            </div>
          </div>
        </template>
      </AiAdvice>
    </div>

    <el-row :gutter="16" class="mt-16">
      <!-- 投入产出对比 -->
      <el-col :span="14" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>各部门投入与产出</h3></div>
          <div class="card-bd">
            <div ref="barRef" style="height: 300px"></div>
          </div>
        </div>
      </el-col>

      <!-- 趋势 -->
      <el-col :span="10" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>费效比趋势</h3><span class="weak">近 6 个月</span></div>
          <div class="card-bd">
            <div ref="lineRef" style="height: 300px"></div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 结构：钱花在哪类出差上、产出从哪来 -->
    <el-row :gutter="16" class="mt-16">
      <el-col :span="14" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>各类出差的投入与产出</h3></div>
          <div class="card-bd">
            <el-table :data="report.typeRows || []" border stripe size="small">
              <el-table-column prop="label" label="出差类型" min-width="110" />
              <el-table-column label="投入（付款）" width="130" align="right">
                <template #default="{ row }"><span class="amount">¥ {{ money(row.totalPay) }}</span></template>
              </el-table-column>
              <el-table-column label="产出（折算）" width="130" align="right">
                <template #default="{ row }">
                  <span v-if="row.totalOutput" class="amount">¥ {{ money(row.totalOutput) }}</span>
                  <el-tag v-else size="small" type="info" effect="plain">未回填</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="费效比" width="100" align="center">
                <template #default="{ row }">
                  <span :style="{ color: ratioColor(row.ratio), fontWeight: 600 }">{{ ratio(row.ratio) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="applyCnt" label="单数" width="80" align="center" />
              <el-table-column label="投入占比" min-width="150">
                <template #default="{ row }">
                  <div class="type-bar">
                    <div class="type-fill" :style="{ width: typeWidth(row) }"></div>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-col>

      <el-col :span="10" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>产出构成</h3></div>
          <div class="card-bd">
            <template v-if="structItems.length">
              <div v-for="it in structItems" :key="it.resultType" class="struct-row">
                <span class="struct-name">{{ it.resultType }}</span>
                <div class="struct-bar">
                  <div
                    class="struct-fill"
                    :style="{
                      width: structWidth(it),
                      background: it.resultType === '无进展' ? 'var(--c-red)' : 'var(--c-green)'
                    }"
                  ></div>
                </div>
                <span class="struct-cnt">{{ it.cnt }} 条</span>
                <span class="struct-amt amount">¥ {{ money(it.amount) }}</span>
              </div>
              <div class="row-end mt-12">
                无进展率
                <b :style="{ color: noProgressColor }">{{ percent(struct?.noProgressRate) }}</b>
                <span class="weak">（共 {{ struct?.totalCnt ?? 0 }} 条）</span>
              </div>
            </template>
            <div v-else class="tip-box tip-info">本月还没有回填的业务结果，产出这一半是空的。</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 明细 -->
    <div class="card mt-16">
      <div class="card-hd">
        <h3>部门明细</h3>
      </div>
      <div class="card-bd">
        <el-table :data="report.rows || []" border stripe size="small">
          <el-table-column prop="deptName" label="部门" width="120" />
          <el-table-column label="投入（付款）" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalPay) }}</span></template>
          </el-table-column>
          <el-table-column label="产出（折算）" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalOutput) }}</span></template>
          </el-table-column>
          <el-table-column label="费效比" width="110" align="center">
            <template #default="{ row }">
              <span :style="{ color: ratioColor(row.ratio), fontWeight: 600 }">{{ ratio(row.ratio) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="applyCnt" label="出差单数" width="100" align="center" />
          <el-table-column label="产出对比" min-width="200">
            <template #default="{ row }">
              <div class="io-bar">
                <div class="io-out" :style="{ width: barWidth(row, 'out') }"></div>
                <div class="io-in" :style="{ width: barWidth(row, 'in') }"></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goDrill(row.deptId)">钻取</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="row-end amount mt-12" style="font-size:14px">
          全司合计 投入 ¥ {{ money(report.totalPay) }}｜产出 ¥ {{ money(report.totalOutput) }}｜费效比 {{ ratio(report.ratio) }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onBeforeUnmount, reactive, ref, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { monthlyReport, analyticsSummary } from '@/api'
import {
  aiEnabled, aiRemote, aiLocal,
  aiMonthlySummary, aiMonthlyInsight, aiMonthlyInsightRefresh, aiInsightAction,
  aiInsightFeedback
} from '@/api/ai'
import { ElMessage } from 'element-plus'
import AiAdvice from '@/components/AiAdvice.vue'
import InsightDrivers from '@/components/InsightDrivers.vue'
import { money, ratio, percent } from '@/utils/dict'

const router = useRouter()
const loading = ref(false)
const month = ref(dayjs().format('YYYY-MM'))
const report = reactive({})
const summary = reactive({})
const trend = reactive({ months: [], ratios: [] })

const barRef = ref()
const lineRef = ref()
let barChart = null
let lineChart = null

/** 后端洞察（/analytics/insight）；拿不到时回落到下面的本地结论 */
const insight = ref(null)
/** 卡片角标：洞察可能来自模型也可能来自规则回落，按实际来源标 */
const insightLocal = ref(aiLocal)
/** AI 月报结论（月报自带的模板结论，或 VITE_AI_MODE=mock 时的本地实现） */
const aiSummary = ref(null)
const aiLoading = ref(false)

/**
 * 页面上的结论取三处的第一处有内容的：后端洞察 → 本地结论 → 月报自带结论。
 * 三级兜底不是为了堆代码，是为了「AI 挂了这页照样能看」——
 * 月报的模板结论永远跟着月报一起返回，它才是这页的底线。
 */
const insightPoints = computed(() => {
  if (insight.value?.highlights?.length) return insight.value.highlights
  if (aiSummary.value?.highlights?.length) return aiSummary.value.highlights
  return report.conclusion?.highlights || []
})

const insightSummary = computed(() =>
  insight.value?.summary || aiSummary.value?.text || report.conclusion?.text || '')

const drivers = computed(() => insight.value?.drivers || [])
const insightPrevMonth = computed(() => insight.value?.prevMonth || '')
const sampleNote = computed(() => insight.value?.sampleNote || '')

/**
 * 这份报告的身份信息。
 *
 * 为什么要显示得这么细：报告是快照，而它上方的六个 KPI 卡是实时的，
 * 两者天然可能对不上。把「什么时候出的、怎么出的、之后数据动过没有」写在明面上，
 * 看的人自己就能判断要不要信这一版，而不是由系统偷偷替他重算。
 */
const insightGenAt = computed(() => {
  const raw = insight.value?.generatedAt
  if (!raw) return ''
  const d = dayjs(raw)
  return d.isValid() ? d.format('MM-DD HH:mm') : String(raw).slice(0, 16)
})

const insightGenLabel = computed(() => ({
  AUTO: '首次打开时生成',
  SCHEDULED: '凌晨自动更新',
  MANUAL: '手动重新分析'
}[insight.value?.generatedBy] || ''))

/** true = 报告生成之后库里的统计又变过，提示看的人自己决定要不要重出 */
const insightStale = computed(() => insight.value?.stale === true)

/**
 * 这份洞察的反馈：true 有帮助 / false 没帮助 / null 还没评。
 *
 * <p>跟着月份换而重置：换了一个月就是另一份报告，
 * 拿上个月的评价显示在下面，等于替看的人做了判断。
 */
const feedback = ref(null)
watch(() => month.value, () => { feedback.value = null })

async function sendFeedback (helpful) {
  feedback.value = helpful
  const done = await aiInsightFeedback({ month: insight.value?.month || month.value, helpful })
  if (!done) {
    feedback.value = null
  }
}

/**
 * 置信度标签。页面上只出现样本量这个真实数字，不出现 0~1 那个置信度——
 * 它是算出来的中间量，写出来就是讲解型小字，看了也不知道该怎么办。
 * 样本不足时换说法并转红，因为「这份结论站不住」是必须让人看见的。
 */
const insightLevel = computed(() => insight.value?.confidenceLevel || '')
const insightLevelText = computed(() => {
  const n = insight.value?.sampleCnt
  if (!insightLevel.value || n == null) {
    return ''
  }
  return insightLevel.value === 'THIN' ? `仅 ${n} 单样本` : `样本 ${n} 单`
})
const insightLevelType = computed(() => {
  const map = { AMPLE: 'success', FAIR: 'warning', THIN: 'danger' }
  return map[insightLevel.value] || 'info'
})

/** 建议。模型一条结论都没站住时后端用规则补两条，照样是能查的事 */
const insightActions = computed(() => insight.value?.actions || [])

/**
 * 标记 / 撤销一条建议的跟进状态。
 *
 * <p>先改本地再落库，落库失败就还原：这条状态是在记录「这件事已经在查了」，
 * 记不上去却显示成已跟进，比没记更糟——看的人会以为已经有人处理过。
 *
 * <p>本地这一步不能省。只等接口回 true 再改的话，等于把「点下去有没有反应」
 * 寄托在下面这次刷新上，人点了没动静就会以为没点上。
 */
async function toggleAction (a, val) {
  const before = a.adopted
  a.adopted = val
  const done = await aiInsightAction({
    month: insight.value?.month,
    code: a.code,
    target: a.target,
    adopted: val
  })
  if (!done) {
    a.adopted = before
  }
}

// 产出构成
const struct = computed(() => report.outputStruct || null)
const structItems = computed(() => struct.value?.items || [])
const maxStructCnt = computed(() =>
  Math.max(...structItems.value.map((it) => Number(it.cnt || 0)), 1))

const noProgressColor = computed(() => {
  const v = Number(struct.value?.noProgressRate || 0)
  if (v >= 0.3) return 'var(--c-red)'
  if (v >= 0.15) return 'var(--c-amber)'
  return 'var(--c-green)'
})

async function refreshAi () {
  if (!aiEnabled || !report.rows?.length) return
  aiLoading.value = true
  try {
    insight.value = null
    const ins = await aiMonthlyInsight({ month: month.value })
    if (ins && (ins.highlights?.length || ins.drivers?.length)) {
      insight.value = ins
      insightLocal.value = ins.local
      return
    }
    // 洞察没给出来（模型不可用 / 输出没过校验 / 样本不足）就退到月报自带的结论
    insightLocal.value = aiLocal
    aiSummary.value = await aiMonthlySummary({ rows: report.rows, conclusion: report.conclusion })
  } finally {
    aiLoading.value = false
  }
}

/**
 * 手动重新分析：这一份就是要新的，后端会如实再调一次模型。
 *
 * 入口只放在这个按钮上，不放在页面加载里——加载要做的是"把已有那份拿来"，
 * 两件事混在一起就没人知道一次刷新到底烧了多少钱。
 */
async function rerunInsight () {
  if (!aiRemote || aiLoading.value) return
  aiLoading.value = true
  try {
    const ins = await aiMonthlyInsightRefresh({ month: month.value })
    if (!ins) {
      ElMessage.warning('这次没能重新生成，页面上仍是上一份报告')
      return
    }
    insight.value = ins
    insightLocal.value = ins.local
    ElMessage.success('已重新生成这份月报解读')
  } finally {
    aiLoading.value = false
  }
}

/**
 * 回填率的色阶。这条不是「效率好坏」，是「数据够不够全」——
 * 低于 60% 时下面所有的结论都只剩一半的说服力，所以标红提醒，而不是当成绩看。
 */
function fillRateColor (v) {
  if (v === null || v === undefined || v === '') return 'inherit'
  const n = Number(v)
  if (n >= 0.8) return 'var(--c-green)'
  if (n >= 0.6) return 'var(--c-amber)'
  return 'var(--c-red)'
}

function typeWidth (row) {
  const max = Math.max(...(report.typeRows || []).map((r) => Number(r.totalPay || 0)), 1)
  return (Number(row.totalPay || 0) / max) * 100 + '%'
}

function structWidth (item) {
  return (Number(item.cnt || 0) / maxStructCnt.value) * 100 + '%'
}

/**
 * 费效比口径：全系统统一为 **投入 ÷ 产出，越小越划算**。
 *
 * 后端 F-01/F-03/F-04 的 ratio 字段、F-02 钻取行的 ratio、评价因子 F2 的
 * 「成本占对象产出比」都是这个口径，页面直接读后端字段即可，不用本地重算。
 * 色阶与 0.40 警戒线也按这个口径定：超过 0.40 说明花得比赚的多，标红。
 */
function ratioColor (r) {
  if (r === undefined || r === null) return 'inherit'
  if (r <= 0.3) return 'var(--c-green)'
  if (r <= 0.45) return 'var(--c-amber)'
  return 'var(--c-red)'
}

function barWidth (row, type) {
  const max = Math.max(...(report.rows || []).map((r) => Math.max(r.totalPay, r.totalOutput)), 1)
  return ((type === 'out' ? row.totalOutput : row.totalPay) / max) * 100 + '%'
}

function renderBar () {
  if (!barChart) barChart = echarts.init(barRef.value)
  const rows = report.rows || []
  barChart.setOption({
    grid: { left: 60, right: 20, top: 40, bottom: 30 },
    legend: { data: ['投入', '产出'], right: 0, top: 0 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map((r) => r.deptName) },
    yAxis: { type: 'value' },
    series: [
      {
        name: '投入', type: 'bar', data: rows.map((r) => r.totalPay),
        itemStyle: { color: '#c0392b', borderRadius: [4, 4, 0, 0] }, barWidth: '32%'
      },
      {
        name: '产出', type: 'bar', data: rows.map((r) => r.totalOutput),
        itemStyle: { color: '#10956a', borderRadius: [4, 4, 0, 0] }, barWidth: '32%'
      }
    ]
  }, true)
}

function renderLine () {
  if (!lineChart) lineChart = echarts.init(lineRef.value)
  lineChart.setOption({
    grid: { left: 50, right: 20, top: 30, bottom: 30 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: trend.months, boundaryGap: false },
    yAxis: { type: 'value', max: 0.6 },
    series: [
      {
        name: '费效比', type: 'line', smooth: true, data: trend.ratios,
        itemStyle: { color: '#2563a8' }, areaStyle: { color: 'rgba(37,99,168,.12)' },
        markLine: {
          silent: true, symbol: 'none',
          data: [{ yAxis: 0.4, label: { formatter: '警戒线 0.40' }, lineStyle: { color: '#c0392b', type: 'dashed' } }]
        }
      }
    ]
  }, true)
}

function resize () {
  barChart?.resize()
  lineChart?.resize()
}

/**
 * 趋势图的数据后端不给：MonthlyReportVO 里没有 trend 字段，
 * 前端按月把近 6 个月的 F-01 查一遍，用同样的费效比口径拼出来。
 */
async function loadTrend () {
  const base = dayjs(month.value + '-01')
  const months = Array.from({ length: 6 }, (_, i) => base.subtract(5 - i, 'month').format('YYYY-MM'))
  const reps = await Promise.all(months.map((m) => monthlyReport({ month: m }).catch(() => null)))
  trend.months = months
  trend.ratios = reps.map((r) => (r ? Number(r.ratio || 0) : 0))
}

async function load () {
  loading.value = true
  try {
    const [rep, sum] = await Promise.all([
      monthlyReport({ month: month.value }),
      analyticsSummary({ month: month.value })
    ])
    Object.assign(report, rep)
    Object.assign(summary, sum)
    await loadTrend()
    await refreshAi()
    await nextTick()
    renderBar()
    renderLine()
  } finally {
    loading.value = false
  }
}

function goDrill (deptId) {
  router.push({ path: '/manager/drill', query: { deptId, month: month.value } })
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'MgtReport' })

/**
 * 回到月报重新拉一遍，选中的月份留在原处。
 * 顺带说清一件事：这里的 AI 洞察走的是 GET /analytics/insight（有旧的就用旧的），
 * 只有人点了「重新分析」才走那个会真调模型的 POST，所以回来刷新不会烧调用。
 *
 * resize 监听跟着展示状态走：留在 mounted 里的话，人已经走到别的页面了，
 * 缩放窗口还会去 resize 两张不在文档里的图表。图表实例本身不 dispose——
 * keep-alive 保留的是同一个 DOM 节点，回来接着用就行，renderBar 里也是「没实例才 init」。
 */
onActivated(async () => {
  await load()
  window.addEventListener('resize', resize)
  // 缓存期间画布是被移出文档的，挂回来得让它按现在的宽度重算一次，
  // 否则两张图还是上一次留在那里的尺寸
  await nextTick()
  resize()
})

onDeactivated(() => {
  window.removeEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  barChart?.dispose()
  lineChart?.dispose()
})
</script>

<style scoped>
.gen-at { font-size: 11px; color: var(--c-text-weak); }

/* 建议：四列表格（动作 / 对象 / 依据 / 状态）。只有两三行，自己排栅格比套表格组件轻 */
.act-box { margin-top: 14px; }
.act-hd {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-weak);
  margin-bottom: 6px;
}
.act-th,
.act-tr {
  display: grid;
  grid-template-columns: 88px 76px 1fr 132px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
  font-size: 12.5px;
  line-height: 1.6;
}
.act-th {
  font-size: 11.5px;
  color: var(--c-text-weak);
  border-bottom: 1px solid var(--c-border);
  padding-bottom: 4px;
}
.act-tr { border-bottom: 1px dashed var(--c-border); }
.act-tr:last-child { border-bottom: none; }
.act-tag {
  justify-self: start;
  padding: 0 6px;
  border-radius: 3px;
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: 11px;
  line-height: 18px;
}
.act-target { color: var(--c-text); font-weight: 600; }
.act-detail { color: var(--c-text-weak); }
.act-op { display: flex; align-items: center; gap: 6px; }
.act-op :deep(.el-button + .el-button) { margin-left: 0; }

/* 洞察反馈：一行里两个链接按钮，跟上面的动作区隔开一点 */
.fb-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}
.fb-label { font-size: 12px; color: var(--c-text-weak); }
.fb-row :deep(.el-button + .el-button) { margin-left: 0; }

.io-bar {
  height: 8px;
  background: #f0f2f5;
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}
.io-out { position: absolute; left: 0; top: 0; height: 100%; background: var(--c-green); opacity: .55; }
.io-in { position: absolute; left: 0; top: 0; height: 100%; background: var(--c-red); opacity: .35; }

/* 出差类型的投入占比：只看谁占得多，所以单色就够，不跟产出的绿条抢注意力 */
.type-bar {
  height: 8px;
  background: #f0f2f5;
  border-radius: 4px;
  overflow: hidden;
}
.type-fill { height: 100%; background: #c0392b; opacity: .5; }

.struct-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--c-border);
}
.struct-row:last-child { border-bottom: none; }
.struct-name { width: 62px; flex: none; font-size: 12.5px; }
.struct-bar { flex: 1; height: 10px; background: #f0f2f5; border-radius: 5px; overflow: hidden; }
.struct-fill { height: 100%; opacity: .6; }
.struct-cnt { width: 52px; flex: none; text-align: right; font-size: 12px; color: var(--c-text-sub); }
.struct-amt { width: 96px; flex: none; text-align: right; font-size: 12.5px; }

</style>
