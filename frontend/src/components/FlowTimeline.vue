<template>
  <div class="flow">
    <!-- 一张记录都没有时给说法，别留个空框让人以为没加载出来 -->
    <div v-if="!items.length" class="tip-box tip-info">{{ emptyText }}</div>

    <template v-else>
      <div class="flow-bar">
        <span class="muted">{{ items.length }} 个节点</span>
        <span class="flow-bar-right">
          <el-button link size="small" @click="desc = !desc">
            {{ desc ? '最新在前' : '按流程顺序' }}
          </el-button>
          <el-button v-if="snapKeys.length" link size="small" @click="toggleAll">
            {{ allOpen ? '收起依据' : '展开依据' }}
          </el-button>
        </span>
      </div>

      <ol class="flow-list">
        <li
          v-for="r in shown" :key="r.key"
          class="flow-item" :class="'node-' + r.tone"
        >
          <div class="flow-dot">{{ r.seq }}</div>
          <div class="flow-main">
            <div class="flow-hd">
              <span class="flow-step">{{ r.stepText }}</span>
              <el-tag :type="r.resultType" size="small" effect="dark">{{ r.resultText }}</el-tag>
              <span class="flow-time mono" :title="r.approvedAt">{{ r.shortTime }}</span>
            </div>

            <div class="flow-meta">
              <span>{{ r.approver || '—' }}</span>
              <span v-if="r.fromNow">· {{ r.fromNow }}</span>
              <!-- 「等了多久」比两个时间戳更容易判断卡在谁那儿 -->
              <span v-if="r.wait" class="flow-wait">这一步等了 {{ r.wait }}</span>
            </div>

            <div
              v-if="r.opinion"
              class="flow-opinion"
              :class="{ 'is-reject': r.result === 'REJECTED' }"
            >
              {{ r.opinion }}
            </div>
            <div v-else class="flow-opinion is-empty">未填写意见</div>

            <!-- 依据快照：后端存的是一整坨 JSON，这里拆成几行给人看 -->
            <div v-if="r.raw" class="flow-snap">
              <div class="snap-tog" @click="toggle(r.key)">
                <span class="caret">{{ open[r.key] ? '▾' : '▸' }}</span>
                {{ r.rows ? '当时的依据' : '原始快照' }}
              </div>
              <div v-show="open[r.key]" class="snap-body">
                <div v-if="r.rows" class="snap-grid">
                  <div v-for="(row, k) in r.rows" :key="k" class="snap-cell">
                    <div class="snap-label">{{ row.label }}</div>
                    <div class="snap-value">
                      <el-tag v-if="row.tag" :type="row.tag.type" size="small" effect="dark">
                        {{ row.tag.text }}
                      </el-tag>
                      <span v-else>{{ row.value }}</span>
                    </div>
                    <div v-if="row.sub" class="snap-sub">{{ row.sub }}</div>
                  </div>
                </div>
                <div v-else class="snap-raw mono">{{ r.raw }}</div>
              </div>
            </div>
          </div>
        </li>

        <!-- 单子还卡在某个环节时补一个虚节点，一眼看出球在谁脚下 -->
        <li v-if="pendingText" class="flow-item is-pending">
          <div class="flow-dot">···</div>
          <div class="flow-main">
            <div class="flow-hd"><span class="flow-step">{{ pendingText }}</span></div>
            <div class="flow-meta">还没人处理</div>
          </div>
        </li>
      </ol>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  APPROVAL_STEP, APPROVAL_RESULT, STANDARD_RESULT, EVAL_LEVEL, money
} from '@/utils/dict'

const props = defineProps({
  /** 后端 ApplyDetailVO.FlowRecordVO 列表，按 id 升序（提交在前） */
  records: { type: Array, default: () => [] },
  /** 单据当前状态，用来补「待处理」节点 */
  status: { type: String, default: '' },
  emptyText: { type: String, default: '暂无流转记录。' }
})

const desc = ref(false)
const open = reactive({})

