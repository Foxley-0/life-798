# 798 小助手（minimal）

一个只做两件事的 Android 客户端：**账号登录**、**扫码启动用水设备**。

与本仓库根目录的 `WaterWidget` 是两个互不影响的 Gradle 工程。本工程放在 `minimal/` 下，自带 wrapper，单独构建：

```bash
cd minimal
./gradlew :app:assembleDebug
```

---

## 功能

| 功能 | 说明 |
|---|---|
| 登录 | 手机号 + 图形验证码 + 短信验证码，签名后换 token |
| 扫码 | CameraX 实时预览 + ZXing 解码，扫到即回填设备编号 |
| 启动 | `GET /dev/start`，成功后本地计时 |
| 停止 | `GET /dev/end` |
| 登出 | 清空本地 token |

**不做**：钱包、充值、积分、多账户、小部件、磁贴、消费统计、推送、任何广告或统计 SDK。

---

## 构建

### 环境

- JDK 21
- Android SDK：`compileSdk 37`、`build-tools 35+`
- Gradle 9.5（wrapper 已内置，无需另装）

### 配置敏感参数

项目里的 API 网关与签名盐值通过 `secrets.properties` 注入，**该文件不进版本控制**。

```bash
cd minimal
cp secrets.properties.example secrets.properties
# 编辑 secrets.properties，填入 API_GATEWAY 与 APP_SIGN_SALT
```

缺少该文件时仍可编译，但登录会失败（网关为空）。

### 构建命令

```bash
cd minimal
./gradlew :app:assembleDebug      # 产物：app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # 产物：app/build/outputs/apk/release/
```

Release 默认用 debug 签名（可安装）。如需正式签名，设置四个环境变量：
`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

### CI

`.github/workflows/minimal.yml` 在 `dev` 分支推送 `minimal/**` 时触发，产出 debug / release APK 作为 artifact。

需在仓库 Settings → Secrets 中配置：

| Secret | 用途 |
|---|---|
| `API_GATEWAY` | 必填，形如 `https://<host>/api/v1` |
| `APP_SIGN_SALT` | 登录签名盐值 |

---

## 结构

```
minimal/
├── settings.gradle.kts
├── build.gradle.kts
├── secrets.properties.example
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/life798/mini/
        │   ├── App.kt                 零 SDK 初始化
        │   ├── MainActivity.kt        单 Activity + 三屏切换
        │   ├── core/                  Prefs（DataStore）、模型
        │   ├── net/                   OkHttp 客户端、MD5 签名
        │   ├── scan/                  ZXing 解码器、二维码解析
        │   └── ui/                    Compose 界面与 ViewModel
        └── res/
```

10 个 Kotlin 文件，约 1500 行。依赖 10 个，全部为 AndroidX / OkHttp / ZXing。

---

## 实现要点

### 扫码为什么不用 ML Kit

ML Kit 识别率更好，但依赖 Google Play 服务，国产设备可能没有 GMS 导致直接不可用。这里改用 ZXing 纯 Java 解码，代价是要自己写帧处理。

### 解码性能

三个关键处理：

1. **直接读 Y 平面**当灰度图，跳过 YUV→RGB 转换，省掉单帧 30~80ms
2. **只解码中心 60%×60%**，面积降到 36%，速度提升约 3 倍
3. **处理 rowStride 填充**，否则宽度对不齐会整帧解不出来

旋转在数据层完成（90/180/270），因为 ZXing 的 `PlanarYUVLuminanceSource` 不支持旋转。

### 签名

```
sign = md5("authCode=" + 短信码 + "&un=" + 手机号 + APP_SIGN_SALT)
```

盐值只用于登录，`/dev/start` 不需要签名，只认 `Authorization` 里的 token。

---

## 已知限制

1. `ptype=91` 是设备消费类型，已实测对淋浴设备有效；若某些设备报错，需调整该参数。
2. token 有效期未知，过期后会提示重新登录。
3. 依赖官方服务端接口，服务端改版则可能失效。

---

## 免责声明

非官方项目，与服务提供方无关。仅供本人对自己有权使用的设备进行操作，请遵守相关服务规则，不要分发或用于批量自动化。
