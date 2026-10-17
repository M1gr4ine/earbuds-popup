# 从源码构建

本项目直接调用 Android SDK 工具编译，应用代码没有第三方运行时依赖。脚本不会下载 SDK、访问手机或生成发布签名密钥。

## 环境

- Python 3.9 或更新版本。
- 完整 JDK，可通过 `JAVA_HOME` 或 `PATH` 找到 `java` / `javac`；当前验证使用 JDK 21。
- Android SDK Platform 36 和 Build-Tools 37.0.0；可用命令行参数选择已安装的其它版本，但需自行验证兼容性。

## 运行测试

```sh
python scripts/build.py --test
```

这一步仅需 JDK，不需要 Android SDK。覆盖电量协议、CRC / 分片 / 异常帧、佩戴状态协议、连接去重、型号配置、设置边界与监听状态策略。

## 编译未签名 APK

设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 后：

```sh
python scripts/build.py --unsigned
```

也可明确指定工具版本：

```sh
python scripts/build.py --sdk /path/to/android-sdk --platform 36 --build-tools 37.0.0 --unsigned
```

非标准 SDK 布局可直接指定文件位置：

```sh
python scripts/build.py --android-jar /path/to/android.jar --build-tools-dir /path/to/build-tools --unsigned
```

输出为 `dist/Earbuds-Popup-1.0.3-unsigned.apk`。未签名 APK 需要签名后才能安装。

## 使用自己的签名密钥

准备自己的 keystore 和 alias，通过环境变量提供密码。示例使用 PowerShell：

```powershell
$env:EARBUDS_STORE_PASSWORD = "你的密钥库密码"
$env:EARBUDS_KEY_PASSWORD = "你的密钥密码"
python scripts/build.py --keystore "C:\keys\earbuds.jks" --alias earbuds
```

签名模式会保留 unsigned APK，另输出 `dist/Earbuds-Popup-1.0.3-signed.apk`，并运行签名验证。工具输出和临时文件位于 `dist/`、`build/temp/`，均不提交到仓库。Releases 使用的签名私钥不公开。

不同签名不能直接覆盖已有安装。同一密钥用于后续升级，才能保留原应用数据。