// 换一张单时把展开状态清掉，不然上一张的展开位置会串到下一张
watch(() => props.records, () => { Object.keys(open).forEach((k) => delete open[k]) })

// 关闭节点已经在 APPROVAL_STEP 里给中文名了，这里只为向后兼容留着 SUBMIT 的老写法。
// stepText 统一走字典，加新环节只改 dict.js 一处。
const STEP_TEXT = APPROVAL_STEP
const PENDING_STATUS = { DEPT_REVIEW: '待部门初审', FINAL_REVIEW: '待管理层复审' }

/** 后端给的是 yyyy-MM-dd HH:mm:ss，Safari 认不了带空格的，先换成斜杠 */
function toDate (s) {
  if (!s) return null
  const t = new Date(String(s).replace('T', ' ').replace(/-/g, '/'))
  return isNaN(t.getTime()) ? null : t
}

/** 时长说人话：「2 天 3 小时」比「2950 分钟」好读 */
function gapText (ms) {
  if (ms == null || ms < 60000) return '不到 1 分钟'
  const m = Math.floor(ms / 60000)
  if (m < 60) return `${m} 分钟`
  const h = Math.floor(m / 60)
  if (h < 24) return h + (m % 60 ? ` 小时 ${m % 60} 分钟` : ' 小时')
  const d = Math.floor(h / 24)
  return d + (h % 24 ? ` 天 ${h % 24} 小时` : ' 天')
}

/**
 * 把依据快照拆成几行。解析不了就整段原文摆出来——
 * 快照是存档数据，宁可难看也不能丢。
 */
function snapshotRows (raw) {
  if (!raw) return null
  let o = null
  try {
    o = JSON.parse(raw)
  } catch {
    return null
  }
  if (!o || typeof o !== 'object') return null

  const rows = []
  const ref = o.costRef
  if (ref) {
    rows.push({
      label: '同类花费参考',
      value: `${ref.toCity || '—'} · ${ref.tripType || '—'} 均值 ¥${money(ref.avgAmount)}`,
      sub: `区间 ¥${money(ref.minAmount)} ~ ¥${money(ref.maxAmount)} · 样本 ${ref.sampleCnt ?? '—'} 单`
    })
  }
  if (o.estTotal != null) {
    rows.push({ label: '本次预估合计', value: `¥ ${money(o.estTotal)}` })
  }
  if (o.standardResult) {
    rows.push({
      label: '差标结论',
      tag: STANDARD_RESULT[o.standardResult] || { text: o.standardResult, type: 'info' }
    })
  }
  if (o.exceedReason) {
    rows.push({ label: '超标说明', value: o.exceedReason })
  }
  if (o.eval) {
    rows.push({
      label: '合理性评价',
      tag: EVAL_LEVEL[o.eval.level] || { text: o.eval.level || '—', type: 'info' },
      sub: o.eval.totalScore != null ? `加权总分 ${o.eval.totalScore}` : ''
    })
  }
  return rows.length ? rows : null
}

const items = computed(() => {
  const src = props.records || []
  return src.map((r, i) => {
    const time = toDate(r.approvedAt)
    const prev = i > 0 ? toDate(src[i - 1].approvedAt) : null
    const now = Date.now()
    // 提交那条记录的 result 也是 APPROVED，但它是「发起」不是「通过」，按 step 分开说
    const isSubmit = r.step === 'SUBMIT'
    const isClose = r.step === 'CLOSE'
    return {
      key: i,
      seq: i + 1,
      ...r,
      stepText: STEP_TEXT[r.step] || r.step || '流转',
      resultText: isSubmit ? '已提交' : (APPROVAL_RESULT[r.result]?.text || '已处理'),
      resultType: isSubmit ? 'info' : (APPROVAL_RESULT[r.result]?.type || 'info'),
      tone: isSubmit ? 'submit' : isClose ? 'close' : (r.result === 'REJECTED' ? 'reject' : 'pass'),
      shortTime: time
        ? String(r.approvedAt).slice(5, 16)
        : (r.approvedAt || ''),
      fromNow: time ? gapText(now - time.getTime()) + '前' : '',
      // 第一条是发起，谈不上等；之后每一步都算「从上一步到这儿」的停留
      wait: time && prev ? gapText(time.getTime() - prev.getTime()) : '',
      raw: r.snapshot || '',
      rows: snapshotRows(r.snapshot)
    }
  })
})

