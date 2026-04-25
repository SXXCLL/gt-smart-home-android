# gt-smart-home-bridger

这是基于 [jupiter2021/smart-home-zigbee](https://github.com/jupiter2021/smart-home-zigbee) 的 Android 集成版本，通过 Chaquopy 在 App 内运行 Python。

## 1) 上游参考 📚

本项目复用了上游项目在协议和巴法云接入上的实现思路：

- [jupiter2021/smart-home-zigbee](https://github.com/jupiter2021/smart-home-zigbee)

协议细节、设备发现方法和巴法云约定，请优先参考上游文档。

## 2) 项目概述 🧩
- Android App + 前台服务（`PythonRunnerService`）
- 此App作为后台服务运行在精装面板上，不影响精装面板原功能
- 通过 Chaquopy 在 App 内运行 Python 桥接逻辑
- MQTT 桥接 + 网关 TCP 转发
- 内置后台巡检（自定义间隔），用于健康检查和重连触发
- 支持一键自动将设备上云，支持巴法云的设备状态回显

本代码库由 vibe coding 和多轮迭代形成。

This app is provided *"AS IS"*, without warranty of any kind, express or implied. Use at your own risk. The author is not liable for any claim, damages, or other liability arising from its use, and may handle issues at their own discretion.

## 3) 使用方式 🚀

### 3.1 克隆仓库

```bash
git clone https://github.com/SXXCLL/gt-smart-home-bridger.git
cd gt-smart-home-bridger
```

### 3.2 准备开发环境与依赖 🛠️

建议先准备：

- Android Studio
- Android SDK + Platform Tools
- JDK（已配置 `JAVA_HOME`）
- Python 3.14（用于 Chaquopy build step）

项目依赖由 Gradle 和 Chaquopy 的 pip 配置统一管理。

### 3.3 发现设备、获取 Bemfa Key，并填写配置 🔧

设备发现和 Bemfa Key 获取方式请参考上游：

- [smart-home-zigbee](https://github.com/jupiter2021/smart-home-zigbee)

然后编辑：

- `app/src/main/python/config.example.json`

说明：

1. 新风作为仅支持风量设置的“空调类设备”处理。
2. 当前仅适配硬件场景；软件场景不走本应用桥接链路，建议在米家中配置，泛用性更好。

### 3.4 编译并生成 APK 📦

推荐使用 Android Studio 打开项目，完成依赖同步后构建即可。

### 3.5 通过 ADB 安装 APK

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 3.6 通过 ADB 发送 Recents / Home 按键

```bash
adb shell input keyevent KEYCODE_APP_SWITCH
```

### 3.7 App 内首次操作 ✅

1. 打开应用  
2. 点击 **一键重置配置**  
3. 点击 **一键添加设备到云**  
4. 点击 **启动**

### 3.8 Android 10 权限与省电设置（操作说明）🔋
ADB Bash
```bash
adb shell dumpsys deviceidle whitelist com.sherry.myapplicationpure
adb shell cmd deviceidle whitelist com.sherry.myapplicationpure
```

下列权限设置供参考
#### Android 10 手动设置路径

1. 进入 `设置 -> 应用和通知 -> 特殊应用权限 -> 电池优化`  
   找到本应用，设置为 **不优化**。
2. 进入 `设置 -> 电池 -> 应用启动管理`（不同 ROM 名称可能不同）  
   将本应用设为 **允许自启动/后台运行**。
3. 进入 `设置 -> WLAN -> 高级设置`（或省电相关设置）  
   关闭与 Wi-Fi 休眠、后台断网相关的省电项（不同 ROM 名称不同）。
4. 如果系统有“后台冻结/智能省电/睡眠应用”列表，确保本应用不在受限列表中。



### 3.9 完成 🎉

- 当配置和权限设置正确后，应用可以运行桥接服务、接收 Bemfa 指令，并转发到网关设备。
- 使用米家APP在第三方平台加入巴法云，同步设备，即可使用小爱同学控制各设备。
- 使用巴法云APP可以在APP上查看设备状态，并控制各设备。
- 由于米家生态对第三方平台的限制，需要借助米家音响和小爱训练计划(可直接控制第三方设备)来实现第三方设备的场景联动。

## 4) TODO

| Progress | Item |
| --- | --- |
| ⏳ Planned | 处理设备断电重启时的自启动 |
| ⏳ Planned | 优化后台巡检，仅在有情况发生时处理 |
