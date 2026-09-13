#!/usr/bin/env python3
"""生成小组件 + 应用内的 9-patch 背景（drawable-nodpi，不做密度缩放）。

── 重要规则（都踩过坑） ─────────────────────────────
1) 四边 1px 边框只能是纯黑(#FF000000)或纯透明，否则 aapt2 报 "found an invalid color"。
   画完阴影必须整条清空边框再画标记。
2) 下/右边框标记 = 内容区(padding)，不是拉伸区；标满整图，padding 交给布局。
3) 9-patch 放 drawable/ 会按 mdpi 解析，在 560dpi 设备上留白放大 3.5 倍把内容挤没
   → 必须放 drawable-nodpi，按原始像素设计（DP = 3.5）。
4) **阴影留白 pad 必须 ≥ offset + blur*2**，否则亮/暗影被图片边界裁断，
   在主体边缘形成突兀硬边（看起来像描边而非浮雕）。← 本次修复
5) 小控件的固定区 2*(pad+radius) 必须小于控件像素高度，否则被撑坏成方块。

设计要点
  · 新拟物：主体色 == 背景色（消除主体硬边），双向柔和阴影
  · 玻璃：半透明 + 顶部高光渐变 + 亮边框，配系统桌面组件
"""
from PIL import Image, ImageDraw, ImageFilter, ImageChops
import os

RES = "/var/minis/mounts/AIWord/projects/balance-widget/res"
DP = 3.5


def hexc(s):
    s = s.lstrip("#")
    if len(s) == 8:                                  # AARRGGBB
        return (int(s[2:4], 16), int(s[4:6], 16), int(s[6:8], 16), int(s[0:2], 16))
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def vgrad(size, c_top, c_bot, radius):
    """垂直渐变 + 圆角裁切（用于玻璃高光）"""
    g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    n = max(1, size - 1)
    for y in range(size):
        t = y / n
        col = tuple(int(c_top[i] + (c_bot[i] - c_top[i]) * t) for i in range(4))
        d.line([(0, y), (size, y)], fill=col)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1],
                                          radius=radius, fill=255)
    g.putalpha(ImageChops.multiply(g.getchannel("A"), mask))
    return g


