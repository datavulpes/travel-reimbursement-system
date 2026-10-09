<template>
  <div class="page-wrap" v-loading="loading">
    <div class="card">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>AI 运行状况</h3>
          <span class="weak">模型有没有在正常工作，看这一页，不用猜</span>
        </div>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="days" size="small" style="width:120px" @change="load">
            <el-option :value="1" label="最近 1 天" />
            <el-option :value="7" label="最近 7 天" />
            <el-option :value="30" label="最近 30 天" />
            <el-option :value="365" label="全部" />
          </el-select>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>

      <div class="card-bd">
        <div class="stat-row">
          <div class="stat-cell">
            <div class="stat-label">调用次数</div>
            <div class="stat-value">{{ data.totalCnt || 0 }}</div>
          </div>
          <div class="stat-cell">
            <div class="stat-label">成功</div>
            <div class="stat-value" style="color:var(--c-green)">{{ data.successCnt || 0 }}</div>
            <div class="stat-sub">占 {{ pct(data.successCnt, data.totalCnt) }}</div>
          </div>
          <div class="stat-cell">
            <div class="stat-label">降级</div>
            <div class="stat-value" :style="{ color: (data.fallbackCnt || 0) > 0 ? 'var(--c-amber)' : 'inherit' }">
              {{ data.fallbackCnt || 0 }}
            </div>
            <div class="stat-sub">模型没接上，走了本地规则</div>
          </div>
          <div class="stat-cell">
            <div class="stat-label">平均耗时</div>
            <div class="stat-value">{{ data.avgCostMs || 0 }} ms</div>
          </div>
          <div class="stat-cell">
            <div class="stat-label">建议采纳率</div>
            <div class="stat-value">{{ data.adoptRate === null ? '—' : pct(data.adoptRate, 1, 0) }}</div>
            <div class="stat-sub">已做决定的那部分里被采用的占比</div>
          </div>
        </div>

        <div class="muted" style="margin-top:10px">
          模型 {{ data.model || '未启用' }} ·
          {{ data.enabled ? (data.usable ? '配置完整' : '配置不完整，会一直降级') : 'AI 总开关已关闭' }}
          <template v-if="!data.logEnabled"> · 留痕已关闭，这一页不会有新数据</template>
        </div>
      </div>
    </div>

    <div class="card mt-16">
      <div class="card-hd"><h3>分场景</h3></div>
      <div class="card-bd">
        <el-table :data="data.scenes || []" border size="small">
          <el-table-column prop="sceneText" label="场景" width="120" />
          <el-table-column label="调用" width="90" align="right">
            <template #default="{ row }">{{ row.cnt }}</template>
          </el-table-column>
          <el-table-column label="成功" width="90" align="right">
            <template #default="{ row }">{{ row.successCnt }}</template>
          </el-table-column>
          <el-table-column label="降级" width="90" align="right">
            <template #default="{ row }">
              <span :style="{ color: row.fallbackCnt > 0 ? 'var(--c-amber)' : 'inherit' }">{{ row.fallbackCnt }}</span>
            </template>
          </el-table-column>
          <el-table-column label="平均耗时" width="110" align="right">
            <template #default="{ row }">{{ row.avgCostMs }} ms</template>
          </el-table-column>
          <el-table-column label="采纳率" width="110" align="right">
            <template #default="{ row }">
              {{ row.adoptRate === null ? '—' : pct(row.adoptRate, 1, 0) }}
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="card mt-16">
      <div class="card-hd"><h3>最近留痕</h3></div>
      <div class="card-bd">
        <el-table :data="data.recent || []" border size="small">
          <el-table-column prop="createdAt" label="时间" width="100" />
          <el-table-column prop="sceneText" label="场景" width="110" />
          <el-table-column prop="refNo" label="关联单号" width="160">
            <template #default="{ row }"><span class="mono">{{ row.refNo || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="model" label="模型" width="150" />
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">{{ row.costMs === null ? '—' : row.costMs + ' ms' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.errorMessage" size="small" type="danger" effect="plain">失败</el-tag>
              <el-tag v-else-if="row.fallback === 1" size="small" type="warning" effect="plain">降级</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">成功</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="采纳" width="80" align="center">
            <template #default="{ row }">
              <span v-if="row.accepted === 1">是</span>
              <span v-else-if="row.accepted === 0">否</span>
              <span v-else class="weak">—</span>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="200">
            <template #default="{ row }">
              <span v-if="row.errorMessage" style="color:var(--c-red)">{{ row.errorMessage }}</span>
              <span v-else class="weak">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="muted mt-12" v-if="!(data.recent || []).length">
          {{ data.logEnabled ? '这段时间没有 AI 调用记录' : '留痕开关关着，不会有任何记录' }}
        </div>
      </div>
    </div>

    <div class="card mt-16">
      <div class="card-hd">
        <h3>最常被改的字段</h3>
        <span class="weak">系统给的值被人改掉了几次，改得越多越该回去改那句 prompt</span>
      </div>
      <div class="card-bd">
        <el-table :data="data.topCorrected || []" border size="small">
          <el-table-column prop="fieldText" label="字段" width="160" />
          <el-table-column prop="field" label="字段名" width="160">
            <template #default="{ row }"><span class="mono">{{ row.field }}</span></template>
          </el-table-column>
          <el-table-column label="被改次数" width="120" align="right">
            <template #default="{ row }">{{ row.cnt }}</template>
          </el-table-column>
          <el-table-column label="占比" min-width="200">
            <template #default="{ row }">
              <div class="bar-wrap">
                <div class="bar" :style="{ width: barWidth(row.cnt) }" />
                <span class="bar-text">{{ pct(row.cnt, correctedSum, 0) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="muted mt-12" v-if="!(data.topCorrected || []).length">
          这段时间没有人工修正记录。
          票据走 OCR 代填、或者填单页用过 AI 建议，这里才会有数据。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiStatus } from '@/api'

const loading = ref(false)
const days = ref(7)
const data = ref({})

/**
 * 百分比。分子为 0 或分母为 0 时给「—」而不是 0%：
 * 没跑过和「跑了但全失败」是两回事，用 0% 会把前者伪装成后者。
 */
function pct (num, den, digits = 0) {
  if (num === null || num === undefined || !den) return '—'
  const v = (Number(num) / Number(den)) * 100
  return v.toFixed(digits) + '%'
}

/** 修正次数合计，给占比条当分母 */
const correctedSum = computed(() =>
  (data.value.topCorrected || []).reduce((s, r) => s + (r.cnt || 0), 0))

/** 条长按最多的那个字段拉满，看得出谁最突出 */
function barWidth (cnt) {
  const max = Math.max(1, ...(data.value.topCorrected || []).map((r) => r.cnt || 0))
  return Math.round(((cnt || 0) / max) * 100) + '%'
}

async function load () {
  loading.value = true
  try {
    data.value = await aiStatus(days.value) || {}
  } catch (e) {
    ElMessage.error('没能取到 AI 运行状况')
  } finally {
    loading.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'AdmAiStatus' })

/** 数据实时聚合而来，回到页面要重拉一次 */
onActivated(load)
</script>

<style scoped>
.stat-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}
.stat-cell {
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: 10px 12px;
}
.stat-label { font-size: 12px; color: var(--c-text-weak); }
.stat-value { font-size: 20px; font-weight: 600; margin-top: 2px; }
.stat-sub { font-size: 11px; color: var(--c-text-weak); margin-top: 2px; }
.bar-wrap { display: flex; align-items: center; gap: 8px; }
.bar {
  height: 8px;
  border-radius: 4px;
  background: var(--c-amber);
  max-width: 260px;
}
.bar-text { font-size: 11.5px; color: var(--c-text-weak); }
</style>
