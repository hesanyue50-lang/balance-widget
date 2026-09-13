# API 余额（桌面小组件 + 应用）

Android 应用 + 主屏小组件，查看各家 AI 平台 API 余额。
纯代码查询，**不调用任何 AI 接口、不消耗 token**。

## 现状

- 沙盒内**本地编译**并已安装到手机，包名 `com.minis.balancewidget`
- 当前版本 v5.2（versionCode 43），可**覆盖安装**升级（签名固定，见下）
- 应用界面 + 小组件均已验证运行正常

## 应用界面

- **主界面**：总资产大字（点击即刷新）+ 各平台卡片（每张卡片点击也刷新）+ 顶部「点击刷新」「设置」
- **设置界面**：6 家平台的 Key 输入框（掩码显示）+ 自定义平台 + 保存 + 添加到桌面引导
- 未填 Key 的平台**完全不显示**，填哪个显示哪个

## 支持平台（6 家，均实测网络可达）

| 平台 | 币种 | 接口 |
|---|---|---|
| DeepSeek | CNY | `/user/balance` |
| OpenRouter | USD | `/api/v1/credits`（充值−消耗） |
| 硅基流动 | CNY | `/v1/user/info` |
| 月之暗面 Moonshot | CNY | `/v1/users/me/balance` |
| Novita AI | USD | `/v3/user/balance` |
| Fireworks AI | USD | `/v1/accounts` |

## 自定义平台（OpenAI 兼容）

设置页底部可添加任意平台：**名称 + 余额查询 URL + Key + 取值路径（可空）+ 币种**。

- 取值路径留空 → 自动识别（候选键名浅层匹配 → 深度遍历兜底）
- 识别不出来 → 卡片上**直接显示接口原始返回**，照着填路径即可

## 刷新方式

| 方式 | 位置 |
|---|---|
| 顶部「点击刷新」按钮 | 应用主界面 |
| 点任意卡片 / 总资产区域 | 应用主界面 |
| 小组件上「点击刷新」按钮 | 桌面 |
| 点小组件任意位置 | 桌面 |
| 自动 | 每 30 分钟（Android 小组件下限） |

## 币种显示

- 每个平台显示**原生币种**金额（DeepSeek/硅基流动/Moonshot 为 ¥，OpenRouter/Novita/Fireworks 为 $）
- **外币平台的人民币换算紧跟在金额旁边**，用更小（8.5sp vs 12sp）、更淡（`@color/tx_conv`）的字
- 人民币平台不显示多余换算
- 顶部「总资产」始终是折合人民币的合计

## 小组件布局

**2 列 × N 行网格**（N 由组件实际高度自动决定：`≥176dp` 用 3 行=6 个，否则 2 行=4 个）。
每个格子上下两行：
- 上行：平台名（10sp 次要色）
- 下行：金额（13sp 加粗）+ 紧邻的换算小字（9sp 淡色）

**超出当前页容量时出现翻页控件**（标题栏 `‹ 1/2 ›`），翻页**不重新联网**（读缓存 JSON），
只有点刷新才联网。平台数 ≤ 一页容量时翻页控件自动隐藏。

### 图标用矢量 + ImageView，不用文字

按钮原用 `TextView` 显示 `⟳`/`‹`/`›` 字符 —— **字形受字体影响，无法真正居中**，
而且尺寸不可控。现改为 30dp 的 `ImageView` + VectorDrawable：

| 图标 | 来源 |
|---|---|
| 刷新 | **Material Design 官方 refresh 路径**（Apache-2.0） |
| 左右 | Lucide chevron-left / chevron-right（ISC） |

图标本身用 `<group scaleX/scaleY>` 缩到 0.72 —— 因为
**ImageView 带背景时，自身 `padding` 会被 9-patch 背景的 padding 覆盖**，
没法靠 padding 收缩图形。

图标统一画成白色，再用 `RemoteViews.setInt(id, "setColorFilter", color)` 染成主题色。

### 布局的 padding 必须放在内层容器

带 9-patch 背景的 View，**自身 padding 会被背景的 padding 覆盖**。
（实测根布局设 16dp，只有背景自带的 4px 生效。）

### ⚠️ 但应用内元素要反过来：padding = 阴影留白 + 视觉间距

应用内的卡片/按钮用 9-patch（有柔和阴影），**阴影留白是算在 View 内部的**。
所以设了 padding 之后，先被阴影留白吃掉，剩下的才是真正到**可见面板边缘**的距离。

实测：`item_platform` 设 `padding=16dp`，而卡片 9-patch 的阴影留白是 52px(15dp)，
结果内容距面板边缘只有 **4px ≈ 1dp** —— 几乎贴着边（这就是"内容偏外"的原因）。