def build(body_px, radius_px, base, light=None, dark=None,
          blur_px=18, off_px=10, inset=False, border=None, border_w=1,
          pad_factor=2.0, gloss=42):
    """新拟物底板。

    pad_factor：阴影留白倍率。
      2.0  阴影完整不裁，但留白会按 组件尺寸-2×pad 吃掉可见面板；
      1.3  允许最外围（已接近全透明）轻微被裁，换取更大的可见面板。
    """
    # 有阴影才需要留白容纳渐变；纯色块只需 2px
    has_shadow = bool(light and dark)
    pad = int(off_px + blur_px * pad_factor) if has_shadow else 2
    S = body_px + 2 * pad
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    def rr(layer, x0, y0, x1, y1, color, rad):
        ImageDraw.Draw(layer).rounded_rectangle([x0, y0, x1, y1], radius=rad, fill=color)

    if light and dark:
        c1 = light if not inset else dark          # 左上
        c2 = dark if not inset else light          # 右下
        for color, dx, dy in ((c1, -off_px, -off_px), (c2, off_px, off_px)):
            lay = Image.new("RGBA", (S, S), (0, 0, 0, 0))
            rr(lay, pad + dx, pad + dy, S - pad + dx, S - pad + dy, color, radius_px)
            img = Image.alpha_composite(img, lay.filter(ImageFilter.GaussianBlur(blur_px)))

    body = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rr(body, pad, pad, S - pad, S - pad, base, radius_px)
    if border:
        ImageDraw.Draw(body).rounded_rectangle(
            [pad, pad, S - pad, S - pad], radius=radius_px, outline=border, width=border_w)

    # ── 材质质感：顶部内高光 + 垂直微渐变 ──
    # 不占尺寸，纯靠光影提升精致度（新拟物容易显得"廉价"就是因为太平）
    if has_shadow and gloss:
        gloss_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        gd = ImageDraw.Draw(gloss_layer)
        span = S - 2 * pad
        for y in range(pad, S - pad):
            t = (y - pad) / max(1, span)
            # 顶部最亮，快速衰减（模拟顶光照射）
            a = int(gloss * (1 - t) ** 2.2)
            if a <= 0:
                continue
            gd.line([(pad, y), (S - pad, y)], fill=(255, 255, 255, a))
        # 裁成与主体一致的圆角
        m = Image.new("L", (S, S), 0)
        ImageDraw.Draw(m).rounded_rectangle(
            [pad, pad, S - pad, S - pad], radius=radius_px, fill=255)
        gloss_layer.putalpha(ImageChops.multiply(gloss_layer.getchannel("A"), m))
        body = Image.alpha_composite(body, gloss_layer)

        # 顶部内高光线：沿圆角描一条极淡的白边，只保留上半部分
        hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        ImageDraw.Draw(hl).rounded_rectangle(
            [pad + 1, pad + 1, S - pad - 1, S - pad - 1],
            radius=max(1, radius_px - 1),
            outline=(255, 255, 255, 74), width=2)
        hm = Image.new("L", (S, S), 0)
        ImageDraw.Draw(hm).rectangle([0, 0, S, pad + radius_px], fill=255)
        hl.putalpha(ImageChops.multiply(hl.getchannel("A"), hm))
        body = Image.alpha_composite(body, hl)

    img = Image.alpha_composite(img, body)

    # ---- 边框：先清空再画标记 ----
    px = img.load()
    for x in range(S):
        px[x, 0] = (0, 0, 0, 0)
        px[x, S - 1] = (0, 0, 0, 0)
    for y in range(S):
        px[0, y] = (0, 0, 0, 0)
        px[S - 1, y] = (0, 0, 0, 0)

    a, b = pad + radius_px, S - pad - radius_px
    if b <= a:
        a, b = pad + 1, S - pad - 1
    for x in range(a, b + 1):
        px[x, 0] = (0, 0, 0, 255)
    for y in range(a, b + 1):
        px[0, y] = (0, 0, 0, 255)
    for x in range(1, S - 1):
        px[x, S - 1] = (0, 0, 0, 255)
    for y in range(1, S - 1):
        px[S - 1, y] = (0, 0, 0, 255)
    return img


def mix(c1, c2, t):
    """按 t 比例把 c1 向 c2 混合（用于从强调色派生亮/暗影）"""
    a = hexc(c1); b = hexc(c2)
    return "#" + "".join(f"{int(a[i]*(1-t) + b[i]*t):02X}" for i in range(3))


# 主按钮的强调色（与 colors.xml 的 accent 一致）
ACCENT = {"": "#3D6FD6", "-night": "#5B95FF"}


def fixed_px(im):
    """返回固定区像素（沿上边框的标记起点）"""
    w = im.size[0]
    px = im.load()
    marks = [x for x in range(w) if px[x, 0][3] > 128]
    return min(marks) if marks else 0


# ===================== 配色 =====================

LIGHT_NEU = dict(base="#E7ECF4", light="#FFFFFF", dark="#B7C1D2")
NIGHT_NEU = dict(base="#232931", light="#5A6678", dark="#0C0F13")

# 小组件专用新拟物配色：面板比应用内略暗，
# 这样纯白亮影才有对比空间（面板越亮，白亮影越看不见）
WIDGET_NEU = {
    "":       dict(base="#DDE4EE", light="#FFFFFF", dark="#A2AEC0"),
    "-night": dict(base="#232931", light="#5A6678", dark="#0C0F13"),
}