const shown = computed(() => (desc.value ? [...items.value].reverse() : items.value))

const pendingText = computed(() => PENDING_STATUS[props.status] || '')

const snapKeys = computed(() => items.value.filter((r) => r.raw).map((r) => r.key))
const allOpen = computed(() => snapKeys.value.length > 0 && snapKeys.value.every((k) => open[k]))

function toggle (key) {
  open[key] = !open[key]
}

function toggleAll () {
  const next = !allOpen.value
  snapKeys.value.forEach((k) => { open[k] = next })
}
</script>

<style scoped>
.flow-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  padding-bottom: 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--c-border);
}
.flow-bar-right { display: flex; align-items: center; gap: 2px; }

.flow-list { list-style: none; margin: 0; padding: 8px 0 0; }

.flow-item { position: relative; padding: 0 0 18px 30px; }
/* 节点之间的竖线，最后一条不需要往下连 */
.flow-item::before {
  content: '';
  position: absolute;
  left: 9px; top: 20px; bottom: 2px;
  width: 1px;
  background: var(--c-border);
}
.flow-item:last-child::before { display: none; }

.flow-dot {
  position: absolute;
  left: 0; top: 2px;
  width: 19px; height: 19px;
  border-radius: 50%;
  background: var(--c-text-weak);
  color: #fff;
  font-size: 11px;
  line-height: 19px;
  text-align: center;
}
.node-pass .flow-dot { background: var(--c-green); }
.node-reject .flow-dot { background: var(--c-red); }
.node-submit .flow-dot { background: var(--c-primary); }
/* 关闭是归档不是结论，用弱色的点，别跟「通过」混成一种颜色 */
.node-close .flow-dot { background: var(--c-text-weak); }
.is-pending .flow-dot {
  background: #fff;
  border: 1px dashed var(--c-text-weak);
  color: var(--c-text-weak);
  font-size: 10px;
}

.flow-hd {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.flow-step { font-weight: 600; font-size: 13.5px; }
.flow-time { color: var(--c-text-weak); }

.flow-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12.5px;
  color: var(--c-text-sub);
  margin-top: 2px;
}
.flow-wait {
  background: var(--c-amber-soft);
  color: #8a5a12;
  border-radius: 4px;
  padding: 0 6px;
}

.flow-opinion {
  margin-top: 7px;
  padding: 7px 10px;
  font-size: 12.5px;
  line-height: 1.7;
  background: #f7f9fc;
  border: 1px solid var(--c-border);
  border-left: 3px solid var(--c-text-weak);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
}
.flow-opinion.is-reject {
  background: var(--c-red-soft);
  border-color: #f0d6d2;
  border-left-color: var(--c-red);
  color: #96271b;
}
.flow-opinion.is-empty { color: var(--c-text-weak); }

.flow-snap { margin-top: 7px; }
.snap-tog {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--c-primary);
  cursor: pointer;
  user-select: none;
}
.snap-tog:hover { text-decoration: underline; }
.caret { font-size: 10px; }

.snap-body { margin-top: 6px; }
.snap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}
.snap-cell {
  background: #f7f9fc;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: 6px 9px;
}
.snap-label { font-size: 11.5px; color: var(--c-text-weak); }
.snap-value { font-size: 12.5px; margin-top: 2px; }
.snap-sub { font-size: 11px; color: var(--c-text-weak); margin-top: 2px; }

/* 解析不了的老快照原样摆出来，宁可难看也不能丢存档 */
.snap-raw {
  background: #f7f9fc;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: 6px 9px;
  color: var(--c-text-sub);
  word-break: break-all;
}
</style>
