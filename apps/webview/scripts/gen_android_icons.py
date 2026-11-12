"""从 icon-512.png 生成 Android 各密度启动图标"""
from PIL import Image, ImageDraw
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, '..', 'web', 'icon-512.png')
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')

DENSITIES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

# 自适应图标前景/背景尺寸（108dp）
ADAPTIVE = {
    'mdpi': 108,
    'hdpi': 162,
    'xhdpi': 216,
    'xxhdpi': 324,
    'xxxhdpi': 432,
}

src = Image.open(SRC).convert('RGBA')

for dens, size in DENSITIES.items():
    folder = os.path.join(RES, 'mipmap-' + dens)
    os.makedirs(folder, exist_ok=True)

    # 传统方形图标
    img = src.resize((size, size), Image.LANCZOS)
    img.save(os.path.join(folder, 'ic_launcher.png'))
    # 圆形图标
    img.save(os.path.join(folder, 'ic_launcher_round.png'))

    # 自适应图标前景（内容缩到 60%，留出安全区）
    a = ADAPTIVE[dens]
    fg = Image.new('RGBA', (a, a), (0, 0, 0, 0))
    inner = int(a * 0.62)
    small = src.resize((inner, inner), Image.LANCZOS)
    off = (a - inner) // 2
    fg.paste(small, (off, off), small)
    fg.save(os.path.join(folder, 'ic_launcher_foreground.png'))

    # 自适应图标背景（纯色）
    bg = Image.new('RGBA', (a, a), (75, 200, 92, 255))
    bg.save(os.path.join(folder, 'ic_launcher_background.png'))

print('icons generated')
