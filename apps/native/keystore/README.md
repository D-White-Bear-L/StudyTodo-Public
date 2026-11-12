# 签名密钥目录（本机专用）

这里放 Android 打包用的 keystore，**`*.jks` 不入库**（`.gitignore` 已排除）。

```bash
keytool -genkeypair -v -keystore dailytask.jks \
    -alias studytodo -keyalg RSA -keysize 2048 -validity 10000
```

- 在仓库根 `.env` 里用 `ANDROID_KEYSTORE` / `ANDROID_KEY_ALIAS` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_PASSWORD` 指向它，Gradle 会自动读取。
- debug 与 release 用同一把密钥。**换签名后，手机上已安装的版本只能卸载重装，本地记录会一起丢掉。**
- 请离线备份这个文件和口令，不要把口令写进任何会提交的文件。

变量含义与开源前检查见 [docs/config.md](../../docs/config.md)。
