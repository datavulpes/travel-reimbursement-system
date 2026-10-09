<template>
  <div class="ai-field-list">
    <div class="af-hd">
      <span>可填充 {{ active.length }} 项 · 已选 {{ selectedCount }}</span>
      <el-link type="primary" underline="never" @click="toggleAll">
        {{ allOn ? '全部取消' : '全选' }}
      </el-link>
    </div>

    <!-- 填完又改了内容，这批建议就是上一版算出来的，先说清再让人决定 -->
    <div v-if="stale" class="af-stale">
      表单改过了，这些建议对应的是上一版内容，重新分析一次再填
    </div>

    <div v-if="!fields.length" class="af-empty">
      这版没有可自动填充的项。缺什么会写在上半部分的提示里，那些要自己填。
    </div>

    <div
      v-for="f in fields" :key="f.field"
      class="af-item"
      :class="{
        'is-on': !!modelValue[f.field],
        'is-stale': stale,
        'is-filled': isFilled(f.field)
      }"
    >
      <div class="af-top">
        <!-- 不走 v-model：勾选要 emit 一份新对象，就地改 props 里的对象父组件收不到通知 -->
        <el-checkbox
          :model-value="!!modelValue[f.field]"
          :disabled="isFilled(f.field)"
          @change="(on) => toggle(f.field, on)"
        />
        <span class="af-label">{{ f.label }}</span>
        <el-tag v-if="isFilled(f.field)" size="small" type="success" effect="plain">已填入</el-tag>
        <el-tag v-else-if="f.overwrite" size="small" type="warning" effect="plain">已填，会覆盖</el-tag>
        <el-tag v-else size="small" type="success" effect="plain">空白</el-tag>
      </div>

      <!-- 事由是长文本，单独一块底衬，跟数字项区分开 -->
      <div v-if="f.field === 'reason'" class="af-text">
        <div class="af-text-label">
          {{ stale ? '这版改写对应的是上一版事由，重新分析一次' : '建议改成' }}
        </div>
        {{ f.suggestValue }}
      </div>
      <div v-else class="af-val">
        <span v-if="f.currentValue" class="af-old">当前 {{ f.currentValue }}</span>
        <span class="af-new">{{ f.suggestValue }}</span>
      </div>

      <!--
        来源不再单列一行。后端每条建议的 source / sourceText 仍然照旧返回（那是防编造的锚，
        去掉后端就无从校验），但页面上那行 11px 的「依据 · 同类花费｜近 12 个月 12 单均值 ¥2400」
        被撤了：金额的出处已经写在 reason 里（「同类均值 ¥2600，3 天 2 晚，住宿约 ¥1700」），
        再挂一行出处等于把同一件事说两遍，反而把真正的理由挤淡了。
      -->
      <div class="af-reason">{{ f.reason }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * AI 可填充项清单。
 *
 * <p>只做展示与勾选，不碰表单：填什么、怎么填由调用方决定。
 * 这样它才能被放进抽屉、卡片或者别的页面而不带一堆上下文。
 *
 * <p>两个刻意的交互：
 * <ul>
 *   <li><b>已填的项默认不勾</b>（overwrite=true 由后端标记）。AI 悄悄改掉人填好的东西最招人烦，
 *       要覆盖得由人自己点上这一条。</li>
 *   <li><b>填过的项锁住并标「已填入」</b>。不锁的话，填完再点一次「填充选中」会把剩下的项
 *       又写一遍，而撤销只按快照还原一次，两次填充对一次撤销就不一致了。</li>
 * </ul>
 */
const props = defineProps({
  fields: { type: Array, default: () => [] },
  /** { [field]: true|false }，勾选状态由调用方持有 */
  modelValue: { type: Object, default: () => ({}) },
  /** 表单改过，这批建议已经过期 */
  stale: { type: Boolean, default: false },
  /** 本次已经填进表单的 field 名 */
  filled: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue'])

/** 还没填进去的项才算「可填充」，填过的锁住 */
const active = computed(() => props.fields.filter((f) => !props.filled.includes(f.field)))
const selectedCount = computed(() => active.value.filter((f) => props.modelValue[f.field]).length)
const allOn = computed(() => active.value.length > 0 && selectedCount.value === active.value.length)

function isFilled (field) {
  return props.filled.includes(field)
}

function toggle (field, on) {
  emit('update:modelValue', { ...props.modelValue, [field]: on })
}

function toggleAll () {
  const on = !allOn.value
  const next = { ...props.modelValue }
  active.value.forEach((f) => { next[f.field] = on })
  emit('update:modelValue', next)
}
</script>

<style scoped>
.af-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--c-text-weak);
  margin-bottom: 8px;
}

.af-stale {
  padding: 6px 10px;
  margin-bottom: 8px;
  border-radius: 5px;
  border-left: 3px solid var(--c-amber);
  background: var(--c-amber-soft);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--c-text-sub);
}

.af-empty { font-size: 12px; color: var(--c-text-weak); line-height: 1.7; }

/* 每条独立成块，和 AiAdvice 的要点卡同一套观感：左色条 + 浅底 */
.af-item {
  padding: 8px 11px;
  border-radius: 6px;
  border-left: 3px solid var(--c-border);
  background: #f7f9fc;
}
.af-item + .af-item { margin-top: 8px; }
.af-item.is-on { background: var(--c-primary-soft); border-left-color: var(--c-primary); }
.af-item.is-stale { background: var(--c-amber-soft); border-left-color: var(--c-amber); }
.af-item.is-filled { opacity: .62; }

.af-top { display: flex; align-items: center; gap: 8px; }
.af-label { font-size: 12.5px; font-weight: 600; }

.af-val { display: flex; align-items: baseline; gap: 10px; margin: 4px 0 0 22px; }
.af-old { font-size: 11.5px; color: var(--c-text-weak); text-decoration: line-through; }
.af-new { font-size: 14px; font-weight: 650; color: var(--c-primary); }

.af-text {
  margin: 6px 0 0 22px;
  padding: 6px 9px;
  border-radius: 5px;
  background: var(--c-green-soft);
  font-size: 12.5px;
  line-height: 1.7;
}
.af-text-label { font-size: 11px; color: var(--c-text-weak); margin-bottom: 2px; }

.af-reason { font-size: 12px; line-height: 1.65; color: var(--c-text-sub); margin: 5px 0 0 22px; }
</style>
