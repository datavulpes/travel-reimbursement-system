import { ref } from 'vue'

/**
 * 手机版式判定。
 *
 * 断点字符串必须与 styles/mobile.css 里的媒体查询**逐字一致**，改一处要改两处。
 *
 * 为什么带上 (pointer: coarse) 而不是只看宽度：
 * 只按宽度判的话，电脑上把浏览器窗口拖窄也会切成手机版式。加上「主输入是触屏」
 * 这个条件后，只有手机/平板会切换，PC 端怎么拖窗口都不受影响。
 * 注意用的是 pointer（主输入）而不是 any-pointer，所以带触摸屏的笔记本
 * 只要主输入还是鼠标/触控板，就仍然走桌面版式。
 */
export const MQ_MOBILE = '(max-width: 768px) and (pointer: coarse)'

const isMobile = ref(false)

function bind() {
  // 组件冒烟等 SSR 场景没有 window，这里必须能安静跳过
  if (typeof window === 'undefined' || !window.matchMedia) return
  const mql = window.matchMedia(MQ_MOBILE)
  isMobile.value = mql.matches

  const onChange = (e) => {
    isMobile.value = e.matches
  }
  // 老版本 Safari 只认 addListener
  if (mql.addEventListener) {
    mql.addEventListener('change', onChange)
  } else if (mql.addListener) {
    mql.addListener(onChange)
  }
}

bind()

/**
 * 返回全局唯一的 isMobile ref。
 * 屏幕方向改变、分屏等情况会自己更新，页面不需要各写一套监听。
 */
export function useIsMobile() {
  return isMobile
}