| 元素 | 阴影留白 | 想要视觉间距 | 应设 padding |
|---|---|---|---|
| 卡片（item_platform / total_bg） | 15dp | 16dp | **31dp** |
| 按钮（btn_bg） | 7.4dp | 15dp | **22dp** |
| 输入框（input_bg） | 7.4dp | 12dp | **20dp** |

> 小组件是例外：它的 9-patch 内容区标记已设为面板边界，
> 框架会自动补上阴影留白，所以布局只需写真实的视觉间距（14dp）。

### 组件尺寸由桌面决定

`minHeight=200dp` / `targetCellHeight=3` 只是**声明**，最终尺寸由桌面给。
`updateAppWidgetOptions` 想推动尺寸，但 vivo 桌面忽略它。
所以做了**按实际高度自适应行数**（`rowsFor()` 读 `OPTION_APPWIDGET_MIN_HEIGHT`），
高度不够就只画 2 行，避免下方留大片空白。

## 小组件外观风格（可切换）

设置页「小组件外观」可选三种皮肤，**给小组件单独设置**（应用内固定新拟物）：

| 风格 | 观感 | 适用场景 |
|---|---|---|
| **玻璃**（默认） | 半透明深色面板 + 顶部高光 + 亮描边 | **配系统桌面组件**（天气/日历那类），壁纸会透出来 |
| 新拟物 | 不透明同色 + 浮雕阴影 | 想要立体质感、桌面比较素时 |
| 简约 | 不透明纯色 + 细边框 | 追求清晰、信息优先 |

**右上角的刷新按钮会跟随风格变化**（玻璃→半透明白胶囊；新拟物→同色浮雕；简约→实体+细边）。

改完点「保存并刷新」生效。

### 毛玻璃：材质吸色 + 文字自适应对比度

玻璃风格的两个要点：

**① 材质要真的"吸色"** —— 面板必须**低不透明度 + 中性色**（不带蓝调），
壁纸颜色才能透过来。实测：

| 主题 | 面板色 | 不透明度 | 效果 |
|---|---|---|---|
| 浅色 | `#A6FFFFFF` → `#99FFFFFF` | ~62% | 白色毛玻璃，壁纸颜色染上来 |
| 深色 | `#8014181C` → `#73101317` | ~47% | 深色毛玻璃 |

> 早期版本用了 `#E64A5464`（90% 不透明的**蓝灰**），结果浅色下是一块死沉的深色板，
> 切绿色壁纸也完全不变色 —— 这就是"不吸色"的原因。

**② 文字跟随主题做黑白预设切换**（不是写死浅色）：

| 主题 | 文字色 | 说明 |
|---|---|---|
| 浅色（白毛玻璃） | `glass_tx #1A1F28` 深色 | 黑预设 |
| 深色（黑毛玻璃） | `glass_tx #F2F6FC` 浅色 | 白预设 |

同样是 `values/` + `values-night/` 两套，由 `applyGlassText()` 在代码里统一刷。

> 曾走过的弯路：一度把玻璃文字**写死成浅色**（假设面板恒为深色），
> 结果浅色模式下整块面板难看 —— 因为面板其实是半透明的，颜色由壁纸决定。
> **正确做法是让面板与文字都跟随系统主题。**

按钮底同理要分主题：浅色模式用**深色半透明**（`#26000000`），否则白玻璃上的白按钮看不见。

### ⚠️ 关键实现：皮肤必须用代码设置，不能写进布局

小组件被桌面缓存后，**只会复用旧的 View 树**。往布局 XML 里新增
`android:background="@drawable/xxx"` 这类引用，**即使换了 layoutId 也可能不生效**
（桌面按 view id 复用视图）。

所以皮肤走代码：

```java
v.setInt(R.id.widget_root, "setBackgroundResource", bgOf(style));
v.setInt(R.id.w_refresh, "setBackgroundResource", btnBgOf(style));
```

实测对比：写进布局时面板完全不显示；改成 `setInt` 后立即生效。
若仍不生效，重启桌面（`am force-stop <launcher>`）强制重建。

## 顶部栏与刘海适配

主界面与设置界面用**同一套逻辑**（`UiInsets.java`，避免两处不一致）：

1. **窗口内容延伸到系统栏后** —— `setDecorFitsSystemWindows(false)`
   （API < 30 用 `SYSTEM_UI_FLAG_LAYOUT_*`）。否则顶部栏背景盖不住状态栏，会留一条分界。