# 玻璃：更透 + 顶部高光（模拟玻璃反光）
# 毛玻璃：低不透明度 + **中性色**（不带蓝调），让壁纸颜色透出来形成"吸色"效果
#   浅色模式 → 白色毛玻璃 + 深色边框勾边
#   深色模式 → 中性深色毛玻璃 + 亮色描边
# 关键：不透明度必须够高，面板才能明确"压在"壁纸之上。
# 太透（<60%）会让亮壁纸把面板推到"中灰"区间 —— 那时黑字白字都不够看。
#   浅色模式 白毛玻璃 ~80%：壁纸色染上来但底子仍是浅的 → 深色文字清晰
#   深色模式 黑毛玻璃 ~78%：壁纸色染上来但底子仍是深的 → 浅色文字清晰
GLASS_BODY = {
    "":      dict(top="#D9FFFFFF", bot="#CCFFFFFF", border="#1F000000"),
    "-night": dict(top="#C714181C", bot="#BF101317", border="#33FFFFFF"),
}

# 小组件按钮：半透明，任何风格下都协调
# 玻璃风格的按钮底：浅色模式用**深色半透明**（白玻璃上白按钮看不见），深色模式用亮色
GLASS_BTN = {"": dict(base="#26000000", border="#1F000000"),
             "-night": dict(base="#33FFFFFF", border="#4DFFFFFF")}

# 应用内元素：文件名 -> (主体px, 圆角dp, 凹陷)
APP = [
    ("card_bg",  280, 20, False),
    ("total_bg", 280, 22, False),
    ("btn_bg",   150,  9, False),
    ("input_bg", 150,  9, True),
]


