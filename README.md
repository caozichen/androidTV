# LibreTV Android

阶段 7 修订版为 0.6.2（14）。桌面的「LibreTV」对应正式应用 `dev.libretv.android`，「LibreTV 调试版」对应 `dev.libretv.android.debug`，「LibreTV 演练版」对应 `dev.libretv.android.rehearsal`。三个版本各自保存数据，覆盖升级使用相同包名、相同签名和更高版本号。

## 首次正式签名

1. 在本机 Android Studio 选 **Build → Generate Signed App Bundle or APK → APK → Create new**。在工程外的私人目录创建密钥库，例如 `~/.config/libretv-android/release/`。设置别名、独立生成的强密码和至少 25 年有效期。将密码保存在自己的密码管理器，后续发行复用同一把密钥。
2. 用 Studio JBR 的 `keytool -list -v -keystore /原始密钥库绝对路径 -alias 自己的别名` 查看私钥条目（PrivateKeyEntry），独立记录其公开证书 SHA-256。将密钥库做一份加密备份，再恢复到另一私人目录。按下方「验证备份恢复」实际用恢复件签署样本，成功后才将这份备份标为可恢复。密码与备份分开保管。
3. 在私人目录准备 UTF-8 的 `signing.properties`。目录权限使用 `700`，配置及密钥库权限使用 `600`。用本机编辑器填入下列字段。路径需为绝对路径，特殊字符按 Java Properties 语法转义，反斜线写两次。若密钥格式要求密钥密码与库密码相同，两个字段填同一个密码。

```properties
purpose=release
storeFile=/密钥库的绝对路径
keyAlias=自己的别名
storePassword=库密码
keyPassword=密钥密码
```

4. 从工程目录运行经过校验的交付命令。使用本机 Python 3、Studio JBR 和已有缓存的 Android SDK/Gradle 依赖，将环境变量换成实际值。签名用途、预期版本及独立记录的证书指纹任一不匹配时，命令会失败。交付路径须为新文件名。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export LIBRETV_SIGNING_FILE="$HOME/.config/libretv-android/release/signing.properties"
export LIBRETV_EXPECTED_CERT_SHA256='填写独立记录的正式证书SHA-256'
python3 tools/release.py build release \
  --cert-sha256 "$LIBRETV_EXPECTED_CERT_SHA256" --version-name 0.6.2 --version-code 14 \
  --output "$HOME/Downloads/LibreTV-0.6.2-release.apk"
```

演练时选择仓库外的 `purpose=rehearsal` 配置，改用 `build rehearsal`、独立记录的演练证书指纹及新的演练交付文件名。脚本运行 clean、单测、release Lint、签名和 APK 打包，验证待交付副本的包名、版本与证书后才输出新文件及文件哈希。上次构建留下的中间目录不作为交付入口。

脚本通过单次 Gradle 调用参数 `-PlibretvSignRelease=true` 开启凭据读取。手动构建签名 AAB 时在同一环境执行 `./gradlew --offline --no-configuration-cache -PlibretvSignRelease=true :app:bundleRelease`；演练再加 `-PlibretvRehearsal=true`。日常调试和 IDE 同步使用默认模式，即使终端保留了演练环境变量或旧配置路径，也无需读取签名文件。签名开关只用于单次发行任务。

### 验证备份恢复

从工程目录运行以下命令，使用恢复出来的密钥库和独立保存的预期指纹。样本可选本机已有的演练 APK。根据工具提示在本机输入备份所用库密码；如另有私钥密码，按提示输入。工具在临时目录签署副本并验证实际签名，原样本及备份保持可复用。仅含公钥证书的库、错误的私钥密码或其他私钥均不能通过。

```sh
python3 tools/release.py verify-backup \
  --keystore '/已恢复的备份绝对路径' --alias '自己的别名' \
  --sample-apk '/测试样本APK的绝对路径' --cert-sha256 "$LIBRETV_EXPECTED_CERT_SHA256"
```

## 试用阶段 6 续播

播放同一部影片的两集，分别拖到不同位置，再打开另一部影片。强制停止应用并冷启动，从首页「最近观看」进入，核对来源、集名和时间轴。每个来源的每一集独立记录进度。重新打开会刷新详情；缺集、重名或只能按不稳定下标识别时，回到详情手选。已看完的集可以重播，有下一集时也能从详情继续。

详情里的「清除单集进度」只重置所选线路上的一集；首页删除会移除该来源下整部影片的历史。最近观看遵循分类过滤，更多记录可以分批展开。停用源可以重新启用，删除后重新添加同一端点仍可关联原历史。断网恢复后播放会话可从上次有效位置继续，地址失效时对同集自动刷新一次。本地数据库不保存分集播放地址。

## 试用阶段 5 播放器

从详情点开一集，可以拖动时间轴、暂停、上一集、下一集，或从「选集」切到当前线路的其他集。横屏全屏时点画面可唤回控件，按系统返回先退出全屏，再按一次回到原详情。自然播完后可重播或选择下一集。

回到桌面会暂停。重新打开应用后仍停在这集，点播放可继续。旋转时保留当前播放会话。

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
