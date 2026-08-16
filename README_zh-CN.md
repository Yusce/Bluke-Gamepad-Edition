<div align="center">

# Bluke Gamepad Edition

基于 [Bluke](https://github.com/arnav-kr/Bluke) 的非官方手柄增强版本。

[English](README.md)

</div>

## 简介

这个项目主要修改了 Bluke 的 Gamepad 部分，让 Android 手机或平板更适合作为触屏手柄使用，同时保留原版的 Keyboard、Touchpad 等功能。

最初目标：**用 Android 设备模拟手柄来控制 iPhone / iPad**。

普通 Android Bluetooth HID 手柄可以直接连接 Windows，但无法可靠地被 iOS / iPadOS 识别为游戏控制器。因此我尝试通过 **Pico 2 W + Joypad OS** 进行转接：

```text
Android → Bluetooth HID → Pico 2 W / Joypad OS → 目标设备
```

后来又继续完善了触屏手柄本身，并保留了直接连接 Windows 的 PC Direct 模式。

**不需要在 Windows 或其他最终被控制设备上安装专用的接收客户端。**

## 主要功能

- 可编辑手柄布局
- Xbox / PlayStation 风格控件
- 多点触控
- 区域摇杆
- 陀螺仪映射
- 布局导入 / 导出
- PC Direct 与 Joypad OS 两套 HID Profile
- 125 / 250 / 500 Hz 目标回报率

## 使用方式

### PC Direct

```text
Android → Bluetooth HID → Windows / 通用 HID 主机
```

1. 安装 APK 并授予蓝牙权限
2. 选择 **Non-Joypad OS Device**
3. 与目标设备配对并连接
4. 使用 Keyboard、Touchpad 或 Gamepad

> PC Direct 是通用 HID，并不是原生 XInput。部分只支持 XInput 的游戏可能需要 Steam Input 等兼容层。

### Joypad OS

```text
Android → Bluetooth HID → Pico 2 W / Joypad OS → 最终主机
```

1. 准备一块刷入 Joypad OS 的 Pico 2 W
2. 在应用中选择 **Joypad OS Device**
3. 与 Pico 2 W 配对并连接
4. 使用 Gamepad 页面
5. 由 Joypad OS 向最终设备输出其支持的手柄协议

 **iOS设备需使用Joypad的XInput模式**

> 如果修改 HID Profile 或升级版本后出现映射异常，建议在两端删除旧蓝牙配对后重新配对。

## 编译

需要：

- Android Studio
- Android SDK 36
- JDK 17

Debug 构建：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

## 致谢

- [Bluke](https://github.com/arnav-kr/Bluke) - 本项目的上游项目，作者 Arnav Kumar
- [kbsim](https://github.com/tplai/kbsim) - 原版 Bluke 使用的部分 UI / 音频素材
- [Joypad OS](https://github.com/joypad-ai/joypad-os) - Joypad OS 兼容目标

这是一个个人维护的非官方 fork，并大量使用Codex。

## License

[GNU Affero General Public License v3.0](LICENSE)
