# WaterWidget

WaterWidget 是一个面向慧生活798用户的第三方 Android 设备控制客户端，提供多账户管理、饮水设备启动、钱包充值、用水消费统计、桌面小部件、快捷设置磁贴与设备二维码添加功能。

> 非官方项目，与慧生活798服务提供方无关。请仅使用你本人有权访问的账户和设备，并遵守相关服务规则。

## 功能

- 饮水设备一键启动 / 停止，接水结束后通知本次消费与估算水量
- 自动同步设备名称，支持设备别名、快捷移除和控制中心默认设备切换
- 短信登录与账户管理，分别检测用水与积分登录状态，支持重新验证对应端
- 查看校园钱包余额、通过支付宝充值，并核对服务端充值订单
- 扫描二维码或手动添加设备编号
- 每日签到、积分任务与按账号分组的执行记录，通知栏显示进度及已核验积分变化
- 本地优先的今日 / 本月 / 本年消费统计，支持按账号翻页查看消费记录
- 可隐藏预估水量或自定义金额与水量的统一兑换比例（不区分账号与设备）
- 桌面小部件、设备启动快捷设置磁贴
- 浅色、深色和跟随系统的显示模式

## 积分任务风险

首次进入积分任务页需阅读并确认风险提示。平台可能限制账号、扣除 / 清零 / 冻结积分，任务也可能失败或无法获得奖励；请求成功不等于奖励到账。本项目会核验执行期间的积分变化，但不能保证收益、到账或账号安全，也不能解除平台限制。

**目前积分冻结、任务提示成功但未到账等问题仍未解决，原因尚未确认。** 出现异常时请停止任务并向官方核实，勿反复运行期待自动恢复。

## 运行环境

- Android 13（API 33）及以上
- 仅提供 `arm64-v8a` 安装包，适用于现代 64 位 Android 设备

## 构建

项目采用 Gradle Kotlin DSL、Gradle 9.5、JDK 21、Kotlin 2.4 和 Jetpack Compose。源码通过 Gradle `sourceSets` 直接使用仓库根目录的 `src/main` 和 `src/test`。

### 配置参数

项目中的 API 地址、签名盐值等信息通过 `secrets.properties` 注入，**不会提交到版本控制**。构建前需手动创建：

1. 复制项目根目录的 `secrets.properties.example` 为 `secrets.properties`
2. 将其中的占位值替换为实际值

```bash
cp secrets.properties.example secrets.properties
# 然后编辑 secrets.properties 填入实际配置
```

配置项为 `API_GATEWAY`、`SIGN_SALT`、`APP_SIGN_SALT`、`MINI_SIGN_SALT` 与 `API_CID`。App 与支付宝小程序使用各自的积分签名盐值，不可混用。示例值仅用于离线构建和测试，不可用于实际服务请求。

> **说明**：未配置 `secrets.properties` 时项目仍可正常编译，但构建出的 APK 因缺少必要参数无法连接服务端。

### 构建命令

```bash
# JVM 单元测试
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# 正式 Release（需设置签名环境变量）
./gradlew assembleRelease
```

Release 签名环境变量：`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不要提交密钥或密码。

GitHub PR 验证使用示例配置，不需要真实服务参数；标签发布还需配置上述五项 API Secrets，以及 `ANDROID_KEYSTORE_BASE64` 和三个签名密码 / 别名 Secrets。推送 `v*` 标签会触发发布流水线，已有同名 Release 时流水线不会替换其附件。

APK 默认输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 隐私与数据

应用的账户和设备配置保存在本机。请不要将设备控制登录信息、账户数据或二维码内容分享给他人。

## 致谢与第三方项目

- [miuix](https://github.com/compose-miuix-ui/miuix)：使用 `miuix-ui` 的下拉刷新组件和 `miuix-blur` 的模糊能力，并在应用内适配刷新布局与状态衔接；Apache-2.0 License。
- [QuickieExtended](https://github.com/T8RIN/QuickieExtended)：提供基于 CameraX 与 ML Kit 的二维码扫描能力；MIT License。
- 感谢 [wasdwx](https://github.com/wasdwx) 在 [PR #5](https://github.com/nocookies111/life-798/pull/5) 中贡献底部导航栏避让系统导航栏的修复。
- 感谢 [zhuying66](https://github.com/zhuying66) 在 [PR #22](https://github.com/nocookies111/life-798/pull/22) 中提出刷新验证码后的焦点改进；已结合异步加载流程适配整合。
- 感谢 [luckjokerwang](https://github.com/luckjokerwang) 在 [PR #24](https://github.com/nocookies111/life-798/pull/24) 中提供官方版本兼容问题的分析与修复建议；当前修复由本项目独立分析实现，未直接合并该 PR。

上述项目及其代码继续遵循各自的许可证，本项目的 MIT License 不替代其许可证。

## 免责声明

本项目按现状提供，不对可用性、准确性或使用结果作任何保证，使用风险由使用者自行承担；不免除或限制法律规定不得免除或限制的责任。

## 许可证

本项目采用 [MIT License](LICENSE)。
