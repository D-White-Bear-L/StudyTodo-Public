# 架构与目录

> [← 返回 README](../README.md) · [配置说明](config.md)

## 三种形态

同一份产品，三种技术实现，功能一致，可以并存安装：

| 形态 | 目录 | 技术 | 产物 |
|:---|:---|:---|:---|
| **原生版 App（主）** | `apps/native` | Kotlin + Jetpack Compose | `release/*-native-*.apk` → 分发时固定为 `todo.apk` |
| 兼容版 APK | `apps/webview` | Capacitor 套壳（WebView） | `release/*-webview-*.apk` |
| 网页版 / PWA | `apps/web` | 原生 HTML/CSS/JS + Service Worker | 自己部署的站点，可添加到主屏幕 |

- **原生版**是独立实现，用 Compose 重写 UI，数据层是 `SharedPreferences` + JSON，不依赖 WebView。对外分发只发这一版。
- **网页版是兼容版的 UI 真源**：改网页样式 / 交互先改 `apps/web`，再由 `apps/webview/scripts/copy-web.js` 同步进套壳工程。
- 三端共用同一份数据模型和同步协议，记录 key 完全一致，可互相导入导出备份。
- 网页版按 `location.origin` 拼同步地址；原生版地址由 `.env` 在构建时注入 `BuildConfig.SYNC_BASE_URL`，源码里没有写死的主机。

## 目录结构

```
.
├── .env.example              # 配置模板（唯一需要填的地方，.env 不入库）
├── apps/
│   ├── web/                  # ③ 网页版 / PWA（兼容版的 UI 真源）
│   │   ├── index.html
│   │   ├── styles.css
│   │   ├── app.js
│   │   ├── manifest.webmanifest
│   │   ├── sw.js             # Service Worker 离线缓存
│   │   └── icon-*.png
│   │
│   ├── webview/              # ② Capacitor 套壳
│   │   ├── capacitor.config.ts
│   │   ├── package.json      # 独立依赖（@capacitor/*）
│   │   ├── scripts/copy-web.js   # apps/web -> dist
│   │   └── android/          # 生成的 Android 工程
│   │
│   └── native/               # ① Kotlin 原生（主）
│       ├── app/build.gradle  # 读 .env，注入同步地址与签名信息
│       ├── build.gradle
│       ├── settings.gradle
│       ├── keystore/         # 本机签名密钥（不入库）
│       └── app/src/main/java/host/workbuddy/dailytasklist/app/
│           ├── MainActivity.kt       # 入口 + 导航 + 清单页
│           ├── Data.kt               # 数据模型 + 存储 + 日期工具
│           ├── Sync.kt               # 云端同步：变更 diff、游标、墓碑
│           ├── AutoBackup.kt         # 自动备份到「下载」目录
│           ├── Backup.kt             # JSON 导入导出 + 确认弹窗
│           ├── Components.kt         # 通用 UI 组件
│           ├── LiquidMaterial.kt     # 自研色场 / CPU 采样降级
│           ├── PrismalGlass.kt       # 在 PrismalAGSL 之上封装四层材质
│           ├── GlassSettingsScreen.kt# 设置页（含云端同步开关）
│           ├── EditScreen.kt         # 任务编辑
│           ├── StudyScreen.kt        # 学习详情
│           ├── CalendarScreen.kt     # 日历
│           └── ExportDialogs.kt      # 导出与迁移弹窗
│
├── docs/                     # 技术文档 + 界面预览图
│   ├── config.md             # 配置外置说明
│   └── screenshots/
├── backup/                   # 从旧版 PDF 转出来的备份 JSON（不入库）
├── assets/                   # 图标生成脚本（图标本体在 apps/web）
├── scripts/
│   ├── config.js             # 解析 .env.example + .env
│   ├── gen-config.js         # 渲染 nginx / systemd 配置到 build/
│   ├── build-native.js
│   ├── build-webview.js
│   ├── prepare-publish.js    # 组装 build/publish 发布目录
│   ├── legacy-pdf-import.py  # 旧版 PDF 历史记录 -> 备份 JSON
│   └── download-page.html    # 下载页模板
├── server/                   # 同步服务
│   ├── sync_server.py        # 单文件服务：同步接口 + 静态托管 + 每日备份
│   ├── nginx-site.conf.example  # nginx 站点配置模板
│   ├── todo-sync.service     # systemd 单元模板
│   ├── patch_nginx_inline.py # 把站点配置插进主 nginx.conf（读 .env）
│   ├── migrate_encrypt.py    # 把已有明文就地加密
│   ├── import_backup.py      # 备份 JSON -> 云端（首次导入）
│   ├── sync_backup.py        # 备份 JSON -> 云端（镜像式覆盖）
│   ├── test_sync.py          # 接口测试（20 项）
│   ├── test_client.js        # 双设备端到端（28 项）
│   ├── test_live.js          # 线上只读验证（14 项）
│   └── test_encrypt.py       # 加密闭环（临时库，7 组）
├── build/                    # 构建产物与生成的部署配置（不入库）
└── release/                  # APK 输出（不入库，走 Release 分发）
```

