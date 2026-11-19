# LittleSauce HiFi

Android 本地音乐播放器，主打 **USB 小尾巴（UAC DAC）直连输出**。
两条输出通路可选：系统 USB 音频通路（兼容性优先）与 usbfs isochronous 独占直通（bit-perfect 优先），
直通失败会自动回落系统通路并在界面上写明原因，不会没声音。

```
解码(MediaCodec) → float32 交织 → [可选多相重采样] → 按端点要求重新编码 → 输出
                                                                        ├─ 模式A: AudioTrack + setPreferredDevice → USB HAL
                                                                        └─ 模式B: usbfs ISO URB 队列 → UAC streaming 端点
```

## 两种输出通路

| | 模式 A · 系统 USB 通路 | 模式 B · 独占直连 |
|---|---|---|
| 实现 | `AudioTrack` + `setPreferredDevice()` | `USBDEVFS_SUBMITURB`（ISO URB）+ 自填 packet |
| 经过 | AudioFlinger → USB HAL → 内核 snd-usb-audio | 直接写设备端点，绕过混音器 |
| 采样率 | 跟随曲目重建 AudioTrack，HAL 会切硬件采样率 | 跟随曲目，通过 UAC control transfer 设置 |
| 系统音量 | 生效 | 不生效，用播放器内软件音量 |
| bit-perfect | 不保证（混音器可能介入） | 保证（位深/采样率/声道来自描述符） |
| 兼容性 | 几乎所有设备 | 受内核 usbfs 策略限制，见「限制」 |

模式 B 的格式不是随便定的：从设备配置描述符里读 AS Interface 的 alt setting、Format Type、
`bSubframeSize`、`tSamFreq`，用 `bSubframeSize` 反推编码方式（2→S16、3→S24_3LE、4→S32），
保证写入字节数与端点帧长严格一致。

## 目录结构

```
app/src/main/java/com/lcs/hifi/
├── MainActivity.kt            三个页面 + 权限 + USB 插拔监听
├── HifiApp.kt
├── core/
│   ├── AudioRouteManager.kt   AudioDeviceInfo 枚举、采样率/声道/编码协商、系统音量
│   ├── UsbHub.kt              UsbManager 封装、权限申请、原始描述符树
│   └── UacDescriptor.kt       UAC1/UAC2 描述符解析、alt 选择、采样率 control transfer
├── audio/
│   ├── MediaScanner.kt        MediaStore 扫描
│   ├── PlaybackEngine.kt      解码线程、seek、切歌、sink 重建
│   ├── AudioSink.kt           AudioTrackSink / DirectUsbSink
│   ├── PlayerHub.kt           单例 + 多播回调
│   └── PlaybackService.kt     前台服务 + 媒体通知
├── native/UsbIso.kt           JNI 声明
├── ui/TrackAdapter.kt         曲库列表
├── ui/UsbRows.kt              USB 设备行
└── util/
    ├── PcmCodec.kt            S16/S24_3LE/S32/F32/U8 编解码
    └── Resampler.kt           16 tap / 128 相位 Kaiser 多相重采样

app/src/main/cpp/
├── CMakeLists.txt
└── usb_iso.cpp                usbfs ISO URB 队列（无第三方依赖）
```

## 构建

### 方式 A：GitHub Actions（不用装 Android SDK）

1. 新建一个空仓库（public 或 private 都行）。
2. 把本工程整个目录推上去：

   ```bash
   cd LittleSauceHiFi
   git init
   git add .
   git commit -m "LittleSauce HiFi"
   git branch -M main
   git remote add origin https://github.com/<你的账号>/<仓库名>.git
   git push -u origin main
   ```

3. 打开仓库的 **Actions** 页，左侧选 `Build APK`，等 `assembleDebug` 跑完（首次约 5～10 分钟，
   要装 SDK + NDK）。
4. 点进这次运行，页面底部 **Artifacts** 下载 `LittleSauceHiFi-debug`，解压得到 `app-debug.apk`。
5. 装到手机：`adb install -r app-debug.apk`，或直接把 apk 传到手机点安装。

workflow 在 `.github/workflows/build.yml`，也会在每次 push / PR 时自动构建。
如果删掉 `app/src/main/cpp/CMakeLists.txt`，CI 会自动以 `-PwithNative=false` 构建（只有模式 A），
不会因为缺 native 而失败。

### 方式 B：Android Studio

1. Android Studio Hedgehog 以上，装好 SDK 34、NDK `25.1.8937393`、CMake `3.22.1`。
2. `File → Open` 选本目录，等 Gradle sync 完成。
3. 直接 Run，或 `Build → Build Bundle(s)/APK(s) → Build APK(s)`。

