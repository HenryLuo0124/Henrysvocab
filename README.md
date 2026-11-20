# Henry’s Vocab

一款用 Kotlin 和 Jetpack Compose 开发的 Android 背单词应用。词书由自己导入，学习与复习按北京时间记录，模型服务可自行配置。

## 安装

- 系统要求：Android 8.0（API 26）或以上。
- 发布后，可在 [GitHub Releases](https://github.com/HenryLuo0124/Henrysvocab/releases) 的 **Assets** 中下载 APK。
- 更新时优先覆盖安装。同一应用包名、同一签名且版本兼容时，覆盖安装可保留本地资料。若系统提示签名冲突，先导出备份，不要直接卸载旧版。

## 开始使用

1. 打开 **设置 → 词书管理**，导入 UTF-8 文本词书并选中它。
2. 回到首页点 **Learn**。后台每批从词书取最多 20 个词条；学习页只显示「今日已学数 / 10」，10 是目标，不是上限。
3. 到期后点 **Review**。AI 判分需先在 **模型配置** 中设置兼容的模型服务。

词书每行一个单词或词组，格式为 `词条/(释义1/释义2)/例句`，例如：

```text
apple/(n. 苹果)/I ate an apple after lunch.
look forward to/(phr. 期待)/I look forward to seeing you.
```

### 学习、复习与查词

- **复习计划**：从学习或上一次答对当天起，依次间隔 1、2、3、7、15、30、60 天；答错从次日重新开始，逾期项目仍可补做。日期以联网校准的北京时间为准。
- **模型查重**：可在词书管理中按词书开启。应用在准备新的一批词条前，将候选词条和已学词表交给模型比对。也可把已学词表导出为每行一个词条的 UTF-8 文本。
- **独立记录**：删除词书会清除它的待学队列，不会删除已学记录或待复习卡片。学习和复习页的垃圾桶可移除当前卡片，不再安排该卡片复习。
- **外部查词**：在 **设置 → 外部翻译** 中选择百度翻译或网易有道词典。点按卡片中的单词会先复制，再尝试打开所选 App。未安装时只复制；如果没有自动填词，可手动粘贴。悬浮返回按钮需要单独授权。
- **壁纸**：可在设置中选择并裁剪本地照片。

## 数据、模型及备份

词书、壁纸和学习记录保存在本机。模型使用自定义的 OpenAI 兼容 HTTPS 接口，可配置 Base URL、路径、模型 ID 和 API Key。支持获取模型列表；供应商不提供列表接口时，可手动输入模型 ID。

调用判分时，当前词条、本地释义与例句以及你的答案会发送到所配置的模型服务；开启查重时，候选词条和已学词表也会发送给该服务。应用不附带历史对话，但服务商仍可能保留请求，具体以其政策为准。

在 **设置 → 备份与恢复** 可导出密码加密的 `.hvb` 备份。请将备份放在「下载」或其他卸载后仍可访问的位置，并保存好密码。重新安装后手动导入即可；恢复会替换当前资料。请勿将包含个人数据或 API Key 的备份上传到公开仓库。

## 从源码构建

用 Android Studio 打开仓库根目录，安装 Android SDK 37 并完成 Gradle 同步。项目包含 Gradle Wrapper，Daemon JVM 配置为 Java 25。也可在终端运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

macOS / Linux：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。没有外部森林照片时会使用仓库内的矢量背景；如需使用自己的构建背景，可添加 `-PforestPhotoPath=/path/to/forest.jpg`。发布 APK 前，请确认打包的照片允许分发。
