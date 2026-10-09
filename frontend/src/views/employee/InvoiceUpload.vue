<template>
  <div class="page-wrap">
    <!-- 单据选择 -->
    <div class="card">
      <div class="card-hd">
        <h3>选择出差单据</h3>
        <span class="weak">票据必须挂接到具体行程段，先选单再传票</span>
      </div>
      <div class="card-bd">
        <el-select v-model="applyNo" filterable placeholder="输入或选择申请号" style="width:320px" @change="loadAll">
          <el-option v-for="a in applyOptions" :key="a.applyNo" :label="`${a.applyNo} · ${a.reason}`" :value="a.applyNo" />
        </el-select>
        <span v-if="current.itineraries?.length" class="muted" style="margin-left:14px">
          共 {{ current.itineraries.length }} 段行程
        </span>
      </div>
    </div>

    <el-row :gutter="16" class="mt-16" v-if="applyNo">
      <!-- 左：上传与确认 -->
      <el-col :span="14" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>上传票据影像</h3></div>
          <div class="card-bd">
            <el-upload
              class="upload-box"
              drag
              :show-file-list="false"
              accept="image/*,.pdf"
              :http-request="handleUpload"
            >
              <el-icon class="upload-icon"><UploadFilled /></el-icon>
              <div class="el-upload__text">把票据拖到这里，或<em>点击上传</em></div>
              <template #tip>
                <div class="el-upload__tip">支持 jpg / png / pdf，单张不超过 10MB</div>
              </template>
            </el-upload>

            <div v-if="uploading" class="tip-box tip-info mt-12">
              影像已上传，正在识别票面……
            </div>

            <div v-if="ocrForm.visible" class="ocr-panel mt-16">
              <el-form :model="ocrForm" label-width="92px" size="small" class="mt-12">
                <el-row :gutter="12">
                  <el-col :span="12" :xs="24">
                    <el-form-item label="发票号">
                      <el-input v-model="ocrForm.invoiceNo" class="mono" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12" :xs="24">
                    <el-form-item label="票面金额">
                      <el-input-number v-model="ocrForm.amount" :min="0" :precision="2" style="width:100%" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12" :xs="24">
                    <el-form-item label="开票日期">
                      <el-date-picker v-model="ocrForm.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" @change="suggestSegment" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12" :xs="24">
                    <el-form-item label="销售方">
                      <el-input v-model="ocrForm.seller" @blur="suggestSegment" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="24">
                    <el-form-item label="挂接行程段">
                      <el-select v-model="ocrForm.itineraryId" style="width:100%">
                        <el-option
                          v-for="it in current.itineraries" :key="it.id"
                          :label="`第 ${it.seq} 段 · ${it.fromCity}→${it.toCity} (${it.startDate} ~ ${it.endDate})`"
                          :value="it.id"
                        />
                      </el-select>
                      <!-- AI 挂段建议：给置信度，低了就必须让人自己选 -->
                      <div v-if="aiEnabled" class="ai-match">
                        <span class="ai-mark">AI</span>
                        <div class="ai-match-body">
                          <span v-if="aiMatching" class="muted">正在判断该挂到哪一段…</span>
                          <template v-else-if="aiMatch">
                            <div class="ai-match-line">
                              <span>
                                建议挂到<b>第 {{ segLabel(aiMatch.itineraryId) }} 段</b>
                                · 置信度 {{ aiMatch.confidence.toFixed(2) }}
                              </span>
                              <el-button size="small" type="primary" plain @click="acceptMatch">采用</el-button>
                            </div>
                            <div class="ai-match-reason">{{ aiMatch.reason }}</div>
                          </template>
                          <span v-else class="weak">填了开票日期后，这里会推荐挂接段</span>
                        </div>
                      </div>
                    </el-form-item>
                  </el-col>
                </el-row>
                <div class="row-end">
                  <el-button @click="ocrForm.visible = false">取消</el-button>
                  <el-button type="primary" :loading="confirming" @click="confirmOcr">确认挂接</el-button>
                </div>
              </el-form>
            </div>

            <!-- 影像预览：上传完就把这张图显示出来，确认 OCR 识别的是不是刚传的那张 -->
            <div v-if="uploadedPath" class="preview-panel mt-16">
              <div class="section-title">影像预览</div>
              <div class="preview-frame">
                <img v-if="previewSrc" :src="previewSrc" class="preview-img" alt="票据影像" />
                <div v-else class="preview-ph">
                  {{ previewFailed ? '影像加载失败（链接可能已过期）' : '票据影像占位' }}
                </div>
                <div class="preview-path mono weak">{{ uploadedPath }}</div>
              </div>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 右：清单与缺票 -->
      <el-col :span="10" :xs="24">
        <div class="card">
          <div class="card-hd">
            <h3>已挂接票据</h3>
            <span class="amount" style="font-weight:650">¥ {{ money(invoiceData.totalAmount) }}</span>
          </div>
          <div class="card-bd">
            <el-table :data="invoiceData.invoices || []" border size="small" max-height="300">
              <!-- 挂接完要能核一眼是不是挂对了：人对票面比对票号快 -->
              <el-table-column label="影像" width="66" align="center" class-name="img-cell">
                <template #default="{ row }">
                  <InvoiceImage
                    :img-path="row.imgPath" :img-url="row.imgUrl" :thumb-url="row.thumbUrl"
                    :invoice-no="row.invoiceNo" :id="row.id" :size="44"
                  />
                </template>
              </el-table-column>
              <el-table-column prop="invoiceNo" label="发票号" min-width="150" class-name="mono" show-overflow-tooltip />
              <el-table-column prop="amount" label="金额" width="100" align="right">
                <template #default="{ row }"><span class="amount">¥ {{ money(row.amount) }}</span></template>
              </el-table-column>
              <el-table-column prop="itineraryId" label="段" width="60" align="center" />
              <el-table-column label="操作" width="70" align="center">
                <template #default="{ row }">
                  <el-button link type="danger" size="small" @click="remove(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!invoiceData.invoices?.length" description="还没有票据" :image-size="60" />
          </div>
        </div>

        <div class="card mt-16">
          <div class="card-hd"><h3>缺票推算</h3></div>
          <div class="card-bd">
            <div v-for="m in invoiceData.missing || []" :key="m.itineraryId + m.ticketType" class="missing-item">
              <el-icon class="missing-icon"><WarningFilled /></el-icon>
              <div>
                <div class="missing-title">第 {{ m.seq }} 段 · {{ m.ticketType }}</div>
                <div class="missing-hint weak">{{ m.hint }}</div>
              </div>
            </div>
            <div v-if="!invoiceData.missing?.length" class="tip-box tip-success">各段行程票据齐全</div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onActivated, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { myApplyPage, applyDetail, uploadInvoice, confirmInvoice, invoiceList, deleteInvoice, fetchInvoiceImage } from '@/api'
