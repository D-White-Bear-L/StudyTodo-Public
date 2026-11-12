# 配置说明

> [← 返回 README](../README.md)

这个项目**把环境相关的东西全部放在本机的 `.env`**，仓库里只有一份不含真实值的模板 `.env.example`。因此：

- 仓库可以公开，clone 下来不会拿到任何人的域名、服务器地址、同步码或密钥；
- 想跑在自己的环境里，先填配置再构建即可；
- 只装 App、不部署服务端的话，配置可以完全不填。

## 三步

```bash
cp .env.example .env       # 1. 复制模板（.env 已被 .gitignore 排除）
#                           # 2. 用编辑器填自己的值
npm run config:gen         # 3. 生成部署配置到 build/
```

`config:gen` 会读 `.env`（缺失时退回 `.env.example` 的占位值），生成：

| 产物 | 用途 |
|:---|:---|
| `build/nginx-site.conf` | nginx 站点配置，放到服务器 |
| `build/todo-sync.service` | systemd 单元，放到服务器 |
| `build/deploy-env.sh` | 供部署脚本 `source` 的环境变量 |

`build/` 整个目录不入库，所以生成的真实值不会进仓库。

源码里读取配置的位置：

| 位置 | 怎么读 |
|:---|:---|
| `scripts/config.js` | 解析 `.env.example` + `.env`，其它脚本统一 `require('./config')` |
| `apps/native/app/build.gradle` | 读 `.env`，把 `APP_BASE_URL` 注入 `BuildConfig.SYNC_BASE_URL`，并取签名信息 |
| `apps/native/.../Sync.kt` | `DEFAULT_BASE = BuildConfig.SYNC_BASE_URL` |
| `apps/web/app.js` | 默认用 `location.origin`；`file://` 下可用 `window.STUDYTODO_SYNC_FALLBACK` 注入 |
| `server/patch_nginx_inline.py` | 自己解析 `.env` 渲染内联的 nginx server 块 |
| `server/sync_server.py` | 通过 `TODO_*` 环境变量（由生成的 systemd 单元提供） |

## 变量一览

### 站点 / 客户端

| 变量 | 模板值 | 说明 |
|:---|:---|:---|
| `APP_DOMAIN` | `your-domain.example` | 站点域名，用于 nginx `server_name` |
| `APP_BASE_URL` | `https://your-domain.example` | 原生版默认同步地址，构建时注入 |
| `APK_URL` | `https://your-domain.example/todo.apk` | 安装包下载地址（网页版下载按钮用） |
| `WEB_SYNC_FALLBACK` | 空 | 仅 `file://` 打开网页版时使用；留空则需在设置里手填 |

> 网页版正常部署时不需要 `APP_BASE_URL`：页面由同步服务托管，跟着 `location.origin` 走即可。

### 服务端

| 变量 | 模板值 | 对应服务端环境变量 |
|:---|:---|:---|
| `DEPLOY_HOST` / `DEPLOY_USER` | `your-server.example` / `deploy` | 部署用的 SSH 目标（脚本不强制，可留占位） |
| `DEPLOY_DIR` | `/opt/studytodo` | 服务根目录 |
| `SYNC_BIND` / `SYNC_PORT` | `127.0.0.1` / `18080` | `TODO_BIND` / `TODO_PORT`，建议只监听回环 |
| `SYNC_DB` | `/opt/studytodo/data/todo.db` | `TODO_DB` |
| `SYNC_TOKEN` | `/opt/studytodo/token` | `TODO_TOKEN`，同步码文件，权限 600 |
| `SYNC_SECRET` | `/opt/studytodo/secret.key` | `TODO_SECRET`，**加密密钥，丢了数据打不开** |
| `SYNC_STATIC` | `/opt/studytodo/www` | `TODO_STATIC`，网页版静态文件 |
| `SYNC_BACKUP` | `/opt/studytodo/backup` | `TODO_BACKUP`，每日快照目录 |
| `PYTHON_BIN` / `SERVICE_NAME` | `/usr/bin/python3` / `studytodo-sync` | systemd 单元 |

### nginx

