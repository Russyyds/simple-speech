# Simple Speech Android Demo

Android 端侧 speech 功能展示 App，包含翻译、ASR、TTS 三个页面。默认进入翻译页，界面使用原生 Android View 实现。

## 当前实现

- 翻译页: 模型选择、源语言、目标语言、输入文本、翻译按钮、结果框。
- ASR 页: 模型选择、识别结果框、底部语音输入按钮。
- TTS 页: 模型选择、语音播放占位框、底部文本输入框。
- 翻译推理: JNI 接入 `third_party/llama.cpp`，已实现 GGUF 模型加载、tokenize、greedy decode、模型缓存。

## 翻译模型路径

本机 GGUF 模型源目录:

```text
E:\tencent\Hy-MT1.5-1.8B-1.25bit-GGUF\Hy-MT1.5-1.8B-1.25bit.gguf
E:\tencent\Hy-MT1.5-1.8B-2bit-GGUF\Hy-MT1.5-1.8B-2bit.gguf
```

App 运行时在设备目录查找:

```text
/sdcard/Android/data/com.tencent.simplespeech/files/models/Hy-MT1.5-1.8B-1.25bit/*.gguf
/sdcard/Android/data/com.tencent.simplespeech/files/models/Hy-MT1.5-1.8B-4bit/*.gguf
/sdcard/Android/data/com.tencent.simplespeech/files/models/Hy-MT1.5-1.8B-8bit/*.gguf
/sdcard/Android/data/com.tencent.simplespeech/files/models/Hy-MT1.5-1.8B-2bit/*.gguf
```

当前 `E:\tencent\Hy-MT1.5-1.8B-1.25bit-GGUF` 下只发现 1.25bit GGUF，暂未发现 4bit/8bit GGUF。代码已按模型名匹配 `4bit/q4`、`8bit/q8` 文件名；后续把对应文件放进目录后，推送脚本会自动处理。

## 构建

首次 clone 项目时建议直接拉取 submodule:

```powershell
git clone --recurse-submodules <repo-url>
```

如果已经 clone 了主仓库，再初始化 llama.cpp submodule:

```powershell
git submodule update --init --recursive --depth 1
```

工程使用本机已有路径:

```text
Android SDK: D:\CommonSoftwares\AndroidStudio\SDK
Android NDK: D:\android-ndk-r27d-windows\android-ndk-r27d
CMake: D:\Cmake
Ninja: D:\ninja-win
Gradle: 项目内 Gradle wrapper，固定为 Gradle 7.2
JDK: C:\Users\Tom\.jdks\corretto-11.0.20
```

构建 debug APK:

```powershell
.\scripts\build_android.bat
```

也可以直接使用 wrapper:

```powershell
.\gradlew.bat --no-daemon assembleDebug
```

安装:

```powershell
.\scripts\install_debug.bat
```

推送 GGUF 模型:

```powershell
.\scripts\push_models.bat
```

## llama.cpp

`third_party/llama.cpp` 是 git submodule，指向 `https://github.com/ggml-org/llama.cpp.git`。当前固定提交的源码中包含 `TQ1_0/Q1_0` 类型，可支持 Hy-MT 1.25bit GGUF 所需的低比特量化类型。

当前构建默认启用 CPU 后端。Hy-MT 1.25bit README 的运行参数也是 `-ngl 0`，所以 1.25bit 在 App 内强制使用 CPU。Adreno OpenCL SDK 已在本机可用，但 GGML OpenCL 后端暂未打开。
