# 云端同步与加密

> [← 返回 README](../README.md) · [配置说明](config.md) · [数据模型与备份](data.md)

多端共用一份数据。**记录级增量**同步：一条任务、一天学习、一天打卡、一个标签各是一行，客户端只上报真正变过的那几行，不整包覆盖。

## 设计要点

1. **用自增 `seq` 当游标，不用时间戳**。客户端只记「我看到第几号」，手机时钟漂了也不会漏同步。
2. **删除写墓碑**（`deleted=1`）。否则 A 删掉的记录会被 B 的旧数据复活，两边来回弹。
3. **冲突按 LWW**（`updated_at` 新者胜），不同记录互不影响，同一条才比时间。
4. 服务端只做搬运和定序，不理解业务内容；页面和接口同源，不跨域。

## 记录 key

`kind|id`，三端完全一致：

| kind | id 形式 | 例子 |
|:---|:---|:---|
| `task` | `日期\|任务id` | `task\|2026-11-04\|mw0zo4oo166` |
| `study` | 日期 | `study\|2026-11-04` |
| `checkin` | 日期 | `checkin\|2026-11-04` |
| `subject` | 标签 key | `subject\|work` |
| `setting` | 固定名 | `setting\|root` |

## 接口

```http
GET  /health                  健康检查，不需要鉴权
GET  /sync?since=<seq>        拉取 seq 之后的变更
POST /sync  {since, changes}  推送本地变更，同时返回 seq 之后的变更（一次往返）
Authorization: Bearer <同步码>
```

- 单次最多回 5000 条（`MAX_PULL`），超出时 `hasMore=true`，客户端接着拉
- 客户端时间比服务器快超 60 秒会被钳到服务器时间（`CLOCK_SKEW_LIMIT`），防止手机时钟乱跳占便宜
- 响应一律 `Cache-Control: no-store`

## 客户端行为

- 触发时机：启动 +1.2 秒、每 30 秒、切回前台、网络恢复、改动后 1.2 秒防抖
- `save()` 里做前后 diff，变更进 pending 队列（本地持久化，刷新不丢）
- 首次连接：先拉全量合并，再把本地独有的以 `updatedAt=0` 补推（0 = 很久以前，冲不掉云端）
- 远端下发走 `applyRemote()`，**刻意不触发 `save()`**，否则会被当成新的本地改动推回去，seq 一直涨
- 原生端接入点只有 `AppStore.save()` 一处，调用 `Sync.onLocalSave(this)` 算差量，业务代码没改
- 设置项（主题色 / 排序 / 壁纸）不参与同步
- 网页版默认跟随 `location.origin`，原生版地址由 `.env` 在构建时注入，源码里没有写死的主机

## 服务端部署

目录、端口、证书、日志等**全部由 `.env` 提供**，见 [配置说明](config.md)。典型结构：

```
<DEPLOY_DIR>/
├── sync_server.py     # 单文件服务，只依赖标准库 + cryptography
├── www/               # 网页版静态文件 + todo.apk
├── data/todo.db       # SQLite，内容加密
├── backup/            # 每日快照
├── token              # 同步码，权限 600
└── secret.key         # 数据密钥，权限 600
```

- 填好 `.env` → `npm run config:gen` → 把 `build/nginx-site.conf` 与 `build/todo-sync.service` 放到服务器。
- 站点配置来自 `server/nginx-site.conf.example` 模板；systemd 单元把 `SYNC_*` 映射成服务端的 `TODO_*` 环境变量。
- 服务只监听回环（`SYNC_BIND`），公网只能经 nginx 反代；80 端口只放行证书校验目录，其余 301 到 443。
- 面板类环境可用 `server/patch_nginx_inline.py` 把 server 块内联进主配置：脚本会先备份、检查锚点唯一性与花括号平衡，只增不改。

## 运维

```bash
systemctl status <SERVICE_NAME>
journalctl -u <SERVICE_NAME> -n 50
tail -f <ACCESS_LOG>
```

- **每日备份**：进程内按天把数据库复制到 `SYNC_BACKUP` 目录，保留 14 天；备份是加密库的副本，本身也是密文。
- 备份 / 恢复脚本走 HTTP，不直接改库：`import_backup.py`（首次导入）、`sync_backup.py`（镜像覆盖，云端多出来的自动打墓碑）。

## 数据存储与加密

服务端 `records.body` **落库前用 AES-256-GCM 加密**，数据库文件被单独拷走也读不出内容。

- 只加密 `body`（任务标题、备注、学习详情都在这里）；`kind` / `id` / `updated_at` / `seq` 保持明文，供增量同步、去重与排序使用，这些字段不含业务内容。
- 密钥路径由 `SYNC_SECRET`（服务端 `TODO_SECRET`）指定，**32 字节、权限 600，不入库也不入代码**。
- **密钥丢失 = 已加密数据无法恢复**，请离线备份；换机器、重装系统前先把钥匙找回来。
- 库里已有密文却找不到密钥时，服务**拒绝启动**而不是偷偷生成新密钥，否则旧数据会变成一堆解不开的字符串。
- 历史明文记录读取时原样返回（无 `enc1:` 前缀即明文），升级不用停机也不用重灌数据。
- 去重比较的是**解密后的明文**，不是密文：密文每次带随机 nonce，直接比密文会永远判定「有变化」，`seq` 无限增长。

```bash
# 把库里已有的明文就地加密（幂等；--dry-run 只看数量）
python3 server/migrate_encrypt.py --dry-run
python3 server/migrate_encrypt.py

# 临时库上验证加密闭环，不碰生产数据
python3 server/test_encrypt.py
```

**能防什么、挡不住什么**：能防数据库、备份文件、磁盘镜像被单独拷走；挡不住服务器被拿到 root——那时密钥和密文在同一台机器上。真要防这一层得改成端到端加密（客户端加密、服务端只存密文），代价是服务端无法再做任何检索。另外「导出备份」下载的 JSON 是明文，注意别随手发出去。

> 具体部署值、密钥备份位置等运维信息请记在自己的本地笔记里，**不要写进仓库文档**。

## 测试

```bash
python server/test_sync.py                                        # 接口 20 项
node   server/test_client.js                                      # 双设备端到端 28 项
SYNC_BASE=https://<你的域名> SYNC_TOKEN=xxx node server/test_live.js  # 线上只读 14 项
python server/test_encrypt.py                                     # 加密闭环（临时库）
```
