<template>
  <div class="draft-field">
    <el-input
      :model-value="modelValue"
      :type="multiline ? 'textarea' : 'text'"
      :rows="multiline ? rows : undefined"
      :maxlength="maxlength"
      :show-word-limit="multiline"
      :placeholder="placeholder"
      @update:model-value="onInput"
    />

    <!--
      状态行只在真有话说的时候出现：没存过、也没在存，就不占这一行高度。
      「已保存 · 10:42」是给人一个确定的回执——理由这种东西写了几百字，
      看不到任何保存痕迹，谁都不敢确定系统到底收没收。
    -->
    <div v-if="line || canRestore || restored" class="df-line">
      <span v-if="line" :class="{ 'is-err': state === 'error' }">{{ line }}</span>

      <template v-if="restored">
        <span class="df-note">这版是上次没写完的</span>
        <el-link type="info" underline="never" @click="dropRestored">不用了</el-link>
      </template>
      <template v-else-if="canRestore">
        <el-link type="primary" underline="never" @click="restore">
          恢复上次写的（{{ draftTime }}）
        </el-link>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { deleteFieldDraft, getFieldDraft, saveFieldDraft } from '@/api'

/**
 * 理由输入框：写完之前替人存着。
 *
 * <p>五处共用：审批意见、财务复核说明、付款备注、强制提交说明、超标说明。
 * 它们的共同点是「一句话要斟酌很久」——写完一段驳回理由要翻好几屏材料，
 * 中途被别的事打断、误点菜单、浏览器崩了，几百字就没了。
 *
 * <p>为什么不做成「点保存按钮」：让人记得点，等于没做。停手 800 毫秒自动存，
 * 右下角给一句「已保存 · 10:42」当回执。按钮式的保存在这类场景里只有一种结局——
 * 没人点，然后丢。
 *
 * <p>为什么不用整张单据的草稿状态：这些理由所在的单据此时都有确定的业务状态
 * （待审批 / 待复核 / 待付款），没有「整单存草稿」这一档可挂。
 * 后端为此单开了一张 field_draft，键是（场景 + 单号 + 字段名 + 本人）。
 *
 * <p>草稿只暂存：正式值仍然由提交接口写进业务表，成功后后端顺手清掉草稿。
 * 它不参与任何计算与统计，也不是审批依据。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },

  /** 场景，见后端 FieldDraft 的 BIZ_* 常量 */
  bizType: { type: String, required: true },

  /** 业务单号：申请号或报销单号。为空表示还没挂上单据，此时不存也不读 */
  bizNo: { type: String, default: '' },

  /** 字段名，见后端 FieldDraft 的 FIELD_* 常量 */
  fieldName: { type: String, required: true },

  /** 多行理由用 textarea；付款备注这类一句话的用单行 */
  multiline: { type: Boolean, default: true },

  rows: { type: Number, default: 3 },
  placeholder: { type: String, default: '' },

  /** 与后端 VARCHAR(200) 对齐 */
  maxlength: { type: Number, default: 200 },

  /** 停手多久算写完一次 */
  debounce: { type: Number, default: 800 }
})

const emit = defineEmits(['update:modelValue'])

/** idle 没存过 / saving 存中 / saved 已存 / error 没存上 */
const state = ref('idle')
const savedAt = ref('')
const draftAt = ref('')
/** 上次落库的内容，拿它跟当前值比，没变就不必再写一次 */
const lastSaved = ref(null)
/** 库里读回来的草稿正文 */
const draft = ref(null)
/** 当前框里的内容是这次自动恢复出来的 */
const restored = ref(false)
/** 已经读过哪个键的草稿，避免每次 activated 都重读一遍 */
let fetchedKey = ''
let timer = null

const key = computed(() => `${props.bizType}|${props.bizNo}|${props.fieldName}`)

const line = computed(() => {
  if (state.value === 'saving') return '保存中…'
  if (state.value === 'error') return '草稿没存上，内容还在框里'
  if (state.value === 'saved') return `已保存 · ${hhmm(savedAt.value)}`
  return ''
})

