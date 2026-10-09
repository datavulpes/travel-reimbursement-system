<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <h3>AI助手</h3>
      </div>

      <div class="card-bd">
        <div class="ask-row">
          <el-input
            v-model="question"
            maxlength="200"
            show-word-limit
            clearable
            placeholder="比如：9 月技术部费效比多少？哪个客户最不划算？张三 9 月出差几次？"
            @keyup.enter="ask"
          />
          <el-button type="primary" :loading="loading" :disabled="!question.trim()" @click="ask">
            问一句
          </el-button>
        </div>

        <div class="quick-row">
          <span class="quick-label">试试：</span>
          <el-tag
            v-for="q in samples" :key="q" class="quick-tag" size="small" effect="plain"
            @click="question = q; ask()"
          >{{ q }}</el-tag>
        </div>

        <div v-if="result.question" class="answer-box mt-16">
          <div class="ask-echo">
            <span class="ask-mark">问</span>
            <span>{{ result.question }}</span>
          </div>

          <div class="answer-line" :class="{ 'is-miss': result.answerable === false }">
            <span class="ask-mark" :class="{ 'is-miss': result.answerable === false }">答</span>
            <span>{{ result.answer }}</span>
          </div>

          <template v-if="scalarRows.length">
            <div class="section-title mt-16">数据明细</div>
            <div class="kv-grid">
              <div v-for="(r, i) in scalarRows" :key="i" class="kv-cell">
                <span class="kv-k">{{ r.label }}</span>
                <span class="kv-v">{{ r.value }}</span>
              </div>
            </div>
          </template>

          <el-table
            v-if="tableRows.length" :data="tableRows" size="small" class="mt-16" border
          >
            <el-table-column
              v-for="col in tableCols" :key="col" :prop="col" :label="LABELS[col] || col"
            />
          </el-table>

          <template v-if="statusRows.length">
            <div class="section-title mt-16">按状态分布</div>
            <ul class="fact-list">
              <li v-for="(s, i) in statusRows" :key="i">{{ s[0] }}：{{ s[1] }} 单</li>
            </ul>
          </template>

        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dataQa } from '@/api'

const question = ref('')
const loading = ref(false)
const result = reactive({})

const samples = [
  '9 月费效比多少',
  '哪个客户最不划算',
  '我 9 月出差几次',
  '技术部 9 月预算还剩多少',
  '各部门 9 月预算还剩多少',
  '有哪些单超时没批'
]

const LABELS = {
  month: '月份', scope: '范围', totalPay: '投入', totalOutput: '产出',
  ratio: '费效比', attentionRatio: '可疑占比', fillRate: '回填率',
  applyCnt: '出差单数', paidCnt: '已付款单数', deptName: '部门', empName: '员工',
  totalEst: '预估合计', monthlyBudget: '月度预算', used: '已使用', left: '余额',
  totalBudget: '总预算', totalUsed: '总使用', totalLeft: '总余额',
  count: '在审单数', overdueCount: '超时单数', totalInvest: '总投入',
  partnerName: '对象', tripCnt: '出差次数', applyNo: '单号', reason: '事由',
  status: '状态', waitedHours: '已等待(小时)', overdueHours: '超时(小时)', overdue: '是否超时'
}

// 顶层标量字段（排除数组与对象），渲染成「键：值」网格
const scalarRows = computed(() => {
  const d = result.data
  if (!d) return []
  const out = []
  for (const [k, v] of Object.entries(d)) {
    if (k === 'rows' || k === 'items' || k === 'byStatus' || k === 'noSpend' || k === 'scope') continue
    if (v === null || v === undefined) continue
    out.push({ label: LABELS[k] || k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) })
  }
  return out
})

// 数组字段（rows / items）渲染成表格
const tableRows = computed(() => {
  const d = result.data
  if (!d) return []
  return d.rows || d.items || []
})

const tableCols = computed(() => {
  if (!tableRows.value.length) return []
  return Object.keys(tableRows.value[0])
})

const statusRows = computed(() => {
  const d = result.data
  if (!d || !d.byStatus) return []
  return Object.entries(d.byStatus)
})

async function ask () {
  const q = question.value.trim()
  if (!q) return
  loading.value = true
  try {
    Object.assign(result, await dataQa(q))
  } catch (e) {
    ElMessage.error('这次没答上来，稍后再试')
  } finally {
    loading.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字。
// 问数结果要留着：切走看一眼月报再回来，刚才那句数字不该没了。
defineOptions({ name: 'MgrDataQa' })

/** 回到页面不重拉：问答是一次性的，重拉会把上一条结果清空 */
onActivated(() => {})
</script>

<style scoped>
.ask-row {
  display: flex;
  gap: 10px;
  align-items: center;
}
.quick-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.quick-label {
  font-size: 12.5px;
  color: var(--c-text-weak);
}
.quick-tag {
  cursor: pointer;
}
.answer-box {
  padding: 14px 16px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: #fbfcfe;
}
.ask-echo {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: var(--c-text-weak);
}
.answer-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--c-border);
  font-size: 14px;
  line-height: 1.7;
}
.answer-line.is-miss { color: var(--c-text-weak); }
.ask-mark {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: 11px;
  line-height: 20px;
  text-align: center;
}
.ask-mark.is-miss {
  background: #f0f2f5;
  color: var(--c-text-weak);
}
.section-title {
  font-size: 12.5px;
  color: var(--c-text-weak);
}
.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 8px 16px;
  margin-top: 8px;
}
.kv-cell {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: #fff;
}
.kv-k {
  font-size: 12.5px;
  color: var(--c-text-weak);
}
.kv-v {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.fact-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--c-text-weak);
  line-height: 1.9;
}
</style>