import { aiEnabled, aiInvoiceMatch } from '@/api/ai'
import { money } from '@/utils/dict'
import InvoiceImage from '@/components/InvoiceImage.vue'

const route = useRoute()
const applyNo = ref(route.query.applyNo || '')
const applyOptions = ref([])
const current = reactive({ itineraries: [] })
const invoiceData = reactive({ invoices: [], missing: [], totalAmount: 0 })
const uploadedPath = ref('')
const confirming = ref(false)
const uploading = ref(false)
const previewSrc = ref('')
const previewFailed = ref(false)

const ocrForm = reactive({
  visible: false,
  invoiceNo: '', amount: 0, invoiceDate: '', seller: '', itineraryId: null,
  // 1 = 这一版字段是 OCR 代填的，0 = 手工录入。后端据此决定要不要记人工修正
  ocrFlag: 0
})

/**
 * OCR 刚识别出来的原文快照。
 *
 * 保存它是为了记「人改了什么」：员工核对时把票号或金额改掉，
 * 事后要知道系统当时给的是什么。快照只在点确认那一刻用一次，
 * 直接读 ocrForm 是不行的——那里已经是改过的值了。
 */
let ocrSnapshot = null

/** AI 挂段建议（后端未接入，走 api/ai.js 本地实现） */
const aiMatch = ref(null)
const aiMatching = ref(false)

