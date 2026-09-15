# Spo2 Helper (Pixel Watch edition)

![Logo](https://raw.githubusercontent.com/Flyfish233/Spo2Helper/main/screenshot/ic_launcher_round.png)

A phone-plus-watch pair of apps for Google Pixel Watch (target: Pixel Watch 5, Wear OS 7).
The phone asks the watch for an **on-demand SpO2 spot check**, once or every N minutes. The
watch reads its raw optical (PPG) sensor for about 20 seconds, computes blood oxygen from the red
and infrared channels, adds a Health Services heart rate and last night's Google Health SpO2 for
comparison, and sends everything to the phone.

This is a rewrite of the original Samsung Galaxy Watch tool by
[Flyfish233](https://github.com/Flyfish233/Spo2Helper), which worked by remotely launching Samsung
Health's hidden SpO2 measurement screen. Pixel Watch has no such screen, so the approach changed.

## How on-demand SpO2 works on a Pixel Watch

- Google Health (formerly Fitbit) measures SpO2 **only during sleep** and offers no way to start a
  reading. Health Services has no SpO2 data type either (only heart rate is available on demand).
- The optical hardware is, however, exposed to any app through the ordinary Android
  `SensorManager` as a vendor sensor named **"AFE4950 PPG Sensor"** (a Texas Instruments optical
  front end). Each sample carries 16 values, one per LED/photodiode time slot; on Pixel Watch 3
  twelve of them are live.
- Google does not document which slot is red and which is infrared. The watch app therefore
  analyses every channel (DC level, pulse amplitude, perfusion index, dominant frequency, SNR),
  guesses red/IR from the usual ordering of perfusion (red < infrared < green), and computes

  ```
  R    = (AC_red / DC_red) / (AC_ir / DC_ir)
  SpO2 = a - b * R          (a = 110, b = 25 until you calibrate)
  ```

- The phone shows the per-channel table so you can override the red/IR assignment, and it does a
  one-point calibration against a fingertip pulse oximeter. Until you do that, treat the number
  as an uncalibrated estimate. This is not a medical device.

The estimator is pure Kotlin with JVM unit tests (`wear/src/test`).

## Install

Both APKs must be signed with the same key and share the application ID, otherwise the Wearable
Data Layer will not deliver messages between them.

```bash
./gradlew assembleDebug
adb -s <phone-serial> install mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-serial> install wear/build/outputs/apk/debug/wear-debug.apk
```

Requirements: phone on Android 14+, watch on Wear OS 5+ (Wear OS 6+ for on-watch Health Connect),
JDK 17+ and Android SDK platform 36 with extension level 19 to build.

## First run

1. **Watch**: open Spo2 Helper once and grant the permissions it asks for (body sensors / heart rate,
   notifications, and blood-oxygen read access on Wear OS 6+). The raw PPG sensor needs the same
   body-sensor permission.
2. **Phone**: open Spo2 Helper, allow notifications. Optionally tap *Grant Health Connect access* to
   also see the sleep SpO2 that Google Health syncs to the phone.
3. In Google Health, make sure the app is connected to Health Connect so SpO2 records are written.

## Use

- **Spot check now**: sends a message to every connected watch. The watch captures the PPG sensor
  for the configured number of seconds while Health Services measures heart rate, estimates SpO2,
  reads the newest sleep SpO2 record from Health Connect, and sends the result back. The phone
  stores it, shows it on screen and posts a notification.
- **Start monitoring**: a foreground service on the phone repeats the spot check at the interval
  you enter (default 10 minutes) until you stop it.
- **PPG channels and calibration** (phone): after a spot check, the table lists each sensor slot.
  Bold rows carry a heartbeat. Choose the red and infrared slot indices if the automatic guess is
  wrong, adjust the `a`/`b` constants or capture length, enter a reference SpO2 from a real
  oximeter and tap *Calibrate*, then *Send to watch*.
- The last raw capture is saved on the watch as `files/ppg-last.csv` for offline analysis:

  ```bash
  adb -s <watch-serial> shell run-as com.flyfish233.spo2helper cat files/ppg-last.csv > ppg.csv
  ```

  (`run-as` works for debug builds.)

## Builds and releases

GitHub Actions (`.github/workflows/android.yml`) runs the unit tests and lint on every push and
pull request, builds release APKs for phone and watch, and uploads them as the `spo2helper-apks`
artifact. Pushing a tag like `v3.0.0` also publishes them on a GitHub release.

Both APKs are signed with the same key, which the Data Layer requires. Without signing secrets
the release build falls back to the debug keystore, so CI APKs are installable but the key changes
whenever the runner's keystore does; uninstall before installing a new build. For a stable key,
add these repository secrets: `KEYSTORE_BASE64` (base64 of a `.jks`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Locally the same values can be given as environment variables, with
`KEYSTORE_FILE` pointing at the keystore.

## How it works

```
phone MainActivity / MonitorService
    -- MessageClient "/spo2/measure" -->  watch WatchListenerService
                                              -> MeasureService (foreground, type health)
                                                   SensorManager "AFE4950 PPG Sensor" -> Spo2Estimator
                                                   Health Services MeasureClient: HEART_RATE_BPM
                                                   Health Connect: latest OxygenSaturationRecord
    -- MessageClient "/spo2/config" -->   Spo2Config (red/IR slots, a, b, capture seconds)
    <-- MessageClient "/spo2/result" ---  Reading as JSON
phone PhoneListenerService -> ReadingStore + notification -> MainActivity
```

Modules:

- `shared` — message paths, the `Reading` model, Health Connect reader, Data Layer wrapper.
- `mobile` — phone app (Jetpack Compose, Material 3).
- `wear` — watch app (Compose for Wear OS, raw PPG capture, SpO2 estimator, Health Services).

## Acknowledgements

Original idea and icon from the Samsung version by Flyfish233. Icon by
[木子家的小团子](https://www.iconfont.cn/user/detail?uid=5049874&nid=fZ6DpMNcJqzs).
