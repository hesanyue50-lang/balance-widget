#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统一 App 与小组件的视觉资源。

风格定为「扁平 + 柔阴影」（现代 Material 观感），所有圆角共用一套尺度，
让小组件、应用内卡片、弹窗、按钮看起来是一个东西。

生成内容（日间 + 夜间两套）：
    card_bg      卡片 / 弹窗          —— 浅底 + 柔阴影
    wg_bg        桌面小组件底板        —— 同卡片
    total_bg     总资产卡（强调）      —— 同卡片
    btn_bg       次要按钮             —— 纯色圆角，无阴影
    btn_primary  主按钮               —— 强调色圆角
    input_bg     输入框               —— 内凹色圆角

⚠️ 9-patch 铁律：
  1. 高斯模糊实际扩散 ≈ radius × 3，留白按 off + blur*3 算，否则阴影被硬切
  2. 四边 1px 标记只能纯黑或纯透明
  3. 「内容区标记」会覆盖布局里的 padding，边距必须在这里定
"""
from PIL import Image, ImageDraw, ImageFilter

RADIUS_CARD = 60        # 卡片类圆角
RADIUS_CTRL = 24        # 按钮 / 输入框圆角


def _shadowed(w, h, radius, color, shadow, off, blur, hl):
    pad = off + blur * 3
    W, H = w + pad * 2, h + pad * 2
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    sh = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [pad, pad + off, pad + w, pad + h + off], radius=radius, fill=shadow)
    img = Image.alpha_composite(img, sh.filter(ImageFilter.GaussianBlur(blur)))

    cd = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(cd).rounded_rectangle([pad, pad, pad + w, pad + h],
                                         radius=radius, fill=color)
    img = Image.alpha_composite(img, cd)

    if hl:
        hlt = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(hlt).rounded_rectangle(
            [pad + 1, pad + 1, pad + w - 1, pad + h - 1],
            radius=radius - 1, outline=hl, width=1)
        img = Image.alpha_composite(img, hlt)
    return img, pad, W, H


def _plain(w, h, radius, fill, stroke=None, stroke_w=0):
    """无阴影的纯色圆角（按钮 / 输入框）"""
    W, H = w, h
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=radius, fill=fill,
                        outline=stroke, width=stroke_w)
    return img, 0, W, H


def _save_9patch(img, pad, W, H, radius, content, path):
    out = Image.new('RGBA', (W + 2, H + 2), (0, 0, 0, 0))
    out.paste(img, (1, 1))
    px = out.load()
    B = (0, 0, 0, 255)
    for x in range(1 + pad + radius, W + 1 - pad - radius):
        px[x, 0] = B
    for y in range(1 + pad + radius, H + 1 - pad - radius):
        px[0, y] = B
    for y in range(1 + pad + max(content, 1), H + 1 - pad - max(content, 1)):
        px[W + 1, y] = B
    for x in range(1 + pad + max(content, 1), W + 1 - pad - max(content, 1)):
        px[x, H + 1] = B
    out.save(path)
    print('   %-18s %dx%d' % (path.split('/')[-1], out.size[0], out.size[1]))


THEMES = [
    # 目录后缀,  卡片底色,             阴影色,              按钮底,              主按钮,              输入框底,      高光
    ('drawable-nodpi',       (243, 245, 249, 255), (35, 45, 65, 58),  (233, 237, 243, 255), (77, 107, 254, 255), (233, 237, 243, 255), (255, 255, 255, 165)),
    ('drawable-night-nodpi', (30, 34, 42, 255),    (0, 0, 0, 130),    (42, 47, 56, 255),    (91, 123, 254, 255), (42, 47, 56, 255),    (255, 255, 255, 26)),
]

ROOT = '/var/minis/mounts/AIWord/projects/balance-widget/res/'

for folder, card_c, shadow_c, btn_c, primary_c, input_c, hl_c in THEMES:
    print(folder + ':')
    base = ROOT + folder + '/'

    # 卡片类：卡片 / 小组件 / 总资产（同款，尺寸不同）
    for name, w, h, content in [('card_bg', 300, 200, 24),
                                ('wg_bg', 240, 160, 24),
                                ('total_bg', 300, 150, 26)]:
        img, pad, W, H = _shadowed(w, h, RADIUS_CARD, card_c, shadow_c, 3, 8, hl_c)
        _save_9patch(img, pad, W, H, RADIUS_CARD, content, base + name + '.9.png')

    # 控件类：次要按钮 / 主按钮 / 输入框
    for name, col, rad in [('btn_bg', btn_c, RADIUS_CTRL),
                           ('btn_primary', primary_c, RADIUS_CTRL),
                           ('input_bg', input_c, 20)]:
        img, pad, W, H = _plain(120, 60, rad, col)
        _save_9patch(img, pad, W, H, rad, 10, base + name + '.9.png')


# ---------- 小组件毛玻璃底 ----------
# 内容区标记故意不设：设了会让包括背景图在内的子元素内缩，表现为"背景留了一圈边框"
def _plain_glass(path, w, h, radius, fill, hl_alpha):
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=fill)
    if hl_alpha > 0:
        ImageDraw.Draw(img).rounded_rectangle([1, 1, w - 2, h - 2],
                                              radius=radius - 1,
                                              outline=(255, 255, 255, hl_alpha), width=1)
    out = Image.new('RGBA', (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(img, (1, 1))
    px = out.load()
    B = (0, 0, 0, 255)
    for x in range(1 + radius, w + 1 - radius):
        px[x, 0] = B
    for y in range(1 + radius, h + 1 - radius):
        px[0, y] = B
    out.save(path)

for folder, fill_l, fill_d, hl_l, hl_d in [
        ('drawable-nodpi', (250, 251, 253, 138), (24, 28, 36, 134), 80, 26)]:
    b = ROOT + folder + '/'
    _plain_glass(b + 'wg_glass_l.9.png', 380, 190, RADIUS_CARD, fill_l, hl_l)
    _plain_glass(b + 'wg_glass_d.9.png', 380, 190, RADIUS_CARD, fill_d, hl_d)
print('毛玻璃底已生成')