### 方式 C：命令行

```bash
# 需要 JDK 17 + Android SDK 34 + NDK 25.1.8937393 + CMake 3.22.1
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 仓库里只带了 `gradle/wrapper/gradle-wrapper.properties`。第一次用命令行前，
> 先在 Android Studio 里打开一次（会自动补齐 `gradle-wrapper.jar`），
> 或者本地装过 gradle 的话执行 `gradle wrapper --gradle-version 8.6`。

关闭 native 层：`./gradlew :app:assembleDebug -PwithNative=false`。

## 手机上怎么用

1. 插上小尾巴（USB-C DAC / 转接头 + DAC）。
2. 首次启动会申请「读取音频文件」，允许后自动扫描本地音乐（也点曲库页的「重新扫描」）。
3. 底部 **USB** 页：
   - 「系统 USB 通路」——默认，插上就能出声，采样率跟随曲目。
   - 「独占直连」——需要先把小尾巴授权给本应用；选中设备后播放即走模式 B。
     状态行会显示实际跑起来的 `kHz/bit/ch`、alt setting 和端点地址。
4. **播放** 页可切歌、拖动进度、调音量（模式 B 下是软件音量，直通模式下系统音量键无效）。

USB 插入时会弹出系统「允许访问该 USB 设备？」对话框，勾选「默认用于此设备」后以后不再问。

## 限制（直连模式为什么可能失败）

模式 B 走的是 Linux usbfs，能不能成功**取决于设备厂商的内核配置**，失败时界面会显示具体原因：

| 现象 | 原因 | 处理 |
|---|---|---|
| `接口被占用且无法接管（claim=Device or resource busy…）` | 内核 `snd-usb-audio` 已绑定该接口，非 root 应用无权 `USBDEVFS_DISCONNECT` | 用模式 A；或 root 后先 `echo -n "1-1:1.0" > /sys/bus/usb/drivers/snd-usb-audio/unbind` |
| `native 直通库未装载` | 构建时 `withNative=false`，或 ABI 不在 arm64-v8a/armeabi-v7a | 用带 native 的构建产物 |
| `该端点使用高带宽多包模式（mult=2/3）` | 端点用 wMaxPacketSize 高 2 位做多包传输 | 少见，用模式 A |
| `端点包长不足以承载该采样率/位宽` | 选到的 alt setting 包太小 | 降低位深或采样率，用模式 A |
| 周期性爆音 / 卡顿 | URB 队列欠载（后台被限流、GC 抖动） | 关掉电池优化，别在后台跑重活 |

要点：**模式 A 已经能做到「按曲目原生采样率输出」**（`AudioTrack` 用源采样率创建 + `setPreferredDevice`），
这是非 root 设备上最接近 bit-perfect 的稳妥做法。模式 B 是给内核放行 usbfs 直连的设备/root 环境准备的加分项。

## 技术要点

- **解码**：`MediaExtractor` + `MediaCodec`，输出统一转 float32 交织。
- **重采样**：只有源采样率不被端点支持时才启用，16 tap、128 相位、Kaiser β=8、每相位 DC 归一化、
  cutoff = min(1, ratio) 抗混叠；能直通就绝不重采样。
- **切歌**：每首歌重建 sink。采样率变了就重建 AudioTrack 或重开 URB 队列，避免中途变速。
- **isoc packet 分配**：`carry += rate; frames = carry / pps; carry -= frames * pps`，
  pps 在 HS 上是 8000（每 microframe），FS 上是 1000（每毫秒），把「每包整数帧」的余数均匀摊开，
  避免长片累积漂移。队列 HS 16×8 包 ≈ 16ms，FS 8×4 包 ≈ 32ms。
- **音量**：模式 B 无法用硬件音量，`softwareVolume` 在 float 域相乘，0～100% 线性。
- **错误可见**：所有回落路径都写进 `OutputInfo.note` 并显示在播放页输出卡片上，不静默降级。

## 已知限制

- 只做本地文件播放（不做流媒体/网络）。
- 曲库走 MediaStore，扫描不到的文件（如非媒体目录下的冷门格式）不会被收录。
- APE/DSF/DFF 能不能播取决于系统 MediaCodec 是否有对应解码器；没有的话会报「解码器不支持」。
- 不支持 DSD 原生输出（DoP 未实现）。
- 通知栏控制是基础的四个按钮，没接 `MediaSession` 的系统媒体中心联动。

## License

个人使用随意，出事自己担。
