# Mirage

> 微信好友隐身 Xposed 模块 - 不删除、不屏蔽，好友仍可发消息打电话

[![Version](https://img.shields.io/badge/version-1.0.1-blue)](https://github.com/HdShare/Mirage/releases)
[![API](https://img.shields.io/badge/Xposed_API-93-green)](https://api.xposed.info/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/)

## 简介

Mirage 是一个基于 Xposed/LSPosed 框架的微信辅助模块，能够在微信前端 UI 层面隐藏指定好友，使其在联系人列表、会话列表、朋友圈、通知、搜索和群成员列表中"隐身"。

**核心策略：不删除、不屏蔽好友**，好友仍然可以正常发消息和打电话，只是在前端界面不可见。

## 功能特性

| 功能 | 描述 |
|------|------|
| 联系人列表隐身 | 在联系人列表中隐藏指定好友 |
| 会话列表隐身 | 在聊天列表中隐藏指定好友的会话 |
| 朋友圈隐身 | 在朋友圈 Feed 中隐藏指定好友的动态 |
| 通知拦截 | 拦截隐藏好友的消息通知 |
| 搜索防泄漏 | 防止通过搜索功能找到隐藏好友 |
| 群成员列表隐身 | 在群聊成员列表中隐藏指定好友（含 @ 列表） |

## 兼容性

- **框架**: Xposed / LSPosed / LSPatch
- **最低 Android 版本**: 8.0 (API 26)
- **目标微信版本**: 需配合 DexKit 动态适配微信混淆，理论上兼容多种微信版本

## 技术架构

项目采用**三层策略 Hook 架构**，确保在各种微信版本中都能正常工作：

1. **DexKit 动态查找** — 按真实微信字符串特征搜索混淆后的类和方法
2. **已知类直接 Hook** — 基于微信 APK 分析结果直接 Hook 已知类名
3. **降级兜底** — Cursor/Adapter/数据库层面的底层数据拦截

```
Mirage (Xposed Module)
├── 入口: MainHook
│   ├── Zygote 阶段 → 获取 modulePath
│   └── handleLoadPackage → 仅微信主进程
│       ├── DexKit 初始化
│       ├── ConfigManager 初始化
│       └── Hook MMApplicationLike.onCreate
│           └── registerAllHooks()
│               ├── ContactHook       (联系人列表隐身)
│               ├── ConversationHook  (会话列表隐身)
│               ├── MomentsHook       (朋友圈隐身)
│               ├── NotificationHook  (通知拦截)
│               ├── SearchHook        (搜索防泄漏)
│               └── GroupMemberHook   (群成员列表隐身)
└── UI: MainActivity (管理隐藏好友列表)
```

## 使用方法

### 安装

1. 确保已安装 Xposed/LSPosed 框架
2. 下载并安装 Mirage APK
3. 在 Xposed/LSPosed 管理器中启用 Mirage 模块
4. 勾选作用域：**微信 (com.tencent.mm)**
5. 重启微信生效

### 添加隐藏好友

1. 打开 Mirage 应用
2. 点击"添加隐藏好友"
3. 输入要隐藏的好友微信 ID（如 `wxid_xxxxx`）
4. 返回微信，该好友即被隐藏

### 取消隐藏

1. 在 Mirage 应用中长按已隐藏的好友
2. 点击"确定"移除

## 构建

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Gradle 8.2+

### 编译步骤

```bash
# 克隆项目
git clone https://github.com/HdShare/Mirage.git
cd Mirage

# 编译 Release APK
./gradlew assembleRelease
```

编译产物位于 `app/build/outputs/apk/release/`。

### GitHub Actions

项目配置了 GitHub Actions 自动编译流程，在 GitHub 仓库的 **Actions** 标签页中可以手动触发编译：

1. 进入仓库的 **Actions** 页面
2. 选择 **Build APK** 工作流
3. 点击 **Run workflow** 按钮
4. 编译完成后下载生成的 APK

## 依赖

| 依赖 | 用途 |
|------|------|
| [Xposed API](https://github.com/rovo89/XposedBridge) | Xposed 框架 Hook API |
| [DexKit](https://github.com/LuckyPray/DexKit) | 动态查找微信混淆类和方法 |
| [Gson](https://github.com/google/gson) | JSON 序列化 |
| AndroidX | Android 支持库 |

## 许可证

本项目仅供学习和研究使用，请勿用于非法用途。

## 免责声明

本模块仅修改微信客户端的前端展示，不涉及任何数据篡改或协议攻击。使用本模块产生的一切后果由使用者自行承担。