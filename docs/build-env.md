# Android 本机构建环境（已验证可产出 APK）

> 全部组件装在仓库内 `<repo>\.toolchain\`，**不改系统环境变量**、不依赖 Android Studio。
> 验证时间：2026-10-24。验证方式：`.toolchain\probe` 最小 Jetpack Compose 应用 `assembleDebug` 成功产出 APK。

## 一、已验证版本组合

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | Microsoft OpenJDK **21.0.5+11-LTS** | `<repo>\.toolchain\jdk` |
| Gradle | **8.14.3** | `<repo>\.toolchain\gradle` |
| Android SDK | cmdline-tools **12.0** (11076708) | `<repo>\.toolchain\android-sdk\cmdline-tools\latest` |
| SDK Platform | **android-35** (rev 2) | `.toolchain\android-sdk\platforms\android-35` |
| Build-Tools | **35.0.0**（AGP 8.7.3 实际还会自动装 **34.0.0**） | `.toolchain\android-sdk\build-tools\35.0.0` / `34.0.0` |
| Platform-Tools | 37.0.1（adb） | `.toolchain\android-sdk\platform-tools` |
| Android Gradle Plugin | **8.7.3** | Gradle 依赖缓存 |
| Kotlin | **2.0.21** + `org.jetbrains.kotlin.plugin.compose` 2.0.21 | Gradle 依赖缓存 |
| Compose | BOM **2024.10.01**（material3 / ui / foundation） | Gradle 依赖缓存 |
| Compose Activity | `androidx.activity:activity-compose:1.9.3` | Gradle 依赖缓存 |
| 工程参数 | `compileSdk=35`、`targetSdk=35`、`minSdk=26`、`jvmTarget=17` | — |

Gradle 发行包来源：**腾讯云镜像** `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip`
（`services.gradle.org` 会 307 跳转到 `github.com`，本机 github 不可达，因此官方地址下载必然卡死）。
JDK 来源：`https://aka.ms/download-jdk/microsoft-jdk-21.0.5-windows-x64.zip`。

## 二、绝对路径

```
JDK        : <repo>\.toolchain\jdk
Gradle     : <repo>\.toolchain\gradle\bin\gradle.bat
Android SDK: <repo>\.toolchain\android-sdk
Gradle 用户缓存 : <repo>\.toolchain\gradle-home   （不是 ~/.gradle！见坑 #1）
Android 用户目录: <repo>\.toolchain\android-user-home
临时目录        : <repo>\.toolchain\tmp
验证工程        : <repo>\.toolchain\probe
probe APK       : <repo>\.toolchain\probe\app\build\outputs\apk\debug\app-debug.apk  (8.3 MB)
```

apk 校验（`aapt2 dump badging`）：`package: com.probe`、`minSdkVersion:'26'`、`targetSdkVersion:'35'`、`compileSdkVersion='35'`。

**主工程（`iCloudAlbum`）也已用同一工具链构建成功**：`scripts\build.ps1` 跑仓库根 `assembleDebug` 产出
`<repo>\app\build\outputs\apk\debug\app-debug.apk`（10.3 MB）。
probe 与主工程共用 `.toolchain\gradle-home` 依赖缓存。

## 三、一键构建（推荐用法）

```bat
rem 在 <repo> 下：
scripts\build.cmd                                rem 默认对仓库根工程跑 assembleDebug
scripts\build.cmd -Task assembleRelease
scripts\build.cmd -Task clean,assembleDebug
scripts\build.cmd -Task assembleDebug -ProjectRoot .toolchain\probe
scripts\build.cmd -Task assembleDebug -NoDaemon
```

等价的 PowerShell 写法（**必须绕过执行策略**，见坑 #4）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 -Task assembleDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 -Task clean,assembleDebug -ProjectRoot .toolchain\probe
```

脚本参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-Task` | `assembleDebug` | Gradle 任务名，可逗号/空格分隔多个 |
| `-ProjectRoot` | 仓库根 `<repo>` | 含 `settings.gradle[.kts]` 的工程目录 |
| `-NoDaemon` | 关 | 传 `--no-daemon`（慢，但不留后台进程） |

脚本做的事：进程级设置 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `GRADLE_USER_HOME` / `ANDROID_USER_HOME` / `TMP`+`TEMP` / `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...`，然后调用
`.toolchain\gradle\bin\gradle.bat -p <ProjectRoot> --console=plain <Task>`，成功后在末尾打印
`APK: <绝对路径> (<大小> KB)`（枚举 `<module>\build\outputs\apk\` 下所有 apk）。

## 四、实测耗时

| 场景 | 耗时 |
|---|---|
| **首次构建**（空缓存：下载 AGP/Kotlin/Compose 依赖 + AGP 自装 build-tools 34.0.0 + 全量编译） | **10 分 05 秒** |
| 增量构建（无改动，`assembleDebug` UP-TO-DATE，含 Gradle 守护进程预热） | **1.9 秒** |
| `clean,assembleDebug`（依赖已在本地缓存，含 build cache 命中） | **3.9 秒**（probe，2026-10-24 实测 7.1s） |

首次构建的 10 分钟几乎全花在下载 AGP/AndroidX/Compose 依赖上（约 1 GB 进 `.toolchain\gradle-home`），
真正编译只要十几秒。主工程（15 个 Kotlin 源文件）在缓存预热后 `assembleDebug` 也是秒级，产出 APK 10.3 MB。

依赖已预热在 `.toolchain\gradle-home`（约 986 MB）。主工程若沿用上表版本（AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01），
不需要再下载这套依赖；引入新依赖（core-ktx、lifecycle、navigation 等）时首次构建会额外下载几十秒。

## 五、踩过的坑 / 环境约束（重要）

1. **在本环境中，JVM 进程不能写用户目录（`%USERPROFILE%\`）**，写 `~/.gradle`、`%TEMP%`
   会抛 `java.nio.file.AccessDeniedException`（PowerShell 自身可写，但 java/gradle 子进程不行）。
   → 因此 `build.ps1` 把 `GRADLE_USER_HOME`、`ANDROID_USER_HOME`、`TMP`、`TEMP`、
   `-Djava.io.tmpdir` 全部重定向到 `.toolchain\` 下的目录。**手动跑 Gradle 时也必须照做**，否则依赖缓存写不进去。
2. **`sdkmanager` 一开始报 `Failed to download any source lists!`**，真实原因是它把仓库清单缓存写到
   `%USERPROFILE%\.android\cache` 时被 AccessDenied（不是网络问题，`dl.google.com` 本身可达）。
   设 `ANDROID_USER_HOME=<工作区>` 后一次成功。
3. **Gradle 发行包不能从 `services.gradle.org` 下**：307 → `github.com/gradle/gradle-distributions`，本机 github 不通。
   已改用腾讯云镜像安装。**不要给工程加 Gradle Wrapper**（wrapper 会再去官方地址下载）；若必须用 wrapper，
   把 `distributionUrl` 指向 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip`。
