<template>
  <!--
    AI 助手侧边栏。员工端四个页面（填单 / 我的申请 / 票据 / 报销）各有一份，
    浮在右侧盖住原来的右栏模块，收起后右栏原样还在。

    「各页各存一份」是这里最要紧的一条：填单时问的是差标，报销时问的是票据要求，
    合成一份会互相覆盖，表现为「在 A 页问完，切到 B 页看到的是 A 的答案」。
    所以按 pageKey 分开存，切换页面时先把当前这页存掉，再去取新页面的那份。
  -->
  <aside v-show="visible" class="ai-panel">
    <div class="ap-hd">
      <span class="ap-mark">AI</span>
      <span class="ap-title">AI助手</span>
      <el-button link class="ap-close" @click="close">收起</el-button>
    </div>

    <div class="ap-bd">
      <div class="ap-ask">
        <el-input
          v-model="question"
          maxlength="100"
          clearable
          placeholder="比如：去深圳坐飞机能报多少？超标了怎么提交？"
          @keyup.enter="ask"
        />
        <el-button
          type="primary" size="small" :loading="loading"
          :disabled="!question.trim()" @click="ask"
        >提问</el-button>
      </div>

      <div v-if="result.question" class="ap-answer">
        <div class="ap-line">
          <span class="ap-tag">问</span>
          <span>{{ result.question }}</span>
        </div>
        <div class="ap-line" :class="{ 'is-miss': result.answerable === false }">
          <span class="ap-tag">答</span>
          <span>{{ result.answer }}</span>
        </div>
        <ul v-if="(result.facts || []).length" class="ap-facts">
          <li v-for="(f, i) in result.facts" :key="i">{{ f }}</li>
        </ul>
      </div>
    </div>

    <div v-if="result.question" class="ap-ft">
      <el-button link size="small" @click="clearAll">清空</el-button>
    </div>
  </aside>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { panelClear, panelGet, panelSave, policyQa } from '@/api'

defineOptions({ name: 'AiAssistPanel' })

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 页面键，与后端 AiPanelState.PAGE_* 逐字一致；不在挂球页面上时为 null */
  pageKey: { type: String, default: null }
})

const emit = defineEmits(['update:visible'])

const question = ref('')
const result = reactive({})
const loading = ref(false)

/** 页面键 → 这一页的问答。内存里留一份，避免每次切页都闪一下空面板 */
const store = {}

function snapshot () {
  return { question: question.value, result: { ...result } }
}

function apply (s) {
  question.value = s?.question || ''
  Object.keys(result).forEach((k) => delete result[k])
  if (s?.result) Object.assign(result, s.result)
}

/** 只有真的问出过结果才值得存；一片空白不该留一行空数据等着「恢复」 */
function hasContent (s) {
  return !!(s && s.result && s.result.question)
}

async function flush (key) {
  if (!key) return
  const s = snapshot()
  store[key] = s
  try {
    if (hasContent(s)) await panelSave(key, JSON.stringify(s))
    else await panelClear(key)
  } catch (e) {
    // 存不上不打断人：面板内容不是业务事实，下一次再存即可
  }
}

// 换页：先把当前这页存掉，再取新页面的那份
watch(() => props.pageKey, async (nk, ok) => {
  if (ok) await flush(ok)
  if (!nk) {
    // 离开了挂球的页面：存完就把面板收起来，不留在别人的页面上
    emit('update:visible', false)
    return
  }
  if (store[nk]) {
    apply(store[nk])
    return
  }
  try {
    const vo = await panelGet(nk)
    store[nk] = vo?.content ? JSON.parse(vo.content) : { question: '', result: {} }
  } catch (e) {
    store[nk] = { question: '', result: {} }
  }
  apply(store[nk])
}, { immediate: true })

// 收起即保存
watch(() => props.visible, (v) => {
  if (!v) flush(props.pageKey)
})

async function ask () {
  const q = question.value.trim()
  if (!q) return
  loading.value = true
  try {
    Object.keys(result).forEach((k) => delete result[k])
    Object.assign(result, await policyQa(q))
    // 答完立刻落一次：万一直接关浏览器，这一句也不会丢
    await flush(props.pageKey)
  } catch (e) {
    ElMessage.error('这次没答上来，稍后再试')
  } finally {
    loading.value = false
  }
}

async function clearAll () {
  question.value = ''
  Object.keys(result).forEach((k) => delete result[k])
  store[props.pageKey] = { question: '', result: {} }
  try {
    await panelClear(props.pageKey)
  } catch (e) {
    // 同上，清不掉就算了
  }
}

function close () {
  emit('update:visible', false)
}
</script>

<style scoped>
/* 盖在右栏上：宽度与右栏（el-col span=8）相仿，收起后右栏原样露出 */
.ai-panel {
  position: fixed;
  z-index: 2050;
  top: 74px;
  right: 20px;
  bottom: 20px;
  width: 380px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, .12);
}

.ap-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--c-border);
}
.ap-mark {
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
.ap-title { font-size: 14px; font-weight: 650; }
.ap-close { margin-left: auto; }

.ap-bd {
  flex: 1;
  overflow: auto;
  padding: 12px 14px;
}

.ap-ask { display: flex; gap: 8px; }

.ap-answer { margin-top: 14px; }
.ap-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.75;
}
.ap-line + .ap-line {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}
.ap-line.is-miss { color: var(--c-text-weak); }
.ap-tag {
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

.ap-facts {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--c-text-weak);
  line-height: 1.9;
}

.ap-ft {
  padding: 8px 14px;
  border-top: 1px solid var(--c-border);
  text-align: right;
}
</style>
