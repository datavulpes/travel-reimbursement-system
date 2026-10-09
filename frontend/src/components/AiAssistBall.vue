<template>
  <!--
    可拖动的 AI 助手入口。员工在填单、报销这类页面上临时想问一句规定，
    不用离开当前页面去翻菜单——拖到顺手的位置，点一下右侧就滑出助手面板。

    拖动与点击用同一组 pointer 事件区分：位移超过 4px 才算拖动，
    否则按点击处理。分开监听 click 会让人「拖完松手又弹出面板」。
  -->
  <div
    v-show="visible"
    class="ai-ball"
    :style="{ left: x + 'px', top: y + 'px' }"
    role="button"
    aria-label="打开 AI 助手"
    @pointerdown="onDown"
    @pointermove="onMove"
    @pointerup="onUp"
    @pointercancel="onUp"
  >
    <span class="ai-ball-txt">AI</span>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'

defineOptions({ name: 'AiAssistBall' })

const props = defineProps({
  visible: { type: Boolean, default: true }
})

const emit = defineEmits(['open'])

const SIZE = 54
/** 位置记在浏览器本地：这是「顺手放哪」的偏好，不是业务数据，不值得占后端一张表 */
const POS_KEY = 'aiBallPos'

const x = ref(0)
const y = ref(0)

let dragging = false
let moved = false
let startX = 0
let startY = 0
let originX = 0
let originY = 0

/** 别拖出视口，也别压住页头 */
function clampX (v) {
  const max = Math.max(8, window.innerWidth - SIZE - 8)
  return Math.min(Math.max(v, 8), max)
}
function clampY (v) {
  const min = 70
  const max = Math.max(min, window.innerHeight - SIZE - 8)
  return Math.min(Math.max(v, min), max)
}

onMounted(() => {
  let px = null
  let py = null
  const saved = localStorage.getItem(POS_KEY)
  if (saved) {
    try {
      const p = JSON.parse(saved)
      px = p.x
      py = p.y
    } catch (e) {
      // 存的东西坏了就当没存过，回到默认位置
    }
  }
  if (px == null || py == null) {
    // 默认落在面板左侧：面板占右侧 380px，球压在上面会挡住答句
    px = window.innerWidth - SIZE - 420
    py = window.innerHeight - SIZE - 40
  }
  x.value = clampX(px)
  y.value = clampY(py)
})

// 窗口变小之后，原来贴着右边缘的球可能被挤到看不见，收回来
watch(() => props.visible, (v) => {
  if (!v) return
  x.value = clampX(x.value)
  y.value = clampY(y.value)
})

function onDown (e) {
  dragging = true
  moved = false
  startX = e.clientX
  startY = e.clientY
  originX = x.value
  originY = y.value
  // 抓住指针：拖到页面其他地方（比如输入框上方）也不会丢掉后续事件
  if (e.currentTarget.setPointerCapture) {
    e.currentTarget.setPointerCapture(e.pointerId)
  }
}

function onMove (e) {
  if (!dragging) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  x.value = clampX(originX + dx)
  y.value = clampY(originY + dy)
}

function onUp () {
  if (!dragging) return
  dragging = false
  localStorage.setItem(POS_KEY, JSON.stringify({ x: x.value, y: y.value }))
  if (!moved) emit('open')
}
</script>

<style scoped>
.ai-ball {
  position: fixed;
  z-index: 2100;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  background: var(--c-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  box-shadow: 0 6px 18px rgba(0, 0, 0, .18);
  user-select: none;
  touch-action: none;
}
.ai-ball:active { cursor: grabbing; }
.ai-ball-txt {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: .5px;
}
</style>