2. **顶部栏 paddingTop = 系统栏高度 + 12dp**，背景随之铺满状态栏区域。
3. **滚动区 paddingTop = 顶部栏实际高度** —— 内容正好从其下方开始，**不会被遮挡**
   （用 `post{}` + `addOnLayoutChangeListener` 双保险同步，因为 insets 回调不总会触发）。
4. **滚动区 paddingBottom = 手势条高度 + 原有留白**。

### 顶部栏用不透明背景，不用半透明

半透明虽然好看，但**内容滚过时文字重叠会干扰信息识别**。
所以顶部栏直接用页面背景色 `@color/bg` 填充，状态栏与顶部栏连成一片，
只在底边留一条极淡的分隔线。

> 另注：Android 的窗口级模糊（`FLAG_BLUR_BEHIND`）只能模糊**窗口背后**的内容，
> **无法模糊同一窗口内滚过的内容**，所以做不了"滚动毛玻璃"。

## 界面风格

应用内是**新拟物（Neumorphism）**：元素表面色与背景同色系，靠
左下暗影 / 右上亮影的位移柔和阴影区分层次，像从背景"挤"出来一样。
输入框与标签用**内凹**（阴影方向相反）。

### 做出"精致"而非"廉价"的三个要点

新拟物很容易显得平，靠这三条拉开质感：

1. **顶部内高光 + 垂直微渐变**（`gloss` 参数）
   主体叠一层从顶部向下快速衰减的白色（`(1-t)^2.2`），模拟顶光照射 ——
   不占尺寸，纯靠光影提升精致度。
2. **顶层内高光线**
   沿圆角描一条 2px 半透明白边，只保留上半部分。
3. **亮/暗影必须分离**（最容易被忽略）
   `offset` 要 **≈ `blur`**。若偏移远小于模糊半径，两层阴影严重重叠，
   后画的暗影会把亮影盖住 → 只剩"右下暗影"，看起来像投影而不是浮雕。

### ⚠️ 留白系数必须 ≈3（PIL 高斯模糊的坑）

`pad_factor` 控制阴影留白。**PIL 的 `GaussianBlur` 实际扩散约为 radius 的 3 倍**，
按 2 倍留白会让暗影的模糊尾巴溢出边界、盖住亮影。

| pad_factor | 结果 |
|---|---|
| 2.0 | 阴影被裁断 → 出现硬边，且亮影被压住 |
| 1.3 | 面板大但**硬切出边框**，过渡生硬 |
| **3.0** | ✅ 阴影完整柔和，亮暗分离 |

### 小组件用独立的配色

小组件面板比应用内**略暗**（`WIDGET_NEU`），因为面板越亮、纯白亮影越看不见。
应用内则保持明亮（否则卡片比背景暗会显得"凹陷"）。

### 阴影留白会吃掉可见面板

9-patch 的 `pad` 是透明的，但算在组件内部 → **可见面板 = 组件尺寸 − 2×pad**。
新拟物原为 16dp（损失 32dp），明显比玻璃/简约小；调整后为 8.9dp（损失 17.8dp），
三者观感接近。

Android 的 `shape` 只能画硬边色块，做不出柔和阴影，所以这些底板是
**用 PIL 渲染 + 高斯模糊生成 `.9.png` 九宫格**（见 `tools/gen_neu_nodpi.py`）。

### 两个必须遵守的 9-patch 规则（踩过的坑）

1. **四边 1px 边框只能含纯黑 `#FF000000` 或纯透明**。模糊阴影溢出到边框会让
   aapt2 报 `found an invalid color`。→ 画完阴影必须**整条清空边框再画标记**。
2. **下/右边框标记 = 内容区(padding)，不是拉伸区**。只标在中间会把内容 padding
   压成一小块（文字被挤成竖排、控件错位）。→ 这里标满整图，padding 交给布局的
   `android:padding` 控制。

### 为什么放 `drawable-nodpi`

9-patch 放 `drawable/` 会按 **mdpi(1x)** 解析，在本机 **560dpi(3.5x)** 下
阴影留白被放大 3.5 倍（几十 dp），把内容挤没。`nodpi` 下按原始像素渲染，
尺寸可精确控制（脚本里 `DP = 3.5` 即换算系数）。

## 应用图标

`res/drawable/ic_launcher.xml` —— **手写矢量**，非位图：

- 意象：三根递增的柱子（余额）+ 一条上升折线（增长），末端一个圆点
- 配色：新拟物底板（`#E7ECF4` / 亮影 `#FFFFFF` / 暗影 `#BAC4D4`）+ 柱体蓝 `#3D6FD6` + 折线绿 `#3AAE84`
- 柱子左侧带高光条，呼应新拟物质感
- 主体只占中间约 55%，四周留白充足 → 圆形蒙版裁剪不会切到内容

