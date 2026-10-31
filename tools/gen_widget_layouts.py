#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""七套小组件布局生成器。

之前七套是同一份模板复制出来只改颜色，视觉上等于没换 —— 白底那两套
（Editorial / Swiss）几乎分不出来。这里改成七种**不同的排版语言**：

  0/1 Aurora  玻璃柔光，双列胶囊
  2   Editorial 衬线杂志，单栏，细实线分隔，大标题
  3   Swiss    海报极简，单栏，超细巨型数字，一条粗黑线
  4   Devtool  终端等宽，单栏，暗绿框，像命令行输出
  5   Brutal   野兽派，双列，荧光黄 + 黑粗线 + 黑底标签块
  6   Clay     柔和，双列，大圆角 + 浅色胶囊

结构约束：8 个 slot / 4 个 row / 3 个 div 的 id 一个都不能少，
WidgetRenderer 靠 id 控制可见性。
"""

CFG = [
    dict(idx=0, name="Aurora 亮", desc="玻璃柔光 · 双列胶囊",
         font=None, orient="horizontal", pad=16, gap=5,
         total=34, total_bold=True, total_grav="center", total_top=6,
         title=11.5, title_caps=False, title_spacing=0, title_grav="left",
         sub=10, sub_grav="center", sub_top=2,
         div_h=0, div_color="#00000000",
         slot_pad=6, slot_bg="@drawable/wslot_pill", slot_gap=5,
         nsize=11.5, val=15, val_bold=True,
         frost=True, deco=None, header_bg=None, header_text="#FF5A6472"),
    dict(idx=1, name="Aurora 暗", desc="玻璃柔光 · 深色双列胶囊",
         font=None, orient="horizontal", pad=16, gap=5,
         total=34, total_bold=True, total_grav="center", total_top=6,
         title=11.5, title_caps=False, title_spacing=0, title_grav="left",
         sub=10, sub_grav="center", sub_top=2,
         div_h=0, div_color="#00000000",
         slot_pad=6, slot_bg="@drawable/wslot_pill_dark", slot_gap=5,
         nsize=11.5, val=15, val_bold=True,
         frost=True, deco=None, header_bg=None, header_text="#FF9AA6B8"),
    dict(idx=2, name="Editorial", desc="衬线杂志 · 单栏细线",
         font="serif", orient="vertical", pad=18, gap=0,
         total=30, total_bold=False, total_grav="left", total_top=10,
         title=21, title_caps=False, title_spacing=0, title_grav="left",
         sub=10.5, sub_grav="left", sub_top=4,
         div_h=1, div_color="#FFD8D2C8",
         slot_pad=7, slot_bg=None, slot_gap=0,
         nsize=13, val=15, val_bold=False,
         frost=False, deco="rule2", header_bg=None, header_text="#FF14110E"),
    dict(idx=3, name="Swiss", desc="海报极简 · 超细巨型数字",
         font="sans-serif-light", orient="vertical", pad=15, gap=0,
         total=52, total_bold=False, total_grav="left", total_top=14,
         title=9, title_caps=True, title_spacing=0.32, title_grav="left",
         sub=9.5, sub_grav="left", sub_top=2,
         div_h=0, div_color="#00000000",
         slot_pad=4, slot_bg=None, slot_gap=1,
         nsize=11, val=12.5, val_bold=False,
         frost=False, deco="bar3", header_bg=None, header_text="#FF000000"),
    dict(idx=4, name="Devtool", desc="终端等宽 · 命令行输出",
         font="monospace", orient="vertical", pad=14, gap=0,
         total=26, total_bold=True, total_grav="left", total_top=8,
         title=11, title_caps=False, title_spacing=0, title_grav="left",
         sub=10, sub_grav="left", sub_top=3,
         div_h=1, div_color="#FF22331F",
         slot_pad=4, slot_bg=None, slot_gap=0,
         nsize=11.5, val=13, val_bold=False,
         frost=False, deco=None, header_bg=None, header_text="#FF6E8F72"),
    dict(idx=5, name="Brutalism", desc="野兽派 · 黑粗线 + 荧光黄",
         font="sans-serif-black", orient="horizontal", pad=13, gap=3,
         total=48, total_bold=True, total_grav="left", total_top=10,
         title=12, title_caps=False, title_spacing=0, title_grav="left",
         sub=10.5, sub_grav="left", sub_top=3,
         div_h=2, div_color="#FF000000",
         slot_pad=5, slot_bg=None, slot_gap=3,
         nsize=12, val=16, val_bold=True,
         frost=False, deco="block", header_bg="#FF000000", header_text="#FFFFF44F"),
    dict(idx=6, name="Soft Clay", desc="柔和陶土 · 大圆角胶囊",
         font=None, orient="horizontal", pad=15, gap=7,
         total=32, total_bold=False, total_grav="center", total_top=8,
         title=12, title_caps=False, title_spacing=0, title_grav="left",
         sub=10, sub_grav="center", sub_top=2,
         div_h=0, div_color="#00000000",
         slot_pad=9, slot_bg="@drawable/wslot_clay", slot_gap=7,
         nsize=12, val=15, val_bold=False,
         frost=False, deco=None, header_bg=None, header_text="#FF767676"),
]

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!-- {name}：{desc} -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w2_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/wg_t{idx}"
    android:padding="{pad}dp">
{frost}
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">
'''

FROST = '''
        <!-- 玻璃高光：只在左上角铺一层极淡的白，制造"有厚度"的错觉 -->
        <View
            android:id="@+id/w2_frost"
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:background="@drawable/wg_frost" />
'''


def header_row(c):
    """标题行：title / page / prev / next / refresh"""
    bg = ''
    tpad = '        '
    if c['header_bg']:
        bg = ('            android:background="%s"\n'
              '            android:paddingStart="8dp"\n'
              '            android:paddingEnd="8dp"\n'
              '            android:paddingTop="4dp"\n'
              '            android:paddingBottom="4dp"\n') % c['header_bg']
    caps = '            android:textAllCaps="true"\n' if c['title_caps'] else ''
    sp = ('            android:letterSpacing="%s"\n' % c['title_spacing']) if c['title_spacing'] else ''
    fam = ('            android:fontFamily="%s"\n' % c['font']) if c['font'] else ''
    return '''
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="{grav}">
{bg}
            <TextView
                android:id="@+id/w2_title"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:minWidth="0dp"
                android:singleLine="true"
                android:ellipsize="end"
                android:includeFontPadding="false"
{fam}{caps}{sp}                android:textColor="{hcolor}"
                android:textSize="{tsize}sp"
                android:text="API 余额" />

            <TextView
                android:id="@+id/w2_page"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:minWidth="0dp"
                android:includeFontPadding="false"
                android:textColor="{hcolor}"
                android:textSize="10.5sp" />

            <ImageView
                android:id="@+id/w2_prev"
                android:clickable="true"
                android:focusable="true"
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:layout_marginStart="2dp"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_chevron_left"
                android:contentDescription="上一页" />

            <ImageView
                android:id="@+id/w2_next"
                android:clickable="true"
                android:focusable="true"
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_chevron_right"
                android:contentDescription="下一页" />

            <ImageView
                android:id="@+id/w2_refresh"
                android:clickable="true"
                android:focusable="true"
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:layout_marginStart="6dp"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_refresh"
                android:contentDescription="刷新" />
        </LinearLayout>
'''.format(grav=c['title_grav'], bg=bg, fam=fam, caps=caps, sp=sp,
           hcolor=c['header_text'], tsize=c['title'])


def deco(c):
    """主数字下面的静态装饰。"""
    kind = c['deco']
    if kind == 'rule2':
        return '''
        <TextView
            android:layout_width="match_parent"
            android:layout_height="2dp"
            android:layout_marginTop="10dp"
            android:background="#FF14110E" />
'''
    if kind == 'bar3':
        return '''
        <TextView
            android:layout_width="match_parent"
            android:layout_height="3dp"
            android:layout_marginTop="10dp"
            android:background="#FF000000" />
'''
    if kind == 'block':
        return '''
        <TextView
            android:layout_width="56dp"
            android:layout_height="6dp"
            android:layout_marginTop="8dp"
            android:background="#FF000000" />
'''
    return ''


def slot(n, c):
    """单个平台格：色点 + 名称 + 金额"""
    vertical = c['orient'] == 'vertical'
    w = 'match_parent' if vertical else '0dp'
    weight = '' if vertical else '\n                    android:layout_weight="1"'
    bg = ('\n                    android:background="%s"' % c['slot_bg']) if c['slot_bg'] else ''
    fam = ('\n                    android:fontFamily="%s"' % c['font']) if c['font'] else ''
    bold = '\n                    android:textStyle="bold"' if c['val_bold'] else ''
    return '''
                <LinearLayout
                    android:id="@+id/w2_slot{n}"
                    android:layout_width="{w}"
                    android:layout_height="wrap_content"{weight}
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:padding="{spad}dp"{bg}>

                    <TextView
                        android:id="@+id/w2_dot{n}"
                        android:layout_width="7dp"
                        android:layout_height="7dp"
                        android:layout_marginEnd="{dgap}dp"
                        android:background="@color/{accent}" />

                    <TextView
                        android:id="@+id/w2_name{n}"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:minWidth="0dp"
                        android:singleLine="true"
                        android:ellipsize="end"
                        android:includeFontPadding="false"{fam}
                        android:textColor="@color/ws{idx}_tx2"
                        android:textSize="{nsize}sp" />

                    <TextView
                        android:id="@+id/w2_val{n}"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:includeFontPadding="false"{fam}{bold}
                        android:textColor="@color/ws{idx}_tx"
                        android:textSize="{vsize}sp" />
                </LinearLayout>
'''.format(n=n, w=w, weight=weight, spad=c['slot_pad'], bg=bg, fam=fam, bold=bold,
           dgap=5 if c['slot_pad'] < 6 else 7,
           accent='ws%d_accent' % c['idx'], idx=c['idx'],
           nsize=c['nsize'], vsize=c['val'])


def row(n, c):
    """一行：两个 slot（横向）或两个 slot 垂直堆叠（单栏）"""
    vertical = c['orient'] == 'vertical'
    orient = 'vertical' if vertical else 'horizontal'
    top = ('\n                    android:layout_marginTop="%ddp"' % c['slot_gap']) if (vertical and c['slot_gap']) else ''
    return '''
            <LinearLayout
                android:id="@+id/w2_row{rn}"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"{top}
                android:orientation="{orient}"
                android:gravity="center_vertical">

{slots}            </LinearLayout>
'''.format(rn=n, top=top, orient=orient, slots=slot(2 * n - 1, c) + slot(2 * n, c))


def div(n, c):
    if c['div_h'] <= 0:
        return '''
            <TextView
                android:id="@+id/w2_div{n}"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:visibility="gone" />
'''.format(n=n)
    return '''
            <TextView
                android:id="@+id/w2_div{n}"
                android:layout_width="match_parent"
                android:layout_height="{h}dp"
                android:layout_marginTop="1dp"
                android:visibility="gone"
                android:background="{col}" />
'''.format(n=n, h=c['div_h'], col=c['div_color'])


def gen(c):
    total_fam = ('\n        android:fontFamily="%s"' % c['font']) if c['font'] else ''
    bold = '\n        android:textStyle="bold"' if c['total_bold'] else ''
    frost = ("""
    <!-- 玻璃高光：背景之上、内容之下，只在顶部露出淡淡一层白 -->
    <ImageView
        android:id="@+id/w2_frost"
        android:scaleType="fitXY"
        android:visibility="gone"
        android:layout_width="match_parent"
        android:layout_height="72dp"
        android:layout_gravity="top"
        android:background="@drawable/wg_frost" />
