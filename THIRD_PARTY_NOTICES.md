# 第三方组件与来源

本仓库自己的程序代码按根目录 MIT LICENSE 提供。第三方库、模型、解释器和投屏组件保留各自许可证；本项目许可不替代它们。源码仓库不提交这些运行库和模型二进制。

| 组件 | 来源 | 许可及范围 |
|---|---|---|
| Pillow | https://github.com/python-pillow/Pillow | HPND 等，见安装包许可证 |
| httpx / httpcore / anyio | https://github.com/encode/httpx / https://github.com/encode/httpcore / https://github.com/agronholm/anyio | BSD/MIT，保留发行包 LICENSE |
| RapidOCR | https://github.com/RapidAI/RapidOCR | Apache-2.0 |
| PaddleOCR 派生模型 | https://github.com/PaddlePaddle/PaddleOCR | 模型来源/适用授权需单独保留，不把转换当作取得模型版权 |
| ONNX Runtime | https://github.com/microsoft/onnxruntime | MIT 及 THIRD_PARTY_NOTICES |
| NumPy / Shapely / pyclipper / PyYAML | 各组件安装包 METADATA 和许可证 | 各自授权；Shapely 还包含 GEOS |
| OpenCV Python | https://github.com/opencv/opencv-python | 含第三方构建组件，见 LICENSE-3RD-PARTY.txt |
| Python / Tcl-Tk / OpenSSL | https://www.python.org / https://www.tcl-lang.org / https://www.openssl.org | 解释器与所带组件的各自许可证 |
| scrcpy | https://github.com/Genymobile/scrcpy | Apache-2.0，不能覆盖其 FFmpeg/SDL/libusb/ADB 依赖许可 |
| FFmpeg | https://ffmpeg.org/legal.html | 按具体编译配置核验许可、对应源码及构建配置 |
| SDL / libusb / Android platform-tools | https://www.libsdl.org / https://libusb.info / https://developer.android.com/tools/releases/platform-tools | 保留具体版本原有许可及来源 |

RapidOCR 上游明确区分程序代码和模型权利，见[模型许可与归属说明](https://rapidai.github.io/RapidOCRDocs/main/en/model_licenses/)。所有捆绑模型应记录文件哈希、原模型来源和转换信息。

## 当前发布方式

按项目维护者决定，Windows 源码发布不包含第三方解释器、投屏程序、库或模型。`requirements*.txt` 是用户在本机安装依赖的清单，不是这些组件的再分发包。官方下载入口和安装步骤见 README。运行时仍会使用这些组件，各自的授权和归属仍然有效。

Windows 源码不再分发上述运行二进制；保留本文件的来源说明。不得将用户安装后的整个目录再次打包上传本项目 Release。

## 历史便携包与未来二进制发布

既有开发机便携分享包的第三方通知尚未补齐，不应仅凭 scrcpy 的 Apache LICENSE 就把整个 mirror 目录按同一许可发布。复核发现两个不同 FFmpeg 构建（mirror 与 OpenCV）、libusb、GEOS 等需要各自对应资料，RapidOCR/部分安装包也缺独立的完整许可文本。

ONNX Runtime 动态库还需要微软 VC++ x64 运行库；开发机已安装不等于完整便携包能在干净电脑直接运行。安装入口见[微软官方支持页面](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)。本仓库不复制系统 DLL，也不捆绑微软安装包。

若未来恢复捆绑第三方二进制的发布方式，应保留所有组件许可证和通知，逐一核对动态库构建选项、对应源码和再分发条件，生成组件版本/来源清单与模型 SHA256。Windows 当前只发布本仓库源码，不把历史桌面便携分享包作为正式下载资产。Android 预览 APK 的独立分发范围见下节。

## Android 预览 APK

Android 0.1.7-preview 为独立安装包，集成 Google ML Kit 中英文 OCR SDK、模型与运行依赖。自有代码使用 MIT；这些第三方组件保留各自授权，不把 MIT 套用于整个 SDK 或模型。来源与条款见 [Android 第三方说明](android/THIRD_PARTY.md)。APK 与分享包包含运行组件清单、POM 许可声明及适用通知。Gradle Wrapper 是构建工具，保留 Apache-2.0 原始头与许可；不打包完整 Gradle/Android SDK。不会捆绑 scrcpy、Python 或其他独立投屏应用。