/** 库里有、且跟框里现在写的不一样，才给「恢复」入口 */
const canRestore = computed(
  () => draft.value != null && draft.value !== (props.modelValue || '').trim() && !restored.value
)

const draftTime = computed(() => hhmm(draftAt.value))

function onInput (v) {
  emit('update:modelValue', v)
  // 手动改过之后就不再是「恢复出来的那版」，撤掉那条提示
  if (restored.value) restored.value = false
  schedule()
}

function schedule () {
  if (!props.bizNo) return
  clearTimeout(timer)
  timer = setTimeout(flush, props.debounce)
}

async function flush () {
  clearTimeout(timer)
  if (!props.bizNo) return
  const val = (props.modelValue || '').trim()
  // 没变过就不写；空且从没存过也不写——否则每次进页面都会发一次无意义的删除
  if (val === (lastSaved.value == null ? '' : lastSaved.value)) return

  state.value = 'saving'
  try {
    const res = await saveFieldDraft({
      bizType: props.bizType,
      bizNo: props.bizNo,
      fieldName: props.fieldName,
      content: val
    })
    if (val) {
      lastSaved.value = val
      savedAt.value = res?.updatedAt || ''
      state.value = 'saved'
    } else {
      // 清空即删除：不留一条空内容占着位，下次进来才不会弹出「恢复」却恢复出一片空白
      lastSaved.value = null
      savedAt.value = ''
      draft.value = null
      state.value = 'idle'
    }
  } catch {
    state.value = 'error'
  }
}

/** 把库里那版填回框里。它本来就是自己上次写的，不必再存一次 */
function restore () {
  emit('update:modelValue', draft.value)
  lastSaved.value = draft.value
  savedAt.value = draftAt.value
  state.value = 'saved'
  restored.value = true
}

/** 不用恢复的内容：连同库里那条一起清掉 */
async function dropRestored () {
  emit('update:modelValue', '')
  restored.value = false
  draft.value = null
  draftAt.value = ''
  lastSaved.value = null
  savedAt.value = ''
  state.value = 'idle'
  try {
    await deleteFieldDraft({
      bizType: props.bizType,
      bizNo: props.bizNo,
      fieldName: props.fieldName
    })
  } catch {
    /* 清不掉就算了，反正内容也不在框里了 */
  }
}

function resetLocal () {
  clearTimeout(timer)
  state.value = 'idle'
  savedAt.value = ''
  draftAt.value = ''
  lastSaved.value = null
  draft.value = null
  restored.value = false
}

async function fetchDraft () {
  if (!props.bizNo || fetchedKey === key.value) return
  fetchedKey = key.value
  resetLocal()
  try {
    const res = await getFieldDraft({
      bizType: props.bizType,
      bizNo: props.bizNo,
      fieldName: props.fieldName
    })
    if (!res || res.content == null) return
    draft.value = res.content
    draftAt.value = res.updatedAt || ''
    // 框里空着就直接填回去：这正是存它的目的。
    // 框里已经有字时不抢——那是这次现写的，比库里那版新
    if (!(props.modelValue || '').trim()) restore()
  } catch {
    /* 草稿读不到不影响填写，页面不该为此报错 */
  }
}

watch(key, () => {
  fetchedKey = ''
  fetchDraft()
})

onMounted(fetchDraft)
onActivated(fetchDraft)
// 切走之前把还没到点的那次存完，别让「刚写完就切走」丢内容
onDeactivated(flush)
onBeforeUnmount(() => clearTimeout(timer))

/** 同一天显示时刻，跨天带上日期，避免「10:42」到底是哪天说不清 */
function hhmm (s) {
  if (!s) return ''
  const d = new Date(String(s).replace(/-/g, '/'))
  if (Number.isNaN(d.getTime())) return String(s).slice(5, 16)
  const now = new Date()
  const p = (n) => String(n).padStart(2, '0')
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  return sameDay
    ? `${p(d.getHours())}:${p(d.getMinutes())}`
    : `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<style scoped>
.draft-field { width: 100%; }

.df-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--c-text-weak);
}
.df-line .is-err { color: var(--c-red); }
.df-note { color: var(--c-text-sub); }
</style>
