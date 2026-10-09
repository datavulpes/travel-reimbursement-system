<template>
  <div
    class="inv-img"
    :class="{ 'is-busy': loading || fullBusy }"
    :style="{ width: boxSize, height: boxSize }"
    @click="onOpen"
  >
    <img v-if="thumbSrc" :src="thumbSrc" :alt="title" @error="onThumbError" />
    <span v-else-if="loading" class="inv-img__txt">…</span>
    <span v-else class="inv-img__txt">{{ hasImage ? '重试' : '—' }}</span>
  </div>

  <!--
    放大镜用的是 EP 自带的 el-image-viewer，不是 el-dialog，两件事就因为它解决掉了：
      1. viewer 是 Teleport 到 body 的。之前用 el-dialog（默认就地渲染）时，弹层长在
         表格单元格 / 抽屉里面，半张窗甩到原页面上、抽屉的表头还反过来压在窗上面。
      2. v-if 让它只有点开那一刻才挂进 DOM。影像查询那页几十张票，以前是几十个
         dialog 常驻着叠在页面上。
  -->
  <el-image-viewer
    v-if="viewerOpen"
    teleported
    hide-on-click-modal
    :url-list="viewerList"
    :z-index="3000"
    @close="onViewerClose"
  />
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchInvoiceImage, invoiceUrl } from '@/api'

/**
 * 票据影像（缩略图 + 点开看原图）。
 *
 * 缩略图和原图是两条地址，各管一段：
 *  - 缩略图（thumbUrl，OSS 走 x-oss-process 压到 240px）列表页直接铺，进页面就加载；
 *  - 原图（imgUrl）**点开那一刻才去取**。影像查询那一页几十张票同屏，
 *    每张都几百 KB，一起拉会把页面拖死。
 *
 * 地址有三种形态，都得认：
 *  - OSS 预签名 URL：https 开头，不带登录态，<img src> 直接挂；
 *  - data: 占位图（mock 演示时用），同上直接挂；
 *  - /api/invoice/file 相对地址：要 Authorization，<img src> 拿不到，
 *    得自己带 token 取回字节再转成 objectURL 喂给 img。
 *
 * 地址带过期时间，页面开久了会失效。失败时手里有 id 就去 I-06 换一条新的再试一次，
 * 试不出来就留「重试」，让人点一下。
 */
const props = defineProps({
  /** 影像相对路径，用来判断这张票到底有没有图 */
  imgPath: { type: String, default: '' },
  imgUrl: { type: String, default: '' },
  thumbUrl: { type: String, default: '' },
  invoiceNo: { type: String, default: '' },
  /** 票据 id，传了才能在加载失败时换新链接 */
  id: { type: [Number, String], default: null },
  size: { type: Number, default: 56 }
})

const DIRECT = /^(https?:|data:|blob:)/

const loading = ref(false)
const fullBusy = ref(false)
const failed = ref(false)
const thumbSrc = ref('')
const viewerOpen = ref(false)
const viewerList = ref([])

/** 当前在用的原始地址。拿 I-06 换过链之后会被覆盖，后续都从这里读 */
const rawThumb = ref('')
const rawFull = ref('')

let thumbObjectUrl = ''
let fullObjectUrl = ''

const boxSize = computed(() => props.size + 'px')
const title = computed(() => (props.invoiceNo ? '票据 ' + props.invoiceNo : '票据影像'))
const hasImage = computed(() => Boolean(rawThumb.value || rawFull.value || props.imgPath))

function release (which) {
  if (which === 'thumb' && thumbObjectUrl) {
    URL.revokeObjectURL(thumbObjectUrl)
    thumbObjectUrl = ''
  }
  if (which === 'full' && fullObjectUrl) {
    URL.revokeObjectURL(fullObjectUrl)
    fullObjectUrl = ''
  }
}

/**
 * 把原始地址变成 <img> 能直接吃的 src。
 * 走网络的分支会产出一个 objectURL，记下来好在替换和卸载时 revoke，不然内存只涨不落。
 */
async function materialize (url, which) {
  release(which)
  if (!url) {
    return ''
  }
  if (DIRECT.test(url)) {
    return url
  }
  const objectUrl = await fetchInvoiceImage(url)
  if (which === 'thumb') {
    thumbObjectUrl = objectUrl
  } else {
    fullObjectUrl = objectUrl
  }
  return objectUrl
}

async function loadThumb (url) {
  loading.value = true
  thumbSrc.value = ''
  try {
    thumbSrc.value = await materialize(url, 'thumb')
    failed.value = false
  } catch {
    failed.value = true
    thumbSrc.value = ''
  } finally {
    loading.value = false
  }
}

/** 相关地址过期时调 I-06 换一条新的。换不到就返回 false，让调用方保持失败态 */
async function refreshUrls () {
  if (props.id == null) {
    return false
  }
  try {
    const vo = await invoiceUrl(props.id)
    const nextThumb = vo?.thumbUrl || ''
    const nextFull = vo?.imgUrl || ''
    if (!nextThumb && !nextFull) {
      return false
    }
    rawThumb.value = nextThumb
    rawFull.value = nextFull
    failed.value = false
    return true
  } catch {
    // 拦截器已经弹过提示，这里安静地失败：占位图上留「重试」，人点一下再来
    return false
  }
}

watch(
  () => [props.thumbUrl, props.imgUrl],
  ([thumb, full]) => {
    rawThumb.value = thumb || ''
    rawFull.value = full || ''
    loadThumb(rawThumb.value || rawFull.value)
  },
  { immediate: true }
)

async function onThumbError () {
  if (failed.value) {
    return
  }
  failed.value = true
  if (await refreshUrls()) {
    await loadThumb(rawThumb.value || rawFull.value)
  }
}

async function onOpen () {
  if (viewerOpen.value || fullBusy.value) {
    return
  }
  if (!hasImage.value) {
    return
  }
  // 链接过期过一次的话，先看能不能换条新的，别拿着旧地址再撞一次墙
  if (failed.value) {
    failed.value = false
    await refreshUrls()
  }
  const target = rawFull.value || rawThumb.value
  if (!target) {
    failed.value = true
    return
  }
  fullBusy.value = true
  try {
    viewerList.value = [await materialize(target, 'full')]
    viewerOpen.value = true
  } catch {
    failed.value = true
    ElMessage.error('影像没能取回来，等一下再点一次试试')
  } finally {
    fullBusy.value = false
  }
}

function onViewerClose () {
  viewerOpen.value = false
  viewerList.value = []
  release('full')
}

onBeforeUnmount(() => {
  release('thumb')
  release('full')
})
</script>

<style scoped>
.inv-img {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #f7f9fc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  cursor: zoom-in;
  transition: border-color 0.15s;
}

.inv-img:hover {
  border-color: var(--el-color-primary);
}

.inv-img.is-busy {
  cursor: progress;
}

.inv-img img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.inv-img__txt {
  font-size: 12px;
  color: #94a3b8;
}
</style>
