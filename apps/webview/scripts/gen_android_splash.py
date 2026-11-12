"""生成 Android 启动画面 splash.png（浅色渐变 + 居中图标）"""
from PIL import Image
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, '..', 'web', 'icon-512.png')
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')

PORT = {'mdpi': (320, 480), 'hdpi': (480, 800), 'xhdpi': (720, 1280),
        'xxhdpi': (960, 1600), 'xxxhdpi': (1280, 1920)}

icon = Image.open(SRC).convert('RGBA')


def gradient(w, h):
    img = Image.new('RGB', (w, h))
    top = (232, 237, 243)
    bot = (221, 229, 238)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top[0] + (bot[0] - top[0]) * t)
        g = int(top[1] + (bot[1] - top[1]) * t)
        b = int(top[2] + (bot[2] - top[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return img


def make(w, h, out):
    bg = gradient(w, h).convert('RGBA')
    size = int(min(w, h) * 0.28)
    ic = icon.resize((size, size), Image.LANCZOS)
    bg.paste(ic, ((w - size) // 2, (h - size) // 2), ic)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    bg.convert('RGB').save(out, 'PNG')


for dens, (w, h) in PORT.items():
    make(w, h, os.path.join(RES, 'drawable-port-' + dens, 'splash.png'))
    make(h, w, os.path.join(RES, 'drawable-land-' + dens, 'splash.png'))

# 通用 drawable 也覆盖一份
make(1280, 1920, os.path.join(RES, 'drawable', 'splash.png'))

print('splash generated')
