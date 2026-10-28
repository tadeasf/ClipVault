# Android emulator on the dev machine

Status 2026-10-28: **the emulator does not boot here.** Android launches are verified on a
physical device over adb instead.

## Environment

- Fedora 44, kernel 7.2.11-200.fc44.x86_64, GNOME 50.6 Wayland
- GPU: AMD Radeon RX 9060 XT (radeonsi, gfx1200)
- KVM: `emulator -accel-check` -> "KVM (version 12) is installed and usable."
- Android Emulator 37.2.12.0 (build 16428233)

## Attempts

| AVD | Image | Flags | Result |
|---|---|---|---|
| `nasplayer` (existing, other project) | unknown | `-no-window -no-audio -no-snapshot-save -no-boot-anim` | SIGSEGV during cold boot |
| `nasplayer` | unknown | `+ -no-snapshot -gpu swiftshader_indirect` | SIGSEGV during cold boot |
| `clipvault-api35` (new, `pixel_6`) | `system-images;android-35;google_apis;x86_64` 9.0.0 | `-no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect` | SIGSEGV during cold boot |

## Crash signature

Every run dies the same way, after "Emulator is performing a full startup":

```
coredumpctl:
  Executable: ~/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless
  Signal: 11 (SEGV)
  #0  0x0000... n/a (n/a + 0x0)        <- jump through a null function pointer
```

Last non-INFO log lines before the crash:

```
WARNING | File System is not ext4, disable QuickbootFileBacked feature
WARNING: cannnot unmap ptr 0x7f886bc01000 as it is in the protected range from 0x7f87cbc00000 to 0x7f886be00000
USER_INFO | Emulator is performing a full startup. This may take upto two minutes, or more.
```

## Not yet tried

- Windowed mode (`qemu-system-x86_64`, not `-headless`) with `-gpu host`.
- An older emulator build (a 36.x release) in case 37.2.12 regressed on kernel 7.2.
- An API 36 image.

Until one of these works, device-dependent checks (instrumented tests, launch smoke tests) run on
a phone: `./gradlew :shared:connectedAndroidDeviceTest` and `adb install` of the debug APKs.
