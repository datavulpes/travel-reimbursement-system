import os, zipfile, sys

PROJECT = r'C:\Users\31259\Desktop\金舟面试项目\小程序端'
SRC = os.path.join(PROJECT, 'dist', 'build', 'app')          # 编译后的前端资源（www）
OUT_DIR = os.path.join(PROJECT, 'update')                      # nginx /update/ 托管的目录
OUT = os.path.join(OUT_DIR, 'chaolvzhushou.wgt')

if not os.path.isdir(SRC):
    print('ERROR: 找不到编译产物', SRC)
    sys.exit(1)

os.makedirs(OUT_DIR, exist_ok=True)
if os.path.exists(OUT):
    os.remove(OUT)

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(SRC):
        for f in files:
            fp = os.path.join(root, f)
            arc = os.path.relpath(fp, SRC)
            z.write(fp, arc)

print('wgt 生成:', OUT)
print('wgt 大小:', os.path.getsize(OUT), 'bytes')
# 打印根目录关键文件，确认结构正确
with zipfile.ZipFile(OUT) as z:
    names = z.namelist()
    for key in ('manifest.json', 'app-service.js', 'index.html'):
        print('  含', key, ':', key in names)