4. **PowerShell 执行策略是 Restricted**（`Get-ExecutionPolicy -List` 全为 Undefined → 默认 Restricted），
   直接 `.\scripts\build.ps1` 会报“禁止运行脚本”。用 `scripts\build.cmd`（内部只对本进程用
   `-ExecutionPolicy Bypass`，不改系统策略）或 `powershell -ExecutionPolicy Bypass -File`。
5. **AGP 8.7.3 默认 Build-Tools 是 34.0.0**：即使已装 35.0.0，首次构建仍会自动 `Install Android SDK Build-Tools 34`。
   probe 与主工程的 `app/build.gradle.kts` 都已显式 `buildToolsVersion = "35.0.0"`，不会再触发；
   34.0.0 仍留在 SDK 里（已下载好，断网也不影响）。若 `buildToolsVersion` 被移除，首次构建会重新联网装 34.0.0。
6. 日志里 `Unable to initialize metrics, ensure %USERPROFILE%\.android is writable` 是 AGP 统计信息写不进用户目录的**无害告警**，
   不影响构建产物，可忽略。
7. `JAVA_TOOL_OPTIONS` 会让每个 JVM 往 stderr 打一行 `Picked up JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=...`，**正常现象**。
8. 依赖仓库用 `google()` + `mavenCentral()` 实测可用（repo1.maven.org 通）；`settings.gradle.kts` 里另配了
   `maven.aliyun.com` 镜像作为兜底（`repository/public`、`repository/google`、`repository/gradle-plugin`）。
9. 系统 Java 是 24（`C:\Program Files\Java\jdk-24`），**不要用它跑本项目**：脚本始终显式指定
   `.toolchain\jdk` 作为 `JAVA_HOME`。若在 IDE / 命令行直接跑，请用
   `-Dorg.gradle.java.home=<repo>\.toolchain\jdk`。
10. **缓存共享方式**：所有构建（probe 与主工程）都通过 `build.ps1` 指向同一个
    `GRADLE_USER_HOME=.toolchain\gradle-home`，因此依赖只下载一次；项目级 `.gradle` 缓存与
    `build\` 产物仍是各工程独立的。`%USERPROFILE%\.gradle` 实际是空的（只有 Gradle 自己创建的
    `native\`、`notifications\` 空目录），**不是**本次构建使用的缓存。

## 六、主工程接入注意

- 仓库根工程直接 `scripts\build.cmd`（默认 `-ProjectRoot` 就是仓库根）；子模块构建会一并执行。
- SDK 位置通过 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 传入；主工程另外放了一份 `local.properties`：
  `sdk.dir=<repo>\\.toolchain\\android-sdk`（脚本不依赖它，二者都对）。
- 根 `gradle.properties` 建议：`android.useAndroidX=true`、`org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`。
  若主工程 `gradle.properties` 覆盖 `org.gradle.jvmargs`，不要丢掉 `-Djava.io.tmpdir`，因为脚本已用 `JAVA_TOOL_OPTIONS` 兜底。
- 若主工程需要签名 release：在 `~` 之外的路径放 keystore，`signingConfigs` 里用绝对路径；debug 签名不需要配置。
- 磁盘占用：`.toolchain\` 约 **2.5 GB**（JDK 326 MB + Gradle 145 MB + SDK 536 MB + Gradle 缓存 986 MB + probe 构建产物 57 MB）。

## 七、安装命令记录（可复现）

```powershell
# JDK 21
curl.exe -L -o jdk21.zip https://aka.ms/download-jdk/microsoft-jdk-21.0.5-windows-x64.zip   # 解压后改名 .toolchain\jdk

# Gradle 8.14.3（官方地址会跳 github，用腾讯镜像）
curl.exe -L -o gradle-8.14.3-bin.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip

# Android cmdline-tools -> .toolchain\android-sdk\cmdline-tools\latest
curl.exe -L -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip

$env:JAVA_HOME   = '<repo>\.toolchain\jdk'
$env:ANDROID_HOME= '<repo>\.toolchain\android-sdk'
$env:ANDROID_USER_HOME = '<repo>\.toolchain\android-user-home'   # 必须，见坑 #2
$env:SDKMANAGER_OPTS = '-Djava.io.tmpdir=<repo>\.toolchain\tmp'
$sd = '<repo>\.toolchain\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat'
'y'*50 | & $sd --sdk_root=$env:ANDROID_HOME --licenses
& $sd --sdk_root=$env:ANDROID_HOME 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
```
