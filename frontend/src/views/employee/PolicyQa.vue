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
            maxlength="100"
            show-word-limit
            clearable
            placeholder="比如：去深圳坐飞机能报多少？超标了怎么提交？"
            @keyup.enter="ask"
          />
          <el-button type="primary" :loading="loading" :disabled="!question.trim()" @click="ask">
            提问
          </el-button>
        </div>

        <div v-if="result.question" class="answer-box mt-16">
          <div class="ask-echo">
            <span class="ask-mark">问</span>
            <span>{{ result.question }}</span>
          </div>

          <div class="answer-line" :class="{ 'is-miss': result.answerable === false }">
            <span class="ask-mark" :class="{ 'is-miss': result.answerable === false }">答</span>
            <span>{{ result.answer }}</span>
            <el-tag
              v-if="result.local" size="small" type="info" effect="plain"
            >本地规则</el-tag>
          </div>

          <template v-if="(result.facts || []).length">
            <div class="section-title mt-16">依据</div>
            <ul class="fact-list">
              <li v-for="(f, i) in result.facts" :key="i">{{ f }}</li>
            </ul>
          </template>

          <el-collapse v-if="(result.allFacts || []).length" class="mt-16">
            <el-collapse-item :title="`库里现行的规定（${result.allFacts.length} 条）`">
              <ul class="fact-list">
                <li v-for="(f, i) in result.allFacts" :key="i">{{ f }}</li>
              </ul>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onActivated, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { policyQa } from '@/api'

const question = ref('')
const loading = ref(false)
const result = reactive({})

async function ask () {
  const q = question.value.trim()
  if (!q) return
  loading.value = true
  try {
    Object.assign(result, await policyQa(q))
  } catch (e) {
    ElMessage.error('这次没答上来，稍后再试')
  } finally {
    loading.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字。
// 这里的问答要留着：切走看一眼差标再回来，刚才那句答案不该没了。
defineOptions({ name: 'EmpPolicyQa' })

/** 回到页面不重拉：问答是一次性的，重拉会把上一条答案清空 */
onActivated(() => {})
</script>

<style scoped>
.ask-row {
  display: flex;
  gap: 10px;
  align-items: center;
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
.fact-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--c-text-weak);
  line-height: 1.9;
}
</style>
