# 798 小助手

慧生活798 的极度精简客户端。**只做一件事：扫码启动用水设备。**

没有钱包、没有积分、没有签到、没有小部件、没有推送。
打开是登录，登录完是扫码，扫完是启动。

---

## 为什么做这个

官方客户端功能很多，但日常真正用到的只有两个动作：扫码、启动。
剩下的模块每次都要多点几下才能进去，中间还夹着开屏广告和推送。

这个版本把那些全部砍掉，只留最短路径。

---

## 功能

- 手机号 + 图形验证码 + 短信验证码登录
- 扫描设备二维码，自动提取设备编号
- 一键启动设备
- 启动后**在设备本体上结算**（服务端限制，App 无法停止）

## 不提供

| 模块 | 说明 |
|---|---|
| 钱包 / 充值 | 不涉及资金操作 |
| 积分 / 任务 / 签到 | 全部移除 |
| 多账户 | 只支持单账号 |
| 桌面小部件 / 快捷磁贴 | 未实现 |
| 消费统计 | 未实现 |
| 推送 / 广告 / 埋点 / 崩溃上报 | 一个都没有 |

---

## 运行环境

- Android 8.0（API 26）及以上
- 需要相机权限（扫码）

## 安装

从 [Releases](../../releases) 下载最新 APK 直接安装。

首次启动需要登录，登录后设备会记在本机，下次打开可直接扫码。

---

## 构建

### 环境要求

- JDK 21
- Android SDK：`compileSdk 37`、`build-tools 35+`
- Gradle 9.5（wrapper 已内置，无需另装）

### 配置敏感参数

API 网关与签名盐值通过 `secrets.properties` 注入，**该文件不进版本控制**：

```bash
cd minimal
cp secrets.properties.example secrets.properties
# 编辑并填入 API_GATEWAY 与 APP_SIGN_SALT
```

缺少该文件仍可编译，但登录会失败（网关为空）。

### 构建命令

```bash
cd minimal
./gradlew :app:assembleDebug      # 产物：app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # 产物：app/build/outputs/apk/release/
```

### CI

`.github/workflows/minimal.yml` 在推送 `dev` / `main` 的 `minimal/**` 改动时构建，
推送 `v*` 标签时自动创建 Release 并附上 APK。

需在仓库 Settings → Secrets 配置：

| Secret | 用途 |
|---|---|
| `API_GATEWAY` | API 网关，形如 `https://<host>/api/v1` |
| `APP_SIGN_SALT` | 登录签名盐值 |
| `KEYSTORE_BASE64` | 签名 keystore 的 base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |

---

## 代码结构

```
minimal/
├── settings.gradle.kts
├── build.gradle.kts
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/life798/mini/
    │   ├── App.kt              入口，只初始化日志
    │   ├── MainActivity.kt     单 Activity，三屏切换
    │   ├── core/               AppLog、Prefs、数据模型
    │   ├── net/                OkHttp 客户端、MD5 签名
    │   ├── scan/               ZXing 解码器、二维码解析
    │   └── ui/                 Compose 界面与 ViewModel
    └── res/
```

10 个 Kotlin 文件，约 1500 行。依赖 10 个，全部是 AndroidX / OkHttp / ZXing / CameraX。

### 扫码实现

不依赖 ML Kit（国产设备可能没有 Google Play 服务），改用 ZXing 纯 Java 解码，代价是自己处理帧：

- 直接读 Y 平面当灰度图，跳过 YUV→RGB 转换
- 只解码画面中心 60%×60%，降低单帧耗时
- 处理 `rowStride` 填充，否则宽度对不齐会整帧解不出来
- 旋转在数据层完成（90/180/270），因为 `PlanarYUVLuminanceSource` 不支持旋转

---

## 隐私

- 账户信息、设备编号只保存在本机
- 无统计、无埋点、无崩溃上报
- 应用内日志只写本地文件，需手动导出才会离开设备；token 与手机号在日志中已脱敏

---

## 上游

本项目 fork 自 [nocookies111/life-798](https://github.com/nocookies111/life-798)。

仓库中 `app/` 与 `src/` 仍保留上游 WaterWidget 的源码；`minimal/` 是本项目的精简实现，两者互不影响，可分别构建。

---

## 免责声明

非官方项目，与慧生活798服务提供方无关。

请仅使用你本人有权访问的账户和设备，并遵守相关服务规则。
本项目按现状提供，不对可用性、准确性作任何保证，使用风险由使用者自行承担。

---

## 许可证

[MIT](LICENSE)
