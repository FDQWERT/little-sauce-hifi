# 进度

状态：**源码完成，待 CI 编译验证**

## 已完成

- Gradle 工程骨架（AGP 8.2.2 / Kotlin 1.9.22 / Gradle 8.6 / compileSdk 34 / minSdk 26）
- `withNative` 开关：`-PwithNative=false` 时不编 native，退化为纯模式 A
- UI：三个页面（播放 / USB / 曲库）+ 深色主题 + 手写 vector 图标
- `core/AudioRouteManager`：输出设备枚举、采样率/声道/编码协商、系统音量
- `core/UsbHub`：UsbManager 封装、权限申请、原始描述符树
- `core/UacDescriptor`：UAC1/UAC2 描述符解析、alt 选择、采样率 control transfer
- `audio/MediaScanner`：MediaStore 扫描
- `audio/PlaybackEngine`：MediaCodec 解码、float 管线、重采样、seek、切歌重建 sink
- `audio/AudioSink`：AudioTrackSink（模式 A）/ DirectUsbSink（模式 B）
- `audio/PlayerHub` + `audio/PlaybackService`：单例广播 + 前台服务通知
- `native/UsbIso` + `cpp/usb_iso.cpp`：usbfs ISO URB 队列，JNI 全实现
- `.github/workflows/build.yml`：push/PR 自动出 debug APK
- `README.md`：构建、使用、限制、troubleshooting

## 待验证（需要编译环境）

- 首次 Gradle sync 是否报依赖版本冲突
- NDK r25 下 `linux/usbdevice_fs.h` 的结构体/宏是否齐（已加 `#ifndef` 兜底）
- `UsbDeviceConnection.getFileDescriptor()` 在你机型上对 UAC 设备是否返回有效 fd。

## 组装与打包

```bash
# Android Studio 打开一次以补齐 gradle-wrapper.jar，然后：
./gradlew :app:assembleDebug
# 或
gradle wrapper --gradle-version 8.6
```

## 环境备忘

- 开发机是 Android 手机容器，没有 JDK/SDK/NDK，本目录所有文件都是手写源码。
- `/sdcard` 是 FUSE：写入工具做原子替换会报 EINVAL，改文件请用 shell 直写。