async function suggestSegment () {
  if (!aiEnabled || !current.itineraries.length) return
  aiMatching.value = true
  try {
    // remote 模式下后端按 applyNo 自己查行程段，行程清单不用从前端传回去
    aiMatch.value = await aiInvoiceMatch({
      applyNo: applyNo.value,
      invoiceDate: ocrForm.invoiceDate,
      seller: ocrForm.seller,
      itineraries: current.itineraries
    })
  } finally {
    aiMatching.value = false
  }
}

function segLabel (id) {
  return current.itineraries.find((it) => it.id === id)?.seq || '?'
}

function acceptMatch () {
  if (!aiMatch.value) return
  ocrForm.itineraryId = aiMatch.value.itineraryId
  ElMessage.success(`已挂到第 ${segLabel(aiMatch.value.itineraryId)} 段`)
}

async function loadOptions () {
  const data = await myApplyPage({ pageNum: 1, pageSize: 50 })
  applyOptions.value = data.list
  if (!applyNo.value && data.list.length) {
    applyNo.value = data.list[0].applyNo
  }
}

async function loadAll () {
  if (!applyNo.value) return
  const [detail, inv] = await Promise.all([
    applyDetail(applyNo.value),
    invoiceList({ applyNo: applyNo.value })
  ])
  current.itineraries = detail.itineraries || []
  Object.assign(invoiceData, inv)
  ocrForm.itineraryId = current.itineraries[0]?.id || null
}

async function handleUpload ({ file }) {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('applyNo', applyNo.value)
  uploading.value = true
  releasePreview()
  previewFailed.value = false
  try {
    const res = await uploadInvoice(fd)
    // 后端 C-01 返回的临时路径字段名是 tempPath（Apifox 文档同），
    // 之前写成 imgPath，上传完拿到 undefined，票据挂接后没有影像
    uploadedPath.value = res.tempPath
    if (res.previewUrl) {
      try {
        previewSrc.value = await fetchInvoiceImage(res.previewUrl)
      } catch (e) {
        // 预览拿不到不影响主流程，影像路径已经拿到了
        previewFailed.value = true
      }
    }
    // OCR 没配（application.yml 里 OCR 三件套是 TODO）时后端回 ocrSuccess=false、
    // 票面字段全 null，这里不能假设 ocr 有值，直接转手工录入
    const ocr = res.ocr || {}
    Object.assign(ocrForm, {
      visible: true,
      invoiceNo: ocr.invoiceNo || '',
      amount: ocr.amount || 0,
      invoiceDate: ocr.invoiceDate || '',
      seller: ocr.seller || '',
      ocrFlag: res.ocrSuccess ? 1 : 0
    })
    if (res.ocrSuccess) {
      // 只在这一刻存快照：下面员工一改，ocrForm 里就不是识别值了
      ocrSnapshot = {
        invoiceNo: ocr.invoiceNo || '',
        amount: ocr.amount == null ? '' : String(ocr.amount),
        invoiceDate: ocr.invoiceDate || '',
        seller: ocr.seller || ''
      }
      ElMessage.success('OCR 识别完成，请核对字段')
    } else {
      ocrSnapshot = null
      ElMessage.warning(ocr.message || 'OCR 未开启，请手工录入票面信息')
    }
    suggestSegment()
  } catch (e) { /* 已统一提示 */ } finally {
    uploading.value = false
  }
}

/** 换图时把上一个 blob 释放掉，不然连着传几张会一直占内存 */
function releasePreview () {
  if (previewSrc.value) {
    URL.revokeObjectURL(previewSrc.value)
    previewSrc.value = ''
  }
}

