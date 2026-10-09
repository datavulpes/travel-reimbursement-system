<template>
  <div v-if="drivers.length || note">
    <div v-if="drivers.length" class="ai-drv-box">
      <div class="ai-drv-hd">
        费效比环比归因
        <span class="weak">较 {{ prevMonth || '上月' }}</span>
      </div>
      <div v-for="d in drivers" :key="d.dimension + '-' + d.name" class="ai-drv">
        <span
          class="ai-drv-tag"
          :style="{ color: worse(d) ? 'var(--c-red)' : 'var(--c-green)' }"
        >{{ worse(d) ? '推高 ' : '拉低 ' }}{{ ratio(Math.abs(d.contribution)) }}</span>
        <span class="ai-drv-text">{{ d.text }}</span>
      </div>
    </div>
    <div v-if="note" class="tip-box tip-warn mt-12">{{ note }}</div>
  </div>
</template>

<script setup>
/**
 * 费效比归因分解块。
 *
 * 单独成件的原因跟 AiFieldList 一样：它挂在新加的接口上，只有拿到数据才渲染，
 * 模板里引用错一个变量，构建期毫无反应，页面却会卡住。抽出来就能单独真渲染一遍。
 *
 * 另一个原因是观感：这块内容虽然挂在 AI 卡片里，但它是算出来的，不是模型归纳的，
 * 自成一块才不会被当成「AI 说的」——两者的可信度不是一回事。
 */
import { ratio } from '@/utils/dict'

defineProps({
  /** 后端 MonthlyInsightVO.drivers：维度 + 名称 + 贡献值 + 一句话说明 */
  drivers: { type: Array, default: () => [] },
  prevMonth: { type: String, default: '' },
  /** 样本不足 / 回填率偏低的提醒 */
  note: { type: String, default: '' }
})

/** 费效比是「越低越划算」，贡献为正就是把比值推高，即变差 */
const worse = (d) => Number(d.contribution) >= 0
</script>

<style scoped>
.ai-drv-box {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--c-primary-soft);
  border-left: 3px solid var(--c-primary);
}
.ai-drv-hd { font-size: 12.5px; font-weight: 600; margin-bottom: 6px; }
.ai-drv {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: 12.5px;
}
.ai-drv-tag { flex: none; font-weight: 600; font-size: 12px; min-width: 62px; }
.ai-drv-text { color: var(--c-text-sub); }
</style>
