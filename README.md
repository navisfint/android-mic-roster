# Screen Roster · 屏幕名单 OCR（Android / Kotlin）

一个安卓端 OCR 采集工具：划一块屏幕区域，把里面的人名实时识别成文字列表，去重后一键复制。
全程离线，APK **未申请 INTERNET 权限**，OCR 模型直接打进了包里，飞行模式下照常可用。

> 这是从真实交付项目整理出的技术版本：业务场景已抽象为「屏幕名单」，代码结构与实现完整保留。

## 为什么值得看

难点不在「调 OCR 接口」，而在于**屏幕上全是干扰**——图标、弹幕、排行榜、礼物特效、省略号截断，
真正的人名只占其中一部分。项目里大半代码都在处理这件事。

## 技术架构

```
MediaProjection 截屏
      ↓  (只取用户框选的横向带)
   Bitmap 裁剪
      ↓
ML Kit 离线 OCR (com.google.mlkit:text-recognition-chinese)
      ↓
  三级文本清洗  ← 核心
      ↓
  去重存储 (NameStore)
      ↓
  列表展示 + 剪贴板导出
```

### 三级清洗

1. **整行精确匹配**（`EXACT_BAD`）—— 命中就是系统 UI 文案，整行丢弃
2. **片段包含匹配**（`CONTAIN_BAD`）—— 横幅、提示语、占位文案
3. **正则可疑特征**（`MicOcr.kt`）：
   - `^[^\u4E00-\u9FFF]{1,2}(?=[\u4E00-\u9FFF]{2,})` → 图标被识别成符号，剥掉前缀
   - `([\u4E00-\u9FFF]{2,})[\s·|丨]*[A-Za-z]$` → 表情被识别成尾部字母，剥掉
   - `^[\d.]+[万千百亿wWkK]$` → 纯数字带单位（各种计数），不是名字

### 只取一条横向带

整屏 OCR 既慢又脏。做法是让用户在屏幕上拖两条线确定识别区间，之后每次只裁剪这一条：

```kotlin
val top = (bitmap.height * topRatio).toInt()
val bottom = (bitmap.height * bottomRatio).toInt()
```

区域外的弹幕、标题栏、底部输入框全部不进入 OCR，识别准确率的提升主要来自这一步。

## 文件结构

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 主界面、权限申请、识别触发 |
| `CaptureService.kt` | 前台服务，持有 MediaProjection 与虚拟屏幕 |
| `FloatingPanel.kt` | 悬浮控制条（在其他应用上层） |
| `MicOcr.kt` | OCR + 三级文本清洗 |
| `NameStore.kt` | 去重存储与导出 |
| `RegionPicker.kt` | 全屏浮层，拖两条线框选区域 |
| `Prefs.kt` | 上限、区域比例等配置 |
| `NameAdapter.kt` | 结果列表 |
| `规则自测/OcrCleanTest.java` | 清洗规则的单测 |

## 工程上的几个判断

**用前台服务而不是后台服务。** 截屏必须挂在带通知的前台服务上，否则系统随时回收。
通知栏常驻一个低优先级通知（`IMPORTANCE_LOW`），无声音无横幅。

**Android 14+ 的虚拟屏幕只能创建一次。** `MediaProjection.createVirtualDisplay()`
在 Android 14 起对同一授权只允许调用一次，重复创建会被系统拒绝。所以 App 被彻底杀掉或
手机重启后必须重新授权——这是系统限制，绕不过去，README 里也写清楚给用户。

**去重要在写入层做，不在展示层做。** `NameStore` 用 `LinkedHashSet`，天然保序 + 去重，
翻页后继续识别可累加，不需要每次重排整表。

**权限只申请必要项：** 悬浮窗（`SYSTEM_ALERT_WINDOW`）、通知、震动。
AndroidManifest 里显式没有 `android.permission.INTERNET`——用户可自行核对。

## 构建

```bash
# 需要 Android SDK 34 + JDK 17
cp local.properties.example local.properties   # 改成本机 sdk.dir
./gradlew assembleDebug
```

`local.properties` 不入库（含本机绝对路径），只提供 `.example` 模板。

## 已知限制

- 只识别**当前这一屏**，要更多需翻页后再识别一次（结果自动累加去重）
- 生僻字、艺术字 OCR 可能错 1~2 个字符，这是离线模型的精度上限
- 框选区域占比一般在屏幕高度的 30%~50% 之间，太窄漏、太宽脏