async function confirmOcr () {
  if (!ocrForm.itineraryId) return ElMessage.warning('请选择要挂接的行程段')
  confirming.value = true
  try {
    // imgPath 必须带上：C-01 上传拿到的 tempPath 就是这张票的影像路径，
    // 不传的话库里 img_path 为空，财务复核时看不到影像
    await confirmInvoice({
      applyNo: applyNo.value,
      ...ocrForm,
      imgPath: uploadedPath.value,
      // 手工录入的票没有「系统给的值」，不带快照，后端也就不记人工修正
      ocrValues: ocrForm.ocrFlag === 1 ? ocrSnapshot : null
    })
    ElMessage.success('挂接成功')
    ocrForm.visible = false
    ocrSnapshot = null
    await loadAll()
  } finally {
    confirming.value = false
  }
}

async function remove (id) {
  await deleteInvoice(id)
  ElMessage.success('已删除')
  await loadAll()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'EmpInvoiceUpload' })

/**
 * 每次回到这页都重新对齐一次：下拉里要有在填写页新提交的单，票据明细要是库里最新的。
 * 选中的那张不动——loadOptions 只在压根没选时才替他挑第一个，
 * 所以正在对着哪张单传票，回来还是那张。
 *
 * 用 activated 而不是 mounted：组件进了 keep-alive 之后切走不再 mounted，
 * 只靠 mounted 的话回到这页还是上一次进来时的数据。
 * （首次进入时 activated 也会触发一次，所以不必再留一个 onMounted）
 */
onActivated(async () => {
  await loadOptions()
  // 列表那边「去上传票据」把单号带在地址栏上，这种明确的指向优先；
  // 没带单号（直接从菜单进来）就保持他上次选的那张，正在填的东西不丢
  const fromUrl = route.query.applyNo || ''
  if (fromUrl && fromUrl !== applyNo.value) applyNo.value = fromUrl
  if (applyNo.value) await loadAll()
})
</script>

<style scoped>
.upload-icon { font-size: 40px; color: var(--c-primary); }
.upload-box :deep(.el-upload-dragger) { padding: 26px 16px; }

/* 影像列：缩略图是定宽块级元素，靠 flex 居中，别让它贴在单元格左边 */
.img-cell :deep(.cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-top: 4px;
  padding-bottom: 4px;
}

.ocr-panel { border-top: 1px dashed var(--c-border); padding-top: 14px; }

/* 和 AiAdvice 卡片同一套观感：浅底 + 左侧主色条，理由换行作小字 */
.ai-mark {
  display: inline-block;
  flex: none;
  background: var(--c-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  border-radius: 4px;
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
  letter-spacing: .5px;
}

.ai-match {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 7px;
  padding: 8px 11px;
  border-left: 3px solid var(--c-primary);
  border-radius: 6px;
  background: var(--c-primary-soft);
  font-size: 12.5px;
}
.ai-match-body { min-width: 0; flex: 1; }
.ai-match-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ai-match-reason { font-size: 11px; color: var(--c-text-weak); margin-top: 4px; line-height: 1.6; }

.preview-frame {
  border: 1px solid var(--c-border); border-radius: 8px; padding: 10px;
}
.preview-ph {
  height: 150px; border-radius: 6px; background: repeating-linear-gradient(45deg, #f4f6f9, #f4f6f9 10px, #eef1f5 10px, #eef1f5 20px);
  display: flex; align-items: center; justify-content: center; color: var(--c-text-weak); font-size: 13px;
}
.preview-img {
  width: 100%; max-height: 260px; object-fit: contain; border-radius: 6px;
  background: #f7f8fa;
}
.preview-path { margin-top: 8px; word-break: break-all; }

.missing-item {
  display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px dashed var(--c-border);
}
.missing-item:last-of-type { border-bottom: none; }
.missing-icon { color: var(--c-amber); font-size: 16px; margin-top: 2px; }
.missing-title { font-size: 13px; font-weight: 600; }
.missing-hint { font-size: 12px; }
</style>