def run():
    for suffix in ("", "-night"):
        pal = LIGHT_NEU if suffix == "" else NIGHT_NEU
        d = os.path.join(RES, "drawable-night-nodpi" if suffix else "drawable-nodpi")
        os.makedirs(d, exist_ok=True)
        light, dark, base = hexc(pal["light"]), hexc(pal["dark"]), hexc(pal["base"])

        # ---- 应用内：新拟物 ----
        for name, body, raddp, inset in APP:
            small = name in ("btn_bg", "input_bg")
            im = build(body, dp(raddp) if False else int(round(raddp * DP)),
                       base, light, dark,
                       blur_px=10 if small else 20,
                       off_px=6 if small else 12,
                       inset=inset)
            im.save(os.path.join(d, name + ".9.png"))
            print(f"  {name:12} {im.size[0]:3}px  固定区 {fixed_px(im)}px")

        # ---- 小组件：新拟物 ----
        # 平衡点：阴影要够明显，但留白会侵占内容空间（9-patch 的固有代价）
        #   pad = off + blur*2 →  4dp + 6dp*2 = 16dp 上下留白
        # 要让「面板大」又「过渡自然」，**不能靠裁阴影**（那会硬切出边框），
        # 正确做法是：把阴影本身做小，同时保持 pad = off + blur*2 不裁断。
        #   blur=3.5dp, off=2dp → pad = 2 + 7 = 9dp（原 16dp）
        #   面板损失 18dp（原 32dp），且阴影完整柔和。
        # 🔑 关键：**偏移要 ≈ 模糊半径**，亮/暗影才会左右分离。
        #    之前 off=7px 而 blur=12px，两层严重重叠，后画的暗影把亮影压住，
        #    结果只有"右下暗影"、没有"左上亮影" —— 看起来像投影而非浮雕。
        #    off == blur 时分离最清晰，且 pad = off + blur*2 = 3×off 也最小。
        # 🔑 pad_factor 必须 ≈3：PIL 的 GaussianBlur 实际扩散约为 radius 的 3 倍。
        #    按 2 倍留白 → 暗影的模糊尾巴向上蔓延，会把左上亮影整个盖住，
        #    结果只剩"右下暗影"，看起来像投影而非浮雕（这就是"过渡不自然"）。
        wn = WIDGET_NEU[suffix]
        im = build(300, int(round(20 * DP)), hexc(wn["base"]),
                   hexc(wn["light"]), hexc(wn["dark"]),
                   blur_px=int(round(2 * DP)), off_px=int(round(2 * DP)),
                   pad_factor=3.0)
        im.save(os.path.join(d, "widget_bg_neu.9.png"))
        print(f"  {'widget_neu':12} {im.size[0]:3}px  固定区 {fixed_px(im)}px")

        # ---- 小组件：玻璃 ----
        g = GLASS_BODY[suffix]
        pad = 6
        S = 200 + 2 * pad
        canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        grad = vgrad(200, hexc(g["top"]), hexc(g["bot"]), int(round(24 * DP)))
        canvas.alpha_composite(grad, (pad, pad))
        ImageDraw.Draw(canvas).rounded_rectangle(
            [pad, pad, S - pad - 1, S - pad - 1],
            radius=int(round(24 * DP)), outline=hexc(g["border"]), width=1)
        px = canvas.load()
        for x in range(S):
            px[x, 0] = (0, 0, 0, 0); px[x, S - 1] = (0, 0, 0, 0)
        for y in range(S):
            px[0, y] = (0, 0, 0, 0); px[S - 1, y] = (0, 0, 0, 0)
        r = int(round(24 * DP))
        # 上/左 = 拉伸区（避开圆角，防止拉伸时圆角变形）
        for x in range(pad + r, S - pad - r + 1):
            px[x, 0] = (0, 0, 0, 255)
        for y in range(pad + r, S - pad - r + 1):
            px[0, y] = (0, 0, 0, 255)
        # 下/右 = 内容区：**标满整图**，让 9-patch 不自带任何 padding。
        # 内边距完全由代码 (PAD_GLASS/PAD_NEU/PAD_FLAT) 控制，三种风格才可预期地统一调。
        # （曾用 10dp 内缩，会与代码 padding 叠加，导致"改了没反应"）
        inset = 0
        for x in range(pad + inset, S - pad - inset + 1):
            px[x, S - 1] = (0, 0, 0, 255)
        for y in range(pad + inset, S - pad - inset + 1):
            px[S - 1, y] = (0, 0, 0, 255)
        canvas.save(os.path.join(d, "widget_bg_glass.9.png"))
        print(f"  {'widget_glass':12} {S:3}px")

        # ---- 小组件：简约 ----
        b = GLASS_BTN[suffix]
        im = build(200, int(round(16 * DP)), hexc("#FFFFFFFF" if suffix == "" else "#FF1F242C"),
                   light=None, dark=None,
                   border=hexc("#D2D8E2" if suffix == "" else "#333A45"), border_w=1)
        im.save(os.path.join(d, "widget_bg_flat.9.png"))
        print(f"  {'widget_flat':12} {im.size[0]:3}px")

        # ---- 主按钮：强调色 + 新拟物浮雕（与其它按钮风格统一） ----
        acc = ACCENT[suffix]
        im = build(150, int(round(9 * DP)), hexc(acc),
                   hexc(mix(acc, "#FFFFFF", 0.26)),      # 左上亮影：强调色提亮
                   hexc(mix(acc, "#000000", 0.34)),      # 右下暗影：强调色压暗
                   blur_px=10, off_px=6)
        im.save(os.path.join(d, "btn_primary.9.png"))
        print(f"  {'btn_primary':12} {im.size[0]:3}px  固定区 {fixed_px(im)}px")

        # ---- 小组件按钮：三套（跟随小组件风格） ----
        rad = int(round(9 * DP))
        # glass：半透明白
        im = build(120, rad, hexc(b["base"]),
                   light=None, dark=None, border=hexc(b["border"]), border_w=1)
        im.save(os.path.join(d, "wbtn_glass.9.png"))
        # neu：与底板同色 + 浮雕
        im = build(120, rad, base, light, dark, blur_px=10, off_px=6)
        im.save(os.path.join(d, "wbtn_neu.9.png"))
        # flat：实体 + 细边
        im = build(120, rad, hexc("#FFFFFFFF" if suffix == "" else "#2A313BFF"),
                   light=None, dark=None,
                   border=hexc("#D2D8E2" if suffix == "" else "#3A424E"), border_w=1)
        im.save(os.path.join(d, "wbtn_flat.9.png"))
        print(f"  {'wbtn x3':12} {im.size[0]:3}px  固定区 {fixed_px(im)}px")


print("=== 应用内 (drawable-nodpi) ===")
run()
print("done")
