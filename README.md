# LibreTV Android

阶段 5 版本为 0.4.0（8）。调试包使用 `dev.libretv.android.debug`。点播源保存在应用私有 Room 数据库，首页可以搜索多源、查看逐源进度和部分结果，进入详情选线路、选集或换源，由 Media3 播放。主页保留环境检查。

## 试用阶段 5 播放器

从详情点开一集，可以拖动时间轴、暂停、上一集、下一集，或从「选集」切到当前线路的其他集。横屏全屏时点画面可唤回控件，按系统返回先退出全屏，再按一次回到原详情。自然播完后可重播或选择下一集。

回到桌面会暂停。重新打开应用后仍停在这集，点播放可继续。旋转时保留当前播放会话。进度为本次会话内状态，跨进程的历史与续播属于阶段 6。

## 阶段 4 试用

在「点播源管理」导入并启用多个个人源，搜索「航拍中国」。结果会逐源返回，可在其余源仍加载时打开影片。「各源状态」显示已完成页数、条数及失败原因。搜索最多同时请求 4 个 CMS 接口，每源最多 5 页、总时限 10 秒。默认过滤成人源与成人分类，可在首页「搜索片源」内调整。

在详情点「换源」，核对来源、年份并选择候选。应用重新获取目标详情。只有影片身份和剧集标签足够明确时才预选对应集，点击剧集后开始播放。缺集、预告、重复标签和身份不确定时需要手动选集。按返回可回到搜索列表。「重新搜索」会绕过 60 秒的搜索缓存。

部分搜索失败时，可以展开「各源状态」，在对应片源下选择「重试此源」。超过五页的片源标记为「仅前 5 页」，换源时需要手动选集。候选按钮显示条目 ID 以区分同源同名的版本。详情缓存从成功获取内容时起计时 60 秒。

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

1. 在 Android 8.0 或以上设备安装调试 APK。全新安装从零个源开始。覆盖升级 0.1.3 时，首次启动会一次性迁移旧片源和启用状态。
2. 把[脱敏样例](samples/source-list.json)放进手机的「下载」目录。在首页点「添加点播源」或「点播源管理」，选「从文件导入」。在尚未添加样例的设备上，应看到可导入 1、重复 1、无效 0、直播跳过 1。取消后数量不变。再次选择文件、确认导入后应新增一条，再次导入时应全部识别为重复。样例域名仅供验证配置流程。[错误样例](samples/source-list-invalid.json)中的三个点播条目均应列为无效。
3. 导入个人点播源文件，也可以手动填写名称与 HTTPS 接口。文件支持 UTF-8 JSON 对象和裸数组，最大 1 MiB。可逐项勾选，总数最多 100 个。普通源默认启用，标记为成人的源默认停用。同一端点重导保留已有名称和启用状态。点「测试」会检查 CMS 目录，并在该源下显示本次检查的时间和结果。
4. 启用有权使用且可访问的片源，回首页搜索「航拍中国」。点开影片和一集，查看播放首帧和时间进度。返回管理页改名或停用，重新搜索以核对来源。删除需二次确认。
5. 回首页点「播放对照样片」可单独检查 HLS 解码。包内素材是自生成的 24 秒动态图和 440 Hz 测试音，共 6 个分片，可离线播放。在线结果随时间、片源和网络变化。

播放器显示清单、媒体加载、位置、首帧和音轨状态。连接调试环境后可用 `adb logcat -s LibreTVPlayback:I` 查看脱敏事件。日志中 `audio_output advancing=true` 表示音频输出时钟已推进，扬声器是否可听需现场试听。

本机 Gradle 缓存包含所需依赖后，可用 `./gradlew --offline clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 验证重新编译。调试 APK 使用本机调试证书签名。