## 数据模型

三个版本结构一致，字段名相同，便于导入导出互通：

```
tasks:    { "2026-11-01": [ { id, title, subject, note, done, createdAt } ] }
study:    { "2026-11-01": { hours, start, place, review } }
checkins: [ "2026-11-01", ... ]
settings: { theme, showCompletion, sort, autoBackup }
subjects: [ { key, name, color } ]
```

| 字段 | 说明 |
|:---|:---|
| `subjects` | 用户自己维护的标签列表（首次启动写入默认的工作 / 学习 / 生活 / 健康 / 其他 / 未分类）；`task.subject` 存标签的 `key`，旧数据无此字段时回落默认值 |
| `settings.sort` | `manual`（手动拖拽，默认）/ `undone`（未完成优先）/ `subject`（按学科分组） |
| `settings.autoBackup` | 自动备份开关，默认 `true` |
| 网页版存储 | `localStorage["todo_state_v1"]`，同步配置单独存 `localStorage["todo_sync_cfg_v1"]` |
| 原生版存储 | `SharedPreferences("daily_task_native")` |

**签名密钥不入库**：由 `.env` 的 `ANDROID_*` 指向本机 keystore，生成方式见 [config.md](config.md)。换掉它等于换了一把新钥匙，手机上已装的版本只能卸载重装，而卸载会连 SharedPreferences 里的全部记录一起删掉，请务必离线备份。

设置项（主题色 / 排序 / 壁纸）不参与同步：两端颜色格式不同（`#RRGGBB` vs ARGB Long），壁纸是本机路径。

## 功能清单

- **任务**：新增 / 编辑 / 删除 / 完成切换 / 多选批量操作
- **排序**：手动（长按上下拖动，拖到边缘自动滚动）/ 未完成优先 / 按学科分组，三态切换且会记住
- **日期**：前后翻天、回到今天、最近 7 天、日历选择
- **标签**：默认 6 个，可新增 / 改名 / 换色 / 删除（删除时由用户选择任务并入哪个标签）
- **学习详情**：总时长、开始时间、地点、复盘时间，支持「沿用上一日」
- **打卡**：连续天数、累计次数
- **云端同步**：记录级增量，多端共用一份，离线改动联网后自动补传，删除不会复活
- **自动备份**：默认开启，写入 `下载/每日任务清单备份/`（MediaStore，无需权限）；空数据时跳过，避免覆盖已有备份
- **迁移**：把未完成任务移到指定日期；可勾选「只迁移某几条」「保留原来的」（= 复制），勾选会被记住
- **数据备份**：全部记录导出 / 导入 JSON（合并或覆盖），三端互通
- **导出**：每日计划（文本）、每日总结（按学科分组 + 完成率）、日历图片（1080 宽 PNG）
- **取色**：自研 HSV 圆形色盘，标签颜色和日历主题色都能调任意颜色
- **外观**：液态玻璃风格，9 种预设主题色 + 自定义，深浅模式自动适配