| 变量 | 模板值 |
|:---|:---|
| `NGINX_MAIN_CONF` | `/etc/nginx/nginx.conf` |
| `NGINX_INCLUDE_ANCHOR` | `include /etc/nginx/conf.d/*.conf;` |
| `WEBROOT` | `/var/www/studytodo`（80 端口证书校验目录） |
| `SSL_CERT` / `SSL_KEY` | `/opt/studytodo/cert/fullchain.pem` / `privkey.key` |
| `ACCESS_LOG` / `ERROR_LOG` | `/var/log/nginx/studytodo_{access,error}.log` |

> 用面板类环境（主配置在别处、include 路径不同）时，把 `NGINX_MAIN_CONF` / `NGINX_INCLUDE_ANCHOR` 改成实际值，再跑 `python server/patch_nginx_inline.py` 内联 server 块。脚本会先备份主配置、检查锚点唯一性与花括号平衡。

### Android 签名

| 变量 | 模板值 | 说明 |
|:---|:---|:---|
| `ANDROID_KEYSTORE` | `apps/native/keystore/dailytask.jks` | 本机 keystore 路径（相对仓库根） |
| `ANDROID_KEY_ALIAS` | `studytodo` | 别名 |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_PASSWORD` | `changeit` | 口令 |

自己生成一把密钥：

```bash
keytool -genkeypair -v -keystore apps/native/keystore/dailytask.jks \
    -alias studytodo -keyalg RSA -keysize 2048 -validity 10000
```

> [!WARNING]
> keystore **不入库**，请自己离线备份。签名一旦更换，手机上已安装的版本只能卸载重装，本地记录会一起丢失。
> 如果只是给别人编译体验版，用他自己的新密钥没问题；如果要给已安装用户发更新包，必须用原来那把。

## 三种典型场景

**① 只用 App，不部署服务端**：什么都不用填。从 Release 下载 APK，数据默认存本机。

**② 本地开发网页版**：`.env` 可跳过，`npm run dev` 后打开 `http://127.0.0.1:8899`。同步功能需要另起 `server/sync_server.py`，并填一个 `token` 文件；测试用 `SYNC_BASE=http://127.0.0.1:18080`。

**③ 自己部署整套服务**：填完所有变量 → `npm run config:gen` → 把 `build/nginx-site.conf`、`build/todo-sync.service` 与 `server/sync_server.py`、网页版静态文件放到服务器 → 启动服务 → `npm run build:native` 打包（会用 `APP_BASE_URL`）。

## 不入库清单

`.gitignore` 已排除：

```
.env                       # 真实配置
keystore.properties        # 若改用 Android 标准写法
apps/native/keystore/*.jks # 签名密钥
本地私有笔记 # 不入库
backup/                    # 个人任务备份 JSON
server/*.db / server/backup/ / server/token / server/secret.key
build/ release/            # 构建与发布产物
```

## 开源前检查

> [!CAUTION]
> **把私有仓库直接改成公开，会把 git 历史一起公开。** 如果历史上提交过 `.env`、keystore、带真实地址的配置文件或运维文档，仅清理当前工作树是不够的。

转公开前逐项确认：

1. `git ls-files` 里没有 `.env`、`*.jks`、本地私有笔记等文件。
2. `git status --short` 不出现上述文件，`git check-ignore -v .env` 有输出。
3. 全仓搜索占位符以外的值（域名、服务器绝对路径、个人用户名路径、证书与日志路径），确认只命中模板与 `.env.example`。
4. `.env.example` 内全部是 `your-domain.example` / `/opt/studytodo` 一类占位符。
5. 历史里若曾有敏感文件：新建干净仓库做单次初始提交，或用 `git filter-repo` 清理全部历史后再 force push。
6. README 的下载链接指向公开可访问的地址（如 GitHub Release），而非内部地址。
7. Release 附件对匿名用户可下载；同步码、密钥、内部笔记都不在 Issues / Discussions 里。

## 常见问题

**忘了填 `.env` 会怎样？** `config:gen` 会提示并生成占位值配置，服务仍能编译，但地址指向 `your-domain.example`，需要重新填好再生成。

**网页版用 `file://` 打开同步不了？** 这是预期行为：无法推断来源地址。在设置里手动填服务器地址，或由部署方注入 `window.STUDYTODO_SYNC_FALLBACK`。

**测试脚本连的是哪？** `server/test_live.js` 默认 `http://127.0.0.1:18080`，跑线上时显式给 `SYNC_BASE` 和 `SYNC_TOKEN`。
