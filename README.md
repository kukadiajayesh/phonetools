# Phone tools

Small app for the Moto G 2014 (LineageOS 15, Android 8.0) test phone.

- **Memory widget**: available / free RAM, used bar, swap used, battery percentage, voltage and temperature, charger input voltage and charging current, and CPU and RAM usage graphs of the last minute. The memory rows, CPU temperature and the graph update every second while the screen is on (the graph scrolls smoothly in between). While an app covers the home screen the widget is not redrawn, only sampled, so the graphs are complete on return; this needs the Network speed service to see what is on screen, and without it the widget always updates (a foreground service with a minimum-priority notification). Refreshes every minute and on plug/unplug. Tapping it frees memory: it clears the recent apps (needs the Network speed accessibility service below), stops other apps' background processes, and shows how much RAM and swap was freed. Swap only shrinks as those processes go; emptying it outright needs root. The background imitates iOS "liquid glass" from the wallpaper behind it (light blur, edges that bend in the wallpaper from just outside like a lens, with colour fringing at the corners, a tint that follows the wallpaper's brightness, and light catching the rim from the top left rather than an even border; drawn by an AGSL shader on the GPU on Android 13+) and is rebuilt as soon as the wallpaper changes; tap the widget once after placing or moving it so it learns where it is. Needs `adb shell pm grant dev.local.phonetools android.permission.READ_EXTERNAL_STORAGE`.
- **Network speed**: a CPU use graph of the last 40 seconds and download and upload in KB/s in the status bar, just left of the status icons and clock, updated every second while the screen is on. It is an accessibility service (apps cannot add views to the status bar); turn it on in Settings > Accessibility > Network speed, or with `adb shell settings put secure enabled_accessibility_services dev.local.phonetools/.SpeedService` and `adb shell settings put secure accessibility_enabled 1`. The graph and text are white or black to match the status icons, which it samples from a screen capture; when the service starts it asks to capture the screen (tick "Don't show again", or allow it beforehand with `adb shell appops set dev.local.phonetools PROJECT_MEDIA allow`). Without it they stay white.
- **Lock shortcut**: locks the screen at once (needs device admin).

## Other devices

The app installs on any phone with Android 8.0 or newer (tested on the Moto G 2014 with LineageOS 15 and a OnePlus 9 Pro with OxygenOS 14) and needs no root (only `userinit.sh` and the hosts file
below do). Features that a phone does not allow are left out, and the rest keeps working:

| Feature | Needs | When it is not available |
|---|---|---|
| RAM, swap, battery, charging status and current | nothing | always works ("Plugged in, not charging" when the phone holds the battery below full; current in mA or µA, whichever the phone reports) |
| Charger input voltage | a readable `/sys/class/power_supply/usb/voltage_now` | left out of the charging line |
| CPU use (widget row, status bar graph) | `/proc/stat` where readable (SELinux permissive, as on the Moto G). Stock Android 8+ blocks it; then it is estimated from each core's idle statistics and clock speed (`/sys/devices/system/cpu/cpu*/cpuidle`, `cpufreq`), within a few percent on a OnePlus 9 Pro | widget shows only the temperature (or hides the row); status bar shows only the speed |
| CPU temperature | a readable CPU thermal zone (Qualcomm, MediaTek, Exynos, Intel names). Often blocked on Android 10+ | left out |
| Glass background | the storage permission; the app asks for it when the Network speed service starts (OxygenOS does not let adb grant it). Without it Android 14 hands out its default wallpaper instead of the real one | plain dark background, never glass of the wrong picture. Live wallpapers always get the plain background: Android has no picture of them to cut glass from |
| Network speed overlay | the accessibility service, a status bar using stock Android view ids (Android 8-11 `system_icon_area`, 12+ `status_bar_end_side_content`/`system_icons`, or the clock), and room left of the status icons | not shown (some vendor builds such as Samsung or MIUI). Where the system shows its own network speed (OxygenOS, LineageOS 17+, MIUI) only the CPU graph is drawn. With notification icons filling the bar (OxygenOS) the graph narrows, down to 24 dp, then hides |
| Black/white text to match the icons | screen capture permission. Where adb cannot pre-allow it (OxygenOS), Android asks each time the service starts (after a reboot or an app update) | always white |
| Clearing recents on tap | the accessibility service, stock Android 8 recents (Android 9+ moved recents into the launcher) and English "Clear all" | skipped after a few seconds; background processes are still stopped |
| Lock shortcut | device admin | asks to enable it |

## Build and install

```bash
./build.sh
adb install -r phonetools.apk
adb root && adb shell dpm set-active-admin dev.local.phonetools/.AdminReceiver
```

Add to the home screen (shows an "Add to Home screen" prompt on the phone):

```bash
adb shell am start -n dev.local.phonetools/.PinActivity --es pin widget
adb shell am start -n dev.local.phonetools/.PinActivity --es pin lock
```

## Uninstall

```bash
adb shell dpm remove-active-admin dev.local.phonetools/.AdminReceiver
adb uninstall dev.local.phonetools
```

## Boot script

`userinit.sh` is the copy of `/data/local/userinit.sh` on the phone: zram 2 GiB, swappiness 100, and CPU (all 4 cores, 1.19 GHz), memory bus and GPU pinned to their highest clocks at every boot (`mpdecision` stopped; `thermal-engine` still throttles when hot).
Restore with `adb root`, `adb push userinit.sh /data/local/userinit.sh`, `adb shell chmod 0755 /data/local/userinit.sh`.

## Ad blocking

`/system/etc/hosts` on the phone holds the StevenBlack ad/tracker list (about 72,500 domains).
`hosts.original` is the stock file. Restore with `adb root`, `adb remount`, `adb push hosts.original /system/etc/hosts`.
