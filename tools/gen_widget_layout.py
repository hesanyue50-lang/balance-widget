#!/usr/bin/env python3
"""生成小组件布局（单一布局，皮肤由代码按风格设置背景）。

要点：
  · 按钮用 ImageView + 矢量图标，固定 34dp×34dp + 5dp 内边距
    —— TextView 的字形受字体影响难以真正居中，图片不会。
  · 根布局的 padding 是「相对可见面板」的，因为 9-patch 的
    内容区标记已设为面板边界，会自动补上阴影留白。
"""

ROOT = "/var/minis/mounts/AIWord/projects/balance-widget/res/layout"

SLOT = '''    <!-- ===== 格子 {i} ===== -->
    <LinearLayout
        android:id="@+id/w_slot{i}"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginLeft="{ml}dp"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/w_slot{i}_n"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:singleLine="true"
            android:ellipsize="end"
            android:textColor="@color/tx2"
            android:textSize="10sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="1dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                android:id="@+id/w_slot{i}_v"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:singleLine="true"
                android:includeFontPadding="false"
                android:textColor="@color/tx"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/w_slot{i}_c"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginLeft="4dp"
                android:singleLine="true"
                android:ellipsize="end"
                android:includeFontPadding="false"
                android:textColor="@color/tx_conv"
                android:textSize="9sp" />
        </LinearLayout>
    </LinearLayout>
'''

rows = []
for r in range(3):
    slots = ""
    for c in range(2):
        i = r * 2 + c + 1
        slots += SLOT.format(i=i, ml=(0 if c == 0 else 12))
    rows.append(f'''    <LinearLayout
        android:id="@+id/w_row{r + 1}"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="horizontal">
{slots}    </LinearLayout>
''')

xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 小组件布局（2 列 × 3 行 = 每页 6 个平台）
     背景（面板 + 按钮底）由 BalanceWidgetProvider.applySkin() 按风格用代码设置，
     不写在这里 —— 桌面会缓存 View 树，布局里新增的资源引用不会生效。 -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <!-- 内容容器：padding 放这里，不能放在带 9-patch 背景的根上
         （背景自带的 padding 会覆盖 View 自身的 padding） -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingLeft="14dp"
        android:paddingRight="14dp"
        android:paddingTop="13dp"
        android:paddingBottom="13dp">

    <!-- 标题栏 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:singleLine="true"
            android:text="@string/widget_title"
            android:textColor="@color/tx2"
            android:textSize="11sp"
            android:textStyle="bold" />

        <ImageView
            android:id="@+id/w_prev"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:padding="8dp"
            android:scaleType="fitCenter"
            android:src="@drawable/ic_chevron_left" />

        <TextView
            android:id="@+id/w_page"
            android:layout_width="wrap_content"
            android:layout_height="32dp"
            android:minWidth="28dp"
            android:gravity="center"
            android:includeFontPadding="false"
            android:text=""
            android:textColor="@color/tx3"
            android:textSize="10sp" />

        <ImageView
            android:id="@+id/w_next"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:padding="8dp"
            android:scaleType="fitCenter"
            android:src="@drawable/ic_chevron_right" />

        <ImageView
            android:id="@+id/w_refresh"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_marginLeft="7dp"
            android:padding="8dp"
            android:scaleType="fitCenter"
            android:src="@drawable/ic_refresh" />
    </LinearLayout>

    <!-- 总资产 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="3dp"
        android:orientation="horizontal"
        android:gravity="bottom">

        <TextView
            android:id="@+id/w_total"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:includeFontPadding="false"
            android:text="—"
            android:textColor="@color/tx"
            android:textSize="25sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/w_sub"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginLeft="8dp"
            android:layout_marginBottom="2dp"
            android:singleLine="true"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:text=""
            android:textColor="@color/tx3"
            android:textSize="9sp" />
    </LinearLayout>

{''.join(rows)}    </LinearLayout>
</LinearLayout>
'''

path = f"{ROOT}/widget_balance.xml"
open(path, "w", encoding="utf-8").write(xml)
print("wrote", path, len(xml), "bytes")

import os
for st in ("neu", "glass", "flat"):
    p = f"{ROOT}/widget_balance_{st}.xml"
    if os.path.exists(p):
        os.remove(p)
        print("removed", p)