> 注：曾尝试用 Pollinations 免费生图模型，但产出全是 3D 产品照风格
> 且**右下角水印无法去除**（`nologo` 参数无效），不适合做图标，故改手写矢量。

## 添加到桌面

**vivo 桌面不支持标准的一键添加**（`requestPinAppWidget` 返回 supported 但静默无响应，
其 `VivoAddWidgetActivity` 也不允许第三方唤起），所以应用内点「添加到桌面」会**弹出图文引导**：

1. 回到桌面，长按空白处
2. 选择「小组件」
3. 在列表里找到「API 余额」
4. 按住拖到桌面合适位置

## 隐私

- Key 存在应用私有 `SharedPreferences`（`/data/data/com.minis.balancewidget/`），不外传、不落共享存储
- 输入框强制密码掩码（`PasswordTransformationMethod`）
- 只发往你填了 Key 的平台的官方域名
- 汇率源 `api.frankfurter.dev`

## 覆盖安装

签名用固定 debug keystore（`/opt/android-dev/keystore/debug.keystore`），
SHA-256 `ffd72134…c439f33`，**与首版完全一致** → 每次升级直接 `pm install -r`，数据保留。

升级时只需提高 `AndroidManifest.xml` 里的 `versionCode`。

## 构建

```sh
cd /var/minis/mounts/AIWord/projects/balance-widget
sh build.sh          # 产出 com.minis.balancewidget.apk
```

安装：

```sh
android-shizuku-cli exec 'cp /storage/emulated/0/others/AIWord/projects/balance-widget/com.minis.balancewidget.apk /data/local/tmp/a.apk && pm install -r /data/local/tmp/a.apk'
```

沙盒是 aarch64，靠 **qemu-user + x86_64 glibc sysroot** 跑官方 aapt2/zipalign；
d8 必须用 Termux 版（build-tools 自带的那个解析闭包匿名类会崩）。
完整步骤见 skill `openminis-android-dev`。

## 文件

| 文件 | 作用 |
|---|---|
| `AndroidManifest.xml` | 组件声明 |
| `res/values/colors.xml` + `res/values-night/colors.xml` | 浅色 / 深色两套配色 |
| `res/drawable-nodpi/` + `drawable-night-nodpi/` | 九宫格底板：新拟物/玻璃/简约 + 各自按钮（PIL 生成） |
| `tools/gen_bg.py` | 九宫格重新生成脚本（改配色/圆角后重跑） |
| `tools/gen_widget_layout.py` | 小组件布局生成脚本（改行列/尺寸后重跑） |
| `res/values/styles.xml` + `values-night/` | 两套主题 |
| `res/drawable/ic_launcher.xml` | 应用图标（手写矢量） |
| `src/.../UiInsets.java` | 刘海/状态栏/手势条适配（两个界面共用） |
| `res/drawable/ic_{refresh,chevron_left,chevron_right}.xml` | 小组件按钮图标（Material / Lucide 官方路径） |
| `res/layout/activity_main.xml` | 主界面 |
| `res/layout/activity_settings.xml` | 设置界面 |
| `res/layout/item_platform.xml` | 平台卡片（动态填充） |
| `res/layout/widget_balance.xml` | 小组件布局（单一布局，皮肤由代码设置） |
| `src/.../MainActivity.java` | 主界面逻辑 |
| `src/.../SettingsActivity.java` | 设置 + 自定义平台 + 添加桌面引导 |
| `src/.../BalanceFetcher.java` | 6 家平台 + 自定义平台的抓取与解析 |
| `src/.../BalanceWidgetProvider.java` | 小组件（含翻页、结果缓存、风格切换） |
| `build.sh` | 构建脚本 |

## 已知边界

- 未设 `previewImage`，小组件选择器里显示应用图标
- **组件尺寸由桌面决定**：声明 3 行格高（200dp）但 vivo 桌面仍给 2 格（144dp），
  故按实际高度自适应行数：144dp 时只显示 2 行（4 个平台）。想要 3 行需手动拉高组件
- **翻页功能未经满页实测**：只有 2 个平台有 Key（≤6 个），页数恒为 1，翻页控件按设计隐藏；
  代码路径（缓存读取 / 页码钳制 / 控件显隐）已就绪但无真实多页数据验证
- 硅基流动等平台的字段名未经真实 Key 验证，已做深度遍历兜底
- 小组件布局仅用 RemoteViews 支持的控件（不能用自定义 View / ScrollView）
- 小组件玻璃/简约皮为**半透明**，观感取决于壁纸；壁纸太亮时对比度会下降，可换「简约」风格
- 换风格需点「保存并刷新」，小组件不会自动重绘