""" if c['frost'] else "")
    out = HEADER.format(name=c['name'], desc=c['desc'], idx=c['idx'], pad=c['pad'], frost=frost)
    out += header_row(c)
    out += '''
        <TextView
            android:id="@+id/w2_total"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="{ttop}dp"
            android:includeFontPadding="false"
            android:gravity="{tgrav}"{tfam}{tbold}
            android:textColor="@color/ws{idx}_tx"
            android:textSize="{tsize}sp"
            android:text="—" />
'''.format(ttop=c['total_top'], tgrav=c['total_grav'],
           tfam=total_fam, tbold=bold, idx=c['idx'], tsize=c['total'])
    out += deco(c)
    out += '''
        <TextView
            android:id="@+id/w2_sub"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="{stop}dp"
            android:includeFontPadding="false"
            android:singleLine="true"
            android:ellipsize="end"
            android:gravity="{sgrav}"
            android:textColor="@color/ws{idx}_tx3"
            android:textSize="{ssize}sp" />
'''.format(stop=c['sub_top'], sgrav=c['sub_grav'], idx=c['idx'], ssize=c['sub'])

    # 列表区：撑满剩余高度
    out += '''
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:layout_marginTop="8dp"
            android:orientation="vertical">
'''
    out += row(1, c) + div(1, c) + row(2, c) + div(2, c) + row(3, c) + div(3, c) + row(4, c)
    out += '''        </LinearLayout>

    </LinearLayout>
'''
    out += '''</FrameLayout>
'''
    return out


if __name__ == '__main__':
    for c in CFG:
        path = 'res/layout/widget_style%d.xml' % c['idx']
        with open(path, 'w', encoding='utf-8') as f:
            f.write(gen(c))
        print('写入', path)