## 版本历史

| 版本 | 日期 | 主要变更 |
|:---|:---|:---|
| — | 2026-11-12 | **配置外置**：域名 / 服务器路径 / 签名抽到 `.env`，仓库与文档脱敏，为公开做准备 |
| v1.43 | 2026-11-12 | **接入自有域名 + 去掉 IP**：站点走 HTTPS（80 自动跳 443、服务只监听回环、未知域名兜底）；客户端不再写死主机地址；安装包固定叫 `todo.apk`；修 Service Worker 把 `/sync` 也缓存、导致同步一直读到旧数据的问题 |
| v1.41 | 2026-11-12 | **原生版接入云端同步**：`Sync.kt` 与网页版协议逐字一致，接入点只有 `AppStore.save()` 一处；设置页新增「云端同步」区块 |
| v1.40 | 2026-11-12 | **删除标签可自选去处**：以前一律并到「剩下的第一个标签」（正好是数学），现在弹窗列出剩余标签让用户点选并实时提示影响条数 |
| v1.39 | 2026-11-12 | **签名固定**：改用本机 keystore（debug/release 一致）；**自动备份**：数据变更 2 秒后写到「下载」目录，设置页可看上次时间 / 立即备份 |
| v1.38 | 2026-11-12 | **迁移增强**：可勾选部分任务、可复制、状态持久化；**弹窗统一**为底部 sheet；**日历**完成度底色下限提到 0.20 并加进度条，「今天」改小圆点 |
| v1.37 | 2026-11-05 | **数据备份**：设置页新增导出 / 导入 JSON（合并 / 覆盖）；网页版导出菜单同步新增；新增 `legacy-pdf-import.py` 把旧版 PDF 历史转成备份（433 条）；导入后不再冒出示例任务 |
| v1.8 | 2026-11-02 | **液态玻璃改以第三方 PrismalAGSL 为底座**：在其 AGSL 折射 / 色散 / 边缘高光之上，补 `LiquidMaterial.kt` 的解析式色场 `SceneField`、CPU 按像素采样降级路径、RGB 三通道色散、增饱和、Fresnel 内辉光、跟随重力与手指的顶部镜面高光与光学颗粒；四层材质封装进 `PrismalGlass.kt`，任务行等大块内容保持平整薄表面 |
| v1.7 | 2026-11-01 | **液态玻璃重做（清单页）**：Floating / Control / Panel / Row 四层材质，玻璃只用在真正交互层；概览 + 统计合并；筛选与日期并组；任务行改整块连续列表；底部操作栏改浮动玻璃坞；背景光斑按真实尺寸定位；去掉作用在卡片自身上的 RenderEffect 模糊 |
| v1.5 | 2026-11-01 | **全局液态玻璃 UI 改造**：卡片 / 顶栏 / 底栏 / 按钮 / 输入框 / 筛选标签 / 日期 chip 全部换材质；圆角统一（卡片 28dp、按钮 16dp）；深浅模式自动适配；背景加微妙光斑 |
| v1.3 / v1.4 | 2026-11-01 | 日历导出图片加完成度百分比；主题色选择区重新排版（3×3 紧凑色盘 + 当前色预览） |
| v1.2 | 2026-11-01 | 色盘取色器（HSV 圆形色盘）；完成度显示 bug 修复（lerp 混色）；日历图片导出；底栏去重；长按拖动排序 |
| v1.1 | 2026-11-01 | 三段式布局修复遮挡；学科标签自适应换行；自定义学科标签（增删改） |
| v1.0 | 2026-11-01 | 原生版初版：任务、学习、打卡、导出 |
