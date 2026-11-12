"""生成应用图标 PNG（纯 Python，无第三方依赖）"""
import zlib, struct, math

def dist_seg(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - x1, py - y1)
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))

def rounded_alpha(x, y, w, h, r):
    """圆角矩形内部为 1，边缘做简单抗锯齿"""
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
    d = math.hypot(x - cx, y - cy)
    if x < r or x > w - r or y < r or y > h - r:
        return max(0.0, min(1.0, (r - d) + 0.5))
    return 1.0

def make(size, path):
    W = H = size
    px = bytearray()
    # 图标内缩一点，留出安全边距（maskable 需要）
    pad = size * 0.0
    r = size * 0.235
    sw = size * 0.085           # 对勾线宽
    a = (0.295, 0.520); b = (0.445, 0.665); c = (0.715, 0.350)
    for y in range(H):
        px.append(0)  # PNG filter byte
        for x in range(W):
            fx, fy = x + 0.5, y + 0.5
            alpha_bg = rounded_alpha(fx - pad, fy - pad, W - 2 * pad, H - 2 * pad, r)
            # 渐变绿
            t = (fx / W) * 0.35 + (fy / H) * 0.65
            rr = int(0x5F + (0x35 - 0x5F) * t)
            gg = int(0xD4 + (0xC2 - 0xD4) * t)
            bb = int(0x6E + (0x4E - 0x6E) * t)
            # 白色对勾
            d1 = dist_seg(fx / W, fy / H, a[0], a[1], b[0], b[1])
            d2 = dist_seg(fx / W, fy / H, b[0], b[1], c[0], c[1])
            d = min(d1, d2) * W
            tick = max(0.0, min(1.0, (sw / 2 - d) + 0.5))
            if tick > 0:
                rr = int(rr + (255 - rr) * tick)
                gg = int(gg + (255 - gg) * tick)
                bb = int(bb + (255 - bb) * tick)
            a8 = int(round(alpha_bg * 255))
            px += bytes((rr, gg, bb, a8))
    raw = bytes(px)

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)
    print('wrote', path, size)

make(192, 'icon-192.png')
make(512, 'icon-512.png')
make(180, 'apple-touch-icon.png')
