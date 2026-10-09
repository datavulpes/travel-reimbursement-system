<template>
  <div class="page-wrap" v-loading="loading">
    <div class="card">
      <div class="card-hd">
        <h3>对象投入产出对照</h3>
        <div>
          <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width:150px" @change="load" />
        </div>
      </div>
      <div class="card-bd">
        <el-table :data="rows" border stripe size="small">
          <el-table-column prop="partnerName" label="往来对象" min-width="200" />
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.partnerType === 'CUSTOMER' ? 'primary' : 'warning'" size="small" effect="plain">
                {{ PARTNER_TYPES[row.partnerType] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="累计投入" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalInvest) }}</span></template>
          </el-table-column>
          <el-table-column label="累计产出" width="130" align="right">
            <template #default="{ row }">
              <span v-if="row.totalOutput" class="amount">¥ {{ money(row.totalOutput) }}</span>
              <el-tag v-else size="small" type="info" effect="plain">尚无</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="投入产出比" width="120" align="center">
            <template #default="{ row }">
              <span v-if="row.totalOutput" :style="{ color: ratioColor(row.ratio), fontWeight: 600 }">
                {{ ratio(row.ratio) }}
              </span>
              <span v-else class="weak">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="tripCnt" label="出差次数" width="100" align="center" />
          <el-table-column label="单次平均投入" min-width="150">
            <template #default="{ row }">
              <div class="avg-bar">
                <div class="avg-fill" :style="{ width: avgWidth(row) }"></div>
                <span class="avg-text amount">¥ {{ money(avgInvest(row)) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="grid-2 mt-16">
      <div class="card">
        <div class="card-hd"><h3>怎么读这张表</h3></div>
        <div class="card-bd">
          <div class="rule-item">
            <div class="rule-title">比值低的，是已经跑通的对象</div>
            <div class="rule-desc">投入产出比 = 累计投入 ÷ 累计产出，低于 0.3 说明这几趟差旅换来的生意值当</div>
          </div>
          <div class="rule-item">
            <div class="rule-title">比值高的，不等于该停</div>
            <div class="rule-desc">可能是新客户还在投入期，也可能是维系型出差。系统只把数字摆出来，不停谁的差旅</div>
          </div>
          <div class="rule-item">
            <div class="rule-title">尚无产出的，看样本够不够</div>
            <div class="rule-desc">只去过一两次、时间又短的，样本太小，结论不可靠</div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-hd"><h3>合计口径</h3></div>
        <div class="card-bd">
          <div class="grid-2">
            <div class="kpi-card">
              <div class="kpi-label">对象总数</div>
              <div class="kpi-value">{{ rows.length }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">累计投入</div>
              <div class="kpi-value amount" style="font-size:20px">¥ {{ money(totalInvest) }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">累计产出</div>
              <div class="kpi-value amount" style="font-size:20px">¥ {{ money(totalOutput) }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">整体投入产出比</div>
              <div class="kpi-value" :style="{ color: ratioColor(overallRatio) }">{{ ratio(overallRatio) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { partnerIo } from '@/api'
import { money, ratio, PARTNER_TYPES } from '@/utils/dict'

const loading = ref(false)
const month = ref(dayjs().format('YYYY-MM'))
const data = reactive({ rows: [] })

const rows = computed(() => data.rows || [])
const totalInvest = computed(() => rows.value.reduce((s, r) => s + r.totalInvest, 0))
const totalOutput = computed(() => rows.value.reduce((s, r) => s + r.totalOutput, 0))
// 与后端 PartnerRoiVO.ratio 同口径：投入 ÷ 产出，产出为 0 时记 0（页面标灰）
const overallRatio = computed(() => (totalOutput.value ? totalInvest.value / totalOutput.value : 0))
const maxAvg = computed(() => Math.max(...rows.value.map((r) => r.totalInvest / (r.tripCnt || 1)), 1))

/** 人均投入 = 累计投入 ÷ 出差次数；一个对象还没人去过时 tripCnt=0，不能硬除 */
function avgInvest (row) {
  return row.tripCnt ? row.totalInvest / row.tripCnt : 0
}

// 色阶与月报页 / 钻取页保持一致：≤0.30 绿、≤0.45 黄、再高标红。
// 后端评价因子 F2 是 5%（满分）~50%（零分），跟这两个坎基本对得上。
function ratioColor (r) {
  if (r <= 0.3) return 'var(--c-green)'
  if (r <= 0.45) return 'var(--c-amber)'
  return 'var(--c-red)'
}

function avgWidth (row) {
  return (avgInvest(row) / maxAvg.value) * 100 + '%'
}

async function load () {
  loading.value = true
  try {
    Object.assign(data, await partnerIo({ month: month.value }))
  } finally {
    loading.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'MgtPartner' })

/**
 * 每次回到这页重新拉一次，月份选择留在原处。
 * 用 activated 而不是 mounted：组件进了 keep-alive 之后切走不再 mounted，
 * 只靠 mounted 的话回来看到的还是上一次进来时的数据。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(load)
</script>

<style scoped>
.avg-bar {
  position: relative;
  height: 22px;
  background: #f3f5f8;
  border-radius: 4px;
  overflow: hidden;
}
.avg-fill { position: absolute; left: 0; top: 0; height: 100%; background: #cfe0f0; }
.avg-text { position: relative; font-size: 12px; padding-left: 8px; line-height: 22px; }

.rule-item { padding: 9px 0; border-bottom: 1px dashed var(--c-border); }
.rule-item:last-child { border-bottom: none; }
.rule-title { font-size: 13px; font-weight: 600; }
.rule-desc { font-size: 12.5px; color: var(--c-text-sub); margin-top: 2px; }
</style>
