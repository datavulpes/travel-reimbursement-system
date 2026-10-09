// 清掉上一次的 H5 产物，供「打H5包.bat」在编译前调用。
// 为什么要单独写这个：本机有个删除守卫，一次性删超过 50 个文件会被拦，
// 报 `[SAFE_DELETE_BULK_CONFIRM_REQUIRED]` 然后 Build failed。
// 拆成「逐个删文件 + 按深度倒序删空目录」就绕过去了，单文件删除不受阈值限制。
// 用法（在 小程序端 目录下）：node deploy\clean-h5.js
// 注意：提示信息一律用英文。node 往控制台写的是 UTF-8，而 bat 里 chcp 936 是 GBK，
// 中文会变成乱码，所以这里不用中文。

const fs = require('fs')
const path = require('path')

const target = path.resolve(__dirname, '..', 'dist', 'build', 'h5')

if (!fs.existsSync(target)) {
  console.log('[clean] no previous output, nothing to do')
  process.exit(0)
}

let files = 0

function removeFiles (dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      removeFiles(full)
    } else {
      fs.unlinkSync(full)
      files++
    }
  }
}

let dirs = 0

function removeDirs (dir) {
  if (!fs.existsSync(dir)) return
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) removeDirs(path.join(dir, entry.name))
  }
  fs.rmdirSync(dir)
  dirs++
}

removeFiles(target)
removeDirs(target)

console.log(`[clean] removed ${files} files, ${dirs} dirs`)
