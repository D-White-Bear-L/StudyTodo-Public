# 构建、发布与环境

> [← 返回 README](../README.md) · [配置说明](config.md) · [架构](architecture.md) · [同步服务](sync.md)

## 常用命令

```bash
cp .env.example .env       # 首次：复制配置模板并填自己的值
npm run config:gen         # 生成 nginx / systemd 配置到 build/

npm run dev                # 网页版预览 http://127.0.0.1:8899
npm run build:native       # 打原生版 APK
npm run build:webview      # 打兼容版 APK
npm run build:all          # 两个都打
npm run publish:prepare    # 组装发布目录（网页 + 下载页 + APK）
```

域名、服务器路径、签名信息都从 `.env` 读，详见 [配置说明](config.md)。只装 App 不部署服务端时，前两步可以跳过。

## 发布到线上

线上是「静态文件 + 一个 Python 服务」，部署前先 `npm run config:gen` 生成配置文件，再按 `.env` 里的变量覆盖：

```bash
source build/deploy-env.sh      # 载入 DEPLOY_HOST / DEPLOY_USER / DEPLOY_DIR 等

# 网页版（index.html / app.js / styles.css / sw.js / 图标）
scp apps/web/{index.html,app.js,styles.css,sw.js,manifest.webmanifest} \
    apps/web/icon-*.png apps/web/apple-touch-icon.png \
    "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/www/"

# 安装包：固定叫 todo.apk，覆盖即可；同时把 app.js 里的 APK_VER 改个号
scp release/每日任务清单-native-v1.43.apk "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/www/todo.apk"

# 服务端代码与配置（改完重启）
scp server/sync_server.py "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/"
scp build/todo-sync.service "$DEPLOY_USER@$DEPLOY_HOST:/etc/systemd/system/"
ssh "$DEPLOY_USER@$DEPLOY_HOST" "systemctl daemon-reload && systemctl restart $SERVICE_NAME"
```

nginx 站点配置：`build/nginx-site.conf` 放到服务器相应位置；面板类环境可改用 `server/patch_nginx_inline.py` 内联（脚本会备份主配置并检查锚点唯一性、花括号平衡）。

发布后建议验证（带缓存穿透参数，比对字节数）：

```bash
curl -I "$APP_BASE_URL/todo.apk?sharecode=xxx"   # 200 + Content-Length 与本地 APK 一致
curl -I "$APP_BASE_URL/?sharecode=xxx"           # 首页 200
curl "$APP_BASE_URL/health"                      # 同步服务健康
```

## 没有真机 / 模拟器时怎么看界面

原生版自带一套离屏渲染测试：用 Robolectric 在 JVM 里真实渲染 Compose，把每个页面导成 PNG，改完布局可以直接看图核对，不用连设备。

```bash
cd apps/native
./gradlew.bat :app:testDebugUnitTest --tests "*ScreenshotTest*"
# 产物：app/build/reports/screenshots/*.png
```

- 屏幕参数在 `ScreenshotTest` 的 `@Config(qualifiers = "w411dp-h891dp-xxhdpi")` 里改（默认 411×891dp）
- 新增页面照抄一个 `shot_xxx()` 方法即可
- 这套依赖只在 `testImplementation` / `debugImplementation`，**不会打进 APK**
- Windows 需要 Robolectric ≥ 4.13（4.11 的原生渲染库不含 Windows DLL）
- 测试里会强制关闭 AGSL 折射（layoutlib 编不了着色器），截图只用来核对排版与布局，真机（Android 13+）效果以实机为准

> [!NOTE]
> **已知问题**：当前 Robolectric 4.17 + `compileSdk 37` 组合下，多数 `ScreenshotTest` 用例会报
> `IllegalArgumentException: 'color' is not a valid layout qualifier`；能成功导出的主要是日历导出图。
> 界面预览请以实际生成的 PNG 为准，README 里引用的图片都来自真实产物。

## 环境要求

| 依赖 | 版本 / 说明 |
|:---|:---|
| Node.js | 18+ |
| JDK | 17+（`JAVA_HOME`） |
| Android SDK | platform-tools、platforms;android-34、build-tools;34.0.0（`ANDROID_HOME` / `local.properties`） |
| 签名 | 本机 keystore，由 `.env` 的 `ANDROID_*` 指定；生成方式见 [config.md](config.md) |
| Python | 服务端只需标准库 + `cryptography` |

应用配置：`applicationId host.workbuddy.dailytasklist.app`，`minSdk 33`（Android 13+），`targetSdk 37`，`versionCode 43` / `versionName 1.43`。

> [!WARNING]
> 签名 keystore 不入库，请自行离线备份。换签名后手机上已装的版本只能卸载重装，本地记录会一起丢失。

## 版本与分支

版本号以 `apps/web/app.js` 里的 `APK_VER` 和 `apps/native/app/build.gradle` 的 `versionName` 为准，两处要同步；`package.json` 的 `version` 只是根工程标记。

分支约定（`main` 稳定 / `develop` 开发 / `v1.xx` 标签）见 [README](../README.md#分支与版本)。

## 不入库的文件

`.gitignore` 排除了：`.env`、签名 keystore、`keystore.properties`、本地私有笔记、APK 与全部构建 / 发布产物、个人任务备份 JSON、加密数据库与同步码、`__pycache__`、`.workbuddy*`、崩溃日志。

**配置外置是刻意设计**：仓库里只留 `.env.example` 模板和各种 `*.example` 配置，任何人 clone 后先填自己的值再构建。转公开前的检查清单见 [config.md#开源前检查](config.md#开源前检查)。
