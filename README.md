# LibreTV Android

阶段 1 点播原型，调试包使用 `dev.libretv.android.debug`。主页可导入个人 CMS 点播源、按片名搜索、查看线路与剧集，点击一集后由 Media3 直接播放。用户配置保存在应用私有空间。主页的「环境检查」保留阶段 0 的两项组件检查。

- 在 IO 线程打开 Room 内存数据库，通过 KSP 生成的 DAO 写入一条记录，读回并比较内容，最后关闭数据库。
- 在主线程创建 Media3 ExoPlayer 和 HLS 媒体源，将媒体源设置给播放器，核对媒体 ID 和项目数量，再释放播放器。这个检查不请求网络或开始真实播放。

## 在 Android Studio 打开

选择 **File → Open**，打开包含本文件和 `settings.gradle.kts` 的目录。Gradle 设置选择工程的 Wrapper，Gradle JDK 选择 Studio 自带的 JBR 25。在 Device Manager 启动 **Medium_Phone_API_36**，选择 `app` 运行。

## 使用终端复核

在本目录运行以下命令。首次构建需要能访问 Google Maven、Maven Central 和 Gradle 分发站点。具体插件和库版本由 [版本目录](gradle/libs.versions.toml) 管理。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb devices -l
```

在模拟器已启动时，使用上一步列出的设备号。此处以 `emulator-5554` 为例。

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am force-stop dev.libretv.android.debug
adb -s emulator-5554 shell am start -W -n dev.libretv.android.debug/dev.libretv.android.MainActivity
```

Android 启动输出应包含 `Status: ok` 和 `LaunchState: COLD`。应用进入搜索首页。「环境检查」可独立查看 Room 读写和 HLS 初始化结果。阶段 1 的 JVM 测试覆盖 CMS 查询和数据解析，固定样例在 `app/src/test/resources/catalog`。

## 五步试用

1. 在 Android 8.0 或以上设备安装调试 APK，将个人点播源 JSON 放到手机的「下载」目录。
2. 打开 LibreTV，点击「导入片源」，在系统的文件选择器中选择 JSON。文件使用 `{"sources":[{"name":"示例","url":"https://example.org/api.php/provide/vod/"}]}` 格式，示例域名需替换为自己的有效接口。
3. 保留「如意资源」选中，输入「航拍中国」并点「搜索」。原型只查每个选定源的第一页，结果按来源分别显示。
4. 在如意结果中选择「航拍中国第一季」，点击「第01集」。轻触画面可调出进度条；用「返回」回到详情再选「第02集」。
5. 回首页点「播放对照样片」独立检查 HLS 解码。包内素材是自生成的 24 秒动态图和 440 Hz 测试音，共 6 个分片，可离线播放。在线结果随时间、片源和网络变化。

播放器显示清单、媒体加载、位置、首帧和音轨状态。连接调试环境后可用 `adb logcat -s LibreTVPlayback:I` 查看脱敏事件。日志中 `audio_output advancing=true` 表示音频输出时钟已推进，扬声器是否可听需现场试听。

本机 Gradle 缓存包含所需依赖后，可用 `./gradlew --offline clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 验证重新编译。调试 APK 使用本机调试证书签名。
