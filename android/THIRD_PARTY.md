# Android 第三方组件

本项目自有 Java 源码继续使用根目录 MIT 许可。Android APK 为独立安装包，构建时集成 OCR SDK 和模型；它们并非本项目 MIT 源码，也不属于桌面源码包。不会捆绑 scrcpy、Python 或其他独立投屏软件。

中英文 OCR 使用 Google ML Kit `text-recognition` 和 `text-recognition-chinese` 16.0.1 的 bundled 版本。来源为 Google Maven 官方仓库。[官方集成方式](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[ML Kit 条款与隐私](https://developers.google.com/ml-kit/terms)、[Google APIs 条款](https://developers.google.com/terms)。不能把官方文档代码示例的 Apache-2.0 许可套用到 Google SDK 和模型。

SDK 的图片识别在设备上完成，图片及 OCR 输出不发送给 Google；SDK 可能请求更新并向 Google 发送性能及使用指标。应用主面板包含此说明。应用自身仅在手动识别时向 DeepSeek 发送当前题目文字、选项和提示，不发送截图或历史题目。

AndroidX、Kotlin 和其他传递依赖保留各自许可证。APK assets 包含实际 `debugRuntimeClasspath` 的64项组件清单、各组件 POM 许可证声明、Apache-2.0 全文和本项目 MIT 许可。已检查这些缓存运行库的独立 LICENSE/NOTICE 文本，本次未发现此类文件；不把缺少独立通知视为第三方无需授权。本次 GitHub 预览发布已核对 APK 与源码中的64项运行组件清单、六份通知/许可文件逐字节一致；分享包亦保留这些材料。此项是组件及产物一致性核验，不替代各组件条款。APK 使用开发签名，不作为商店正式版。

Gradle、Android Gradle Plugin、Android SDK 仅是开发机工具，不放入 APK。JUnit 仅测试使用，不放入 APK。
