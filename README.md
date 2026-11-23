# LibreTV Android

阶段 0 的安卓工程。启动页使用 Jetpack Compose，分别运行两项组件检查。

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

Android 启动输出应包含 `Status: ok` 和 `LaunchState: COLD`。应用进入「LibreTV / 环境验证」，两项应分别显示「通过 · 已写入并读回 1 条记录」和「通过 · 已创建 ExoPlayer 和 HLS 媒体源」。以上为设备上的运行时检查。本阶段没有独立的 JVM 单元测试用例。

本机 Gradle 缓存包含所需依赖后，可用 `./gradlew --offline clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 验证重新编译。调试 APK 使用本机调试证书签名。
