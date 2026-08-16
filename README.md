<div align="center">

# Bluke Gamepad Edition

An unofficial, gamepad-focused fork of [Bluke](https://github.com/arnav-kr/Bluke).

[简体中文](README_zh-CN.md)

</div>

## About

This project mainly modifies Bluke's Gamepad mode to make Android phones and tablets more practical as touchscreen controllers, while keeping the original Keyboard and Touchpad features.

Original goal: **use an Android device as a gamepad to control an iPhone or iPad**.

A generic Android Bluetooth HID gamepad can connect directly to Windows, but it is not reliably recognized as a game controller by iOS / iPadOS. I therefore tried using **Pico 2 W + Joypad OS** as a bridge:

```text
Android → Bluetooth HID → Pico 2 W / Joypad OS → target device
```

I later continued improving the touchscreen controller itself while keeping a PC Direct mode for Windows.

One of the main characteristics of this project is that **no dedicated receiver or companion app needs to be installed on Windows or the final controlled device**.

## Features

- Editable controller layouts
- Xbox / PlayStation-style controls
- Multi-touch input
- Stickpad
- Gyroscope mapping
- Layout import / export
- Separate PC Direct and Joypad OS HID profiles
- 125 / 250 / 500 Hz target report rates

## Usage

### PC Direct

```text
Android → Bluetooth HID → Windows / generic HID host
```

1. Install the APK and grant Bluetooth permissions
2. Select **Non-Joypad OS Device**
3. Pair with and connect to the target device
4. Use Keyboard, Touchpad, or Gamepad

> PC Direct is generic HID, not native XInput. Games that only support XInput may require Steam Input or another compatibility layer.

### Joypad OS

```text
Android → Bluetooth HID → Pico 2 W / Joypad OS → final host
```

1. Prepare a Pico 2 W running Joypad OS
2. Select **Joypad OS Device** in the app
3. Pair with and connect to the Pico 2 W
4. Use the Gamepad page
5. Let Joypad OS expose a controller protocol supported by the final host

**iOS devices require the use of Joypad in XInput mode**

> If mappings become incorrect after changing HID profiles or updating the app, remove the old Bluetooth pairing on both sides and pair again.

## Build

Requirements:

- Android Studio
- Android SDK 36
- JDK 17

Debug build:

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

## Credits

- [Bluke](https://github.com/arnav-kr/Bluke) - upstream project by Arnav Kumar
- [kbsim](https://github.com/tplai/kbsim) - source of some UI / audio assets used by upstream Bluke
- [Joypad OS](https://github.com/joypad-ai/joypad-os) - Joypad OS compatibility target

This is a personal, unofficial fork and is not affiliated with or endorsed by the Bluke or Joypad OS projects.This project has been developed extensively with Codex.

## License

[GNU Affero General Public License v3.0](LICENSE)
