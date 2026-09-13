# AI 余额小组件

> 📥 **[点击下载 APK](https://github.com/hesanyue50-lang/balance-widget/raw/main/BalanceWidget.apk)**

一个 Android 桌面小组件，把各家 AI 平台的余额/用量聚合到桌面卡片上，一眼看清还剩多少钱。

## 功能

- 桌面小组件实时显示各平台余额，支持 USD/CNY 自动汇率折算
- 应用内看板：平台卡片列表，点卡片可刷新 / 看密钥 / 直达控制台 / 直达充值页
- 低余额预警通知（阈值自定义）
- 充值账本（Ledger）：记录余额快照与充值曲线
- 密钥加密存储（Keystore 级加密），不上传任何服务器
- 阿里云百炼双计费模式：余额制（查账户余额）/ 订阅制 Token Plan（查订阅实例与到期时间）

## 支持平台

| 平台 | 类型 | 自动查询 |
|---|---|---|
| DeepSeek | 余额 | ✅ |
| OpenRouter | 余额 | ✅ |
| 七牛云 AI | 月消费 | ✅ |
| 硅基流动 | 余额 | ✅ |
| 月之暗面 Moonshot | 余额 | ✅ |
| 智谱 AI | 余额 | ✅ |
| 阿里云百炼 | 余额制/订阅制 | ✅（需阿里云 AccessKey） |
| 火山方舟 / 讯飞星火 / 书生 / 阶跃星辰 / 优云智算 | 余额 | ✅ |
| 魔搭 ModelScope | 免费额度 | 展示 |

## 安装

1. 下载 APK（上面按钮）
2. 允许安装未知来源应用
3. 桌面长按 → 添加小组件

<details>
<summary><strong>开发者详阅</strong>（点击展开）</summary>

### 构建

```bash
sh build.sh
```

纯代码 UI（无 XML 布局依赖主题），aapt2 + javac + d8 手动流水线，无需 Gradle/Android Studio。

### 文件结构

- `src/com/minis/balancewidget/BalanceFetcher.java` — 各平台余额抓取（并发、IPv4/IPv6 双栈回退）
- `AliyunSigner.java` — 阿里云 BSS OpenAPI V1 签名
- `WidgetRenderer.java` — 小组件 RemoteViews 渲染
- `SettingsActivity.java` / `MainActivity.java` — 设置页与应用内看板
- `KeyStore.java` / `KeyVault.java` — 密钥加密存储

### 已知限制

- 百炼订阅制（Token Plan）无公开余量 API，卡片显示订阅实例与到期时间，余量需到控制台查看
- 百炼余额查询需 RAM 授权 `bss:DescribeAcccount`（历史遗留命名）或附加系统策略 `AliyunBSSReadOnlyAccess`

</details>

---

## 为我发电

如果这个小组件帮到了你，欢迎请作者喝杯咖啡 ⚡

[![为我发电](docs/afdian.jpg)](https://afdian.com/u/bf7a8956ab9711f182455254001e7c00)

👉 [爱发电主页](https://afdian.com/u/bf7a8956ab9711f182455254001e7c00)
