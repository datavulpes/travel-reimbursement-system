<template>
  <div class="page-wrap" v-loading="loading">
    <div class="card">
      <div class="card-hd">
        <h3>归因钻取</h3>
        <span class="weak">从部门一路点到具体出差单</span>
      </div>
      <div class="card-bd">
        <el-select v-model="deptId" style="width:180px" @change="load">
          <el-option v-for="d in depts" :key="d.deptId" :label="d.deptName" :value="d.deptId" />
        </el-select>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width:150px; margin-left:10px" @change="onMonthChange" />
        <span class="muted" style="margin-left:14px">
          部门 {{ currentDept?.deptName }} · 本月预算 ¥ {{ money(currentDept?.monthlyBudget) }} · 已用 ¥ {{ money(currentDept?.usedBudget) }}
        </span>
      </div>
    </div>

    <div class="card mt-16">
      <div class="card-hd">
        <h3>钻取结果</h3>
      </div>
      <div class="card-bd">
        <el-table
          :data="data.rows || []" border size="small"
          row-key="bizNo"
          :tree-props="{ children: 'children' }"
          default-expand-all
        >
          <el-table-column prop="name" label="层级 / 名称" min-width="280">
            <template #default="{ row }">
              <el-tag size="small" :type="levelTag(row.level)" effect="plain" style="margin-right:8px">
                {{ levelText(row.level) }}
              </el-tag>
              {{ row.name }}
            </template>
          </el-table-column>
          <el-table-column prop="bizNo" label="业务号" width="160" class-name="mono" />
          <el-table-column label="投入" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.payAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="产出" width="130" align="right">
            <template #default="{ row }">
              <span v-if="row.outputAmount" class="amount">¥ {{ money(row.outputAmount) }}</span>
              <el-tag v-else size="small" type="info" effect="plain">未回填</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="费效比" width="110" align="center">
            <template #default="{ row }">
              <span v-if="row.outputAmount" :style="{ color: ratioColor(row.ratio), fontWeight: 600 }">{{ ratio(row.ratio) }}</span>
              <span v-else class="weak">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!--
      钻取解释：上面那张表只给数字，看的人还得自己想「为什么会这样」。
      这块点「看分析」才出——按次触发，切部门切月份都不自动跑，
      一是省调用，二是主动冒出来的解释等于又一块没人要的文字。
    -->
    <AiAdvice
      v-if="aiEnabled && aiRemote"
      class="mt-16"
      title="AI分析"
      :points="explain?.highlights || []"
      :summary="explain?.summary || ''"
      :loading="explainLoading"
      :local="explain?.local !== false"
      :source-tag="!!explain"
    >
      <template #extra>
        <el-button
          link type="primary" size="small"
          :disabled="explainLoading" @click="loadExplain"
        >{{ explain ? '再看一次' : '看分析' }}</el-button>
      </template>
    </AiAdvice>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { drillDown, monthlyReport } from '@/api'
import { aiEnabled, aiRemote, aiDrillExplain } from '@/api/ai'
import AiAdvice from '@/components/AiAdvice.vue'
import { deptSeed } from '@/config/depts'
import { money, ratio } from '@/utils/dict'

const route = useRoute()
const loading = ref(false)
// 后端没有部门列表接口，用 config/depts.js 里与库一致的基础数据（原先用 mock 数据）
const depts = ref(deptSeed())
// 默认看当月：写死 2026-09 到下个月就查不到数据了
const deptId = ref(Number(route.query.deptId) || depts.value[0]?.deptId || 1)
const month = ref(route.query.month || dayjs().format('YYYY-MM'))
const data = reactive({ rows: [] })

const currentDept = computed(() => depts.value.find((d) => d.deptId === deptId.value))

/**
 * 钻取解释。刻意不落库、不缓存：它是人问了一句才有的答复，
 * 切走就没了，想再看再问一次。留着旧答案反而会让人以为它跟着数据一起更新过。
 */
const explain = ref(null)
const explainLoading = ref(false)

async function loadExplain () {
  if (explainLoading.value) return
  explainLoading.value = true
  try {
    explain.value = await aiDrillExplain({ deptId: deptId.value, month: month.value })
  } finally {
    explainLoading.value = false
  }
}

function levelText (l) {
  return { DEPT: '部门', REPORT: '报销单', APPLY: '出差单' }[l] || l
}

function levelTag (l) {
  return { DEPT: 'primary', REPORT: 'warning', APPLY: 'info' }[l] || 'info'
}

function ratioColor (r) {
  if (r <= 0.3) return 'var(--c-green)'
  if (r <= 0.45) return 'var(--c-amber)'
  return 'var(--c-red)'
}

async function load () {
  loading.value = true
  // 换了部门或月份，上一份解释就作废了：它是针对那一个部门那一个月的答复
  explain.value = null
  try {
    Object.assign(data, await drillDown({ deptId: deptId.value, month: month.value }))
  } finally {
    loading.value = false
  }
}

/** 顶部的「已用」用 F-01 月报里各部门本月实际报销额填，不编数字 */
async function loadUsage () {
  try {
    const rep = await monthlyReport({ month: month.value })
    const paid = Object.fromEntries((rep.rows || []).map((r) => [r.deptId, r.totalPay]))
    depts.value = deptSeed().map((d) => ({ ...d, usedBudget: paid[d.deptId] || 0 }))
  } catch (e) {
    // 月报错了不影响钻取本身
  }
}

/**
 * 换月份要连「已用」一起刷：那是按月从月报接口取的，
 * 只刷下面的明细不刷它，就会拿上个月的已用冒充这个月的（0.00 假象就是这么来的）。
 * 换部门不用刷——「已用」是全部门一起拉回来存在 depts 里的。
 */
function onMonthChange () {
  loadUsage()
  load()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'MgtDrill' })

/**
 * 部门和月份既可能由地址栏带来（月报里点某一行钻进来），也可能是人在这页自己选的。
 * 进来先看地址栏：带了就是明确要看的那个部门与月份，优先；没带就保持上次选的。
 * 数据是实时统计，回来都重拉一遍。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(async () => {
  const qDept = Number(route.query.deptId) || 0
  const qMonth = route.query.month || ''
  if (qDept) deptId.value = qDept
  if (qMonth) month.value = qMonth
  await loadUsage()
  load()
})
</script>
