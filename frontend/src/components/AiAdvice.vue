<template>
  <div class="card ai-card">
    <div class="card-hd">
      <h3>
        <span class="ai-mark">AI</span>
        {{ title }}
      </h3>
      <div class="hd-right">
        <!-- 后端 AI 还没接，如实标注来源，别让人以为是模型真实输出。
             还没出结论时不标：那会儿既不是本地也不是模型，标了就是编的 -->
        <template v-if="sourceTag">
          <el-tag v-if="local" size="small" type="warning" effect="plain">本地演示</el-tag>
          <el-tag v-else size="small" type="success" effect="plain">模型输出</el-tag>
        </template>
        <slot name="extra" />
      </div>
    </div>
    <div class="card-bd">
      <el-skeleton v-if="loading" :rows="2" animated />

      <template v-else-if="points.length">
        <ul class="ai-list">
          <!-- 来源只在鼠标停上去时才说：印成一行小字就是讲解型文案，看了也不知道该怎么办 -->
          <li
            v-for="(p, i) in points" :key="i" class="ai-item" :class="'lv-' + (p.level || 'low')"
            :title="sourceTitle(p)"
          >
            <!-- 只有要紧和留意挂标签，低优先级的自己就是白底，再挂个标签反而显重 -->
            <div v-if="p.level === 'high' || p.level === 'mid'" class="ai-tag">
              {{ p.level === 'high' ? '要紧' : '留意' }}
            </div>
            <div class="ai-text">{{ p.text }}</div>
          </li>
        </ul>
        <div v-if="summary" class="ai-summary">{{ summary }}</div>
      </template>

      <!-- emptyText 传空串就不渲染：有的卡（按次触发的解释）没出结果时宁愿空着，也不放引导文案 -->
      <div v-else-if="emptyText" class="tip-box tip-info">{{ emptyText }}</div>

      <!-- 建议列表之外的正文，比如「AI 改写的事由」这种要单独成块的产出 -->
      <slot name="body" />

      <!-- 操作区：采用建议 / 重新生成之类，交给调用方 -->
      <div v-if="$slots.default" class="ai-actions">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AI 建议卡。审批页、填写页、票据页共用一张壳，
 * 目的不是省代码，是让所有 AI 产出在页面上长得一样：带 AI 角标、写明来源、可忽略。
 *
 * <p><b>不展示 evidence 那一行。</b>后端每条结论仍然带 field/value 出处（那是防编造的校验依据，
 * 没有它在模型侧就留不住），但页面上不再把它印成一行 11px 的「依据 · reason = ……」——
 * 审批人看的是结论本身，出处写给他等于让他自己去核对。真要问「凭什么」时，
 * 具体的数字口径已经写在建议正文里了。
 */
defineProps({
  title: { type: String, default: 'AI 建议' },
  /** [{ level: 'high'|'mid'|'low', text }]。evidence 后端照旧返回，页面不用它 */
  points: { type: Array, default: () => [] },
  summary: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无建议' },
  /** true = 本地模拟，页面会提示来源 */
  local: { type: Boolean, default: true },
  /** false = 连来源角标也不显示。按次触发的解释在出结论之前用，那时它还什么都不是 */
  sourceTag: { type: Boolean, default: true }
})

/**
 * 这条是哪来的：MODEL 模型归纳，RULE 统计规则直接算出。
 *
 * 一份结论常常是混的——模型给几条、规则补几条，而「整份报告一个布尔」的说法
 * 到逐条这一层就不够用了。但把它印成一行小字又是讲解型文案，
 * 所以只在鼠标停上去时才说：想知道的人能看到，不想知道的人不受打扰。
 */
function sourceTitle (p) {
  if (p.source === 'RULE') return '这条由统计规则直接算出'
  if (p.source === 'MODEL') return '这条由模型归纳'
  return ''
}
</script>

<style scoped>
.ai-card { border-color: #d9e3ef; }

.ai-mark {
  display: inline-block;
  background: var(--c-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  border-radius: 4px;
  padding: 0 6px;
  margin-right: 7px;
  letter-spacing: .5px;
}

.hd-right { display: flex; align-items: center; gap: 8px; }

/* 每条建议独立成块：左侧色条 + 浅底，轻重靠块面而不是一个 8px 的圆点 */
.ai-list { list-style: none; margin: 0; padding: 0; }
.ai-item {
  padding: 8px 11px;
  border-radius: 6px;
  border-left: 3px solid var(--c-border);
  background: #f7f9fc;
}
.ai-item + .ai-item { margin-top: 8px; }

.ai-item.lv-high { background: var(--c-red-soft); border-left-color: var(--c-red); }
.ai-item.lv-mid { background: var(--c-amber-soft); border-left-color: var(--c-amber); }
.ai-item.lv-low { background: #f7f9fc; border-left-color: var(--c-border); }

.ai-tag {
  display: inline-block;
  font-size: 11px;
  line-height: 17px;
  padding: 0 6px;
  margin-bottom: 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, .72);
}
.lv-high .ai-tag { color: var(--c-red); }
.lv-mid .ai-tag { color: var(--c-amber); }

.ai-text { font-size: 12.5px; line-height: 1.7; }

.ai-summary {
  margin-top: 10px;
  padding: 7px 11px;
  border-radius: 6px;
  background: var(--c-primary-soft);
  border-left: 3px solid var(--c-primary);
  font-size: 12.5px;
}

.ai-actions { margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
</style>
