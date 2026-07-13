# Mirage 项目开发指南

## 项目概述
Mirage 是一个基于 Xposed/LSPosed 框架的微信辅助模块，用于在微信前端 UI 层面隐藏指定好友。核心策略：不删除、不屏蔽好友，而是在联系人列表、会话列表、朋友圈、通知、搜索和群成员列表中使指定好友"隐身"。

## 技术栈
- **语言**: Kotlin
- **框架**: Xposed API v93 (compileOnly)
- **核心库**: DexKit 2.0.1 (动态查找微信混淆类), Gson 2.10.1
- **构建**: Gradle 8.2, AGP 8.2.0, Kotlin 1.9.22
- **最低 SDK**: 26 (Android 8.0), 目标 SDK: 34

## 目标应用
- **包名**: `com.tencent.mm` (微信)
- **参考 APK**: `C:\GitHub\po\weixin_arm64.apk`
- **架构**: 仅主进程注入，跳过 15 个微信子进程 (push, tools, support, sandbox, exdevice, appbrand, finder, game, snsad, appbrand0-4 等)

## 项目结构
```
Mirage/
├── app/
│   ├── build.gradle.kts          # 模块构建配置 (versionCode, versionName, 依赖)
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单 (Xposed 模块声明)
│       ├── assets/xposed_init    # Xposed 入口: wx.mirage.MainHook
│       ├── java/wx/mirage/
│       │   ├── MainHook.kt       # 主入口: IXposedHookLoadPackage + IXposedHookZygoteInit
│       │   ├── config/
│       │   │   └── ConfigManager.kt  # SharedPreferences 配置管理
│       │   ├── hook/
│       │   │   ├── ContactHook.kt       # 联系人列表隐身
│       │   │   ├── ConversationHook.kt  # 会话列表隐身
│       │   │   ├── GroupMemberHook.kt   # 群成员列表隐身
│       │   │   ├── MomentsHook.kt       # 朋友圈隐身
│       │   │   ├── NotificationHook.kt  # 通知拦截
│       │   │   └── SearchHook.kt        # 搜索防泄漏
│       │   └── ui/
│       │       └── MainActivity.kt  # 管理界面
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/ (strings.xml, themes.xml)
├── .github/workflows/build.yml   # GitHub Actions 自动编译
└── README.md
```

## 核心架构
每个 Hook 模块采用统一的三层策略：
1. **DexKit 动态查找** — 按真实微信字符串特征搜索混淆后的类和方法
2. **已知类直接 Hook** — 基于微信 APK 分析结果直接 Hook 已知类名
3. **降级兜底** — Cursor/Adapter/数据库层面的底层数据拦截

## 微信 APK 兼容性验证
已验证 `C:\GitHub\po\weixin_arm64.apk`：
- 大小: 167.4 MB (17 个 dex 文件)
- 包名: `com.tencent.mm`
- 关键目标类全部存在: MMApplicationLike, SelectContactUI, SnsTimeLineUI, ChatroomInfoUI, SelectMemberUI, MsgInfo, SnsObject, ContactInfoUI, ConversationInfo, NotificationManager

## 版本管理
- 版本号定义在 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
- 当前版本: v1.0.1 (versionCode: 101)
- 版本号同时出现在 `MainHook.kt` 的日志字符串中，需同步更新

## 构建与发布
- 本地编译: `./gradlew assembleRelease`
- GitHub Actions: 在 Actions 页面点击 "Build APK" → "Run workflow" 即可触发编译
- 编译产物: `app/build/outputs/apk/release/`
- 发布: 在 GitHub Releases 页面手动上传 APK

## 修改代码注意事项
- 版本号变更需同时修改 `app/build.gradle.kts` 和 `MainHook.kt` 中的日志字符串
- 新增 Hook 模块需在 `MainHook.registerAllHooks()` 中注册，并用独立 try-catch 包裹
- 微信子进程列表在 `MainHook.kt` 的 `subProcessSuffixes` 中定义，新增微信进程需更新
- ProGuard 混淆规则需 keep `wx.mirage.**` 和 `org.luckypray.dexkit.**`
- 所有 Hook 模块都通过 `ConfigManager.isHidden(wxId)` 判断是否过滤