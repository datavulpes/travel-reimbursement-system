<template>
  <div class="login-wrap">
    <div class="login-box">
      <!-- 左侧品牌区：只留系统名称，垂直居中 -->
      <aside class="login-side">
        <div class="side-top">
          <h1>企业差旅管理工具</h1>
          <p>Business Travel Management</p>
        </div>
      </aside>

      <!-- 右侧登录表单 -->
      <div class="login-form">
        <h2 class="form-title">账号登录</h2>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="登录名" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
              clearable
            />
          </el-form-item>

          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form>

        <div class="account-block">
          <div class="account-hd">
            <span class="account-title">联调账号</span>
            <span class="account-hint">点击自动填入</span>
          </div>
          <div class="account-grid">
            <div
              v-for="acc in MOCK_ACCOUNTS"
              :key="acc.username"
              class="account-item"
              :class="{ active: form.username === acc.username }"
              :style="{ '--c': roleColor(acc.role), '--soft': roleSoft(acc.role) }"
              @click="fill(acc)"
            >
              <span class="acc-avatar">{{ acc.realName.slice(0, 1) }}</span>
              <span class="acc-main">
                <span class="acc-name">{{ acc.realName }}</span>
                <span class="acc-role">{{ roleLabel(acc.role) }}</span>
                <span class="acc-info mono">{{ acc.username }} / {{ acc.password }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { MOCK_ACCOUNTS, ROLE_META } from '@/config/menus'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

function roleLabel (role) { return ROLE_META[role]?.label || role }
function roleColor (role) { return ROLE_META[role]?.color || '#2563a8' }
function roleSoft (role) { return ROLE_META[role]?.soft || '#e8f1fa' }

function fill (acc) {
  form.username = acc.username
  form.password = acc.password
  ElMessage.success(`已填入 ${roleLabel(acc.role)} 账号，点击登录进入`)
}

async function handleLogin () {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.doLogin({ username: form.username, password: form.password })
      ElMessage.success(`欢迎回来，${userStore.realName}`)
      // 后端返回的 role 决定进入哪个端口
      router.push(userStore.homePath)
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px;
  background:
    radial-gradient(1100px 520px at 12% 6%, #dbe6f4 0%, rgba(219, 230, 244, 0) 62%),
    linear-gradient(135deg, #eef2f7 0%, #e3eaf3 100%);
}

.login-box {
  width: 960px;
  max-width: 100%;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(23, 38, 56, .14);
  display: grid;
  grid-template-columns: .92fr 1fr;
  overflow: hidden;
}

/* ── 左侧 ───────────────────────────────────────── */
.login-side {
  position: relative;
  overflow: hidden;
  padding: 38px 34px;
  color: #fff;
  background: linear-gradient(158deg, #17436f 0%, #2563a8 56%, #3f86c6 100%);
  display: flex;
  flex-direction: column;
  /* 去掉说明段之后左栏只剩两块，垂直居中比顶到底好看 */
  justify-content: center;
}
/* 右下角一块柔光，避免大面积纯色显得板 */
.login-side::after {
  content: '';
  position: absolute;
  right: -80px;
  bottom: -80px;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: radial-gradient(circle at 32% 32%, rgba(255, 255, 255, .14), rgba(255, 255, 255, 0) 70%);
}

.side-top { position: relative; z-index: 1; }
.side-top h1 {
  margin: 0; font-size: 24px; font-weight: 650;
  letter-spacing: 1px; line-height: 1.5;
}
.side-top p {
  margin: 12px 0 0;
  font-size: 11px; opacity: .68;
  letter-spacing: 1.6px; text-transform: uppercase;
}
/* 标题下一条短线，把字压住——不然整块蓝只有两行字，看着像没做完 */
.side-top::after {
  content: '';
  display: block;
  width: 44px; height: 2px;
  margin-top: 16px;
  background: rgba(255, 255, 255, .5);
  border-radius: 2px;
}

/* ── 右侧 ───────────────────────────────────────── */
.login-form { padding: 40px 42px 32px; display: flex; flex-direction: column; }

.form-title { margin: 0 0 26px; font-size: 23px; font-weight: 650; letter-spacing: .5px; }

.login-form :deep(.el-form-item) { margin-bottom: 15px; }
.login-form :deep(.el-input__wrapper) {
  border-radius: 9px;
  box-shadow: 0 0 0 1px var(--c-border) inset;
  padding: 0 13px;
  transition: box-shadow .18s;
}
.login-form :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #ccd6e2 inset; }
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--c-primary) inset, 0 0 0 3px rgba(37, 99, 168, .1);
}

.login-btn {
  width: 100%; height: 44px; margin-top: 6px;
  border-radius: 9px; letter-spacing: 5px;
  font-size: 15px; font-weight: 600;
  box-shadow: 0 6px 16px rgba(37, 99, 168, .24);
}

/* 联调账号：两列网格，比原来四行竖排省一半高度 */
.account-block { margin-top: auto; padding-top: 20px; border-top: 1px dashed var(--c-border); }
.account-hd { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 11px; }
.account-title { font-size: 12.5px; font-weight: 600; color: var(--c-text-sub); }
.account-hint { font-size: 11px; color: var(--c-text-weak); }

.account-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }

.account-item {
  display: flex; align-items: center; gap: 9px;
  padding: 9px 11px; border-radius: 10px;
  border: 1px solid var(--c-border);
  background: #fff;
  cursor: pointer; text-align: left;
  transition: border-color .18s, background .18s, transform .18s, box-shadow .18s;
}
.account-item:hover {
  border-color: var(--c); background: #fbfcfe;
  transform: translateY(-1px); box-shadow: var(--shadow-sm);
}
.account-item.active { border-color: var(--c); background: var(--soft); }

.acc-avatar {
  flex: none;
  width: 30px; height: 30px; border-radius: 50%;
  background: var(--c); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600;
}
.acc-main { flex: 1; min-width: 0; }
.acc-name {
  display: block; font-size: 12.5px; font-weight: 600; color: var(--c-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.acc-role {
  display: inline-block; margin-top: 2px;
  font-size: 10.5px; color: var(--c); line-height: 1.6;
  background: rgba(0, 0, 0, .04); padding: 0 5px; border-radius: 3px;
}
.acc-info {
  display: block; margin-top: 3px;
  font-size: 10.5px; color: var(--c-text-weak);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* ── 响应式 ─────────────────────────────────────── */
@media (max-width: 900px) {
  .login-wrap { padding: 16px; align-items: flex-start; }
  .login-box { grid-template-columns: 1fr; }
  .login-side { display: none; }
  .login-form { padding: 32px 26px 26px; }
}
@media (max-width: 520px) {
  .account-grid { grid-template-columns: 1fr; }
}
</style>
