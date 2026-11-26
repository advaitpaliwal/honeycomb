# Verification

How to check a change before you open a pull request.

## Build and unit tests

```sh
./gradlew :launcher:assembleApp :launcher:assembleDebug :launcher:assembleDebugAndroidTest :launcher:testDebugUnitTest
bash scripts/check-names.sh
```

## On an emulator

```sh
ANDROID_SERIAL=emulator-5554 bash scripts/test-launcher-device.sh
```

The script runs every instrumentation test against the real `LauncherActivity`. It works only on an emulator named
`honeycomb-a16` (API 36), because it clears Honeycomb's app data and resizes the display to 480 × 854 at 220 dpi
first. It refuses physical phones. No accounts or keys are needed.

A few of the suites, as a map of what is covered:

- `HomeFeaturesTest`: Honeycomb is a home activity and holds no assistant role or privileged permission.
- `HomeTest`: the dynamic grid's seven apps by use with no all-apps circle, its "All apps" accessibility action opening the full grid, and dragging apps to reorder them across a restart.
- `BackHomeTest`, `AppReturnTest`, `HomeOnFaceTest`: Back stays on the face, Home from an app returns to it, and Home on the bare face opens the dynamic grid and back.
- `ControlCentreDeviceTest`: the swipes that open Control Centre, Notification Centre, the Smart Stack and the grids.
- `LiveUpdateTest`: posts a timer-style Live Update and checks Honeycomb reads it as live.
- `PhoneSizesTest`, `CutoutClearanceTest`, `TouchTargetTest`: every screen fits the phone, nothing sits under the camera cutout, and every control is large enough to tap.

To cover more screen sizes and Android versions, see [testing across devices](testing-devices.md).

## Screenshots and privacy

The ten README screenshots in `docs/images/` were captured on a fresh Pixel 9a-size (1080 x 2424) Android 16 emulator at commit
a16ee33, with no accounts and no personal data. Each one uses its own generated gradient wallpaper, so each shows a different set of
phone colours. The timer is a real Live Update from Clock You, the music is Auxio playing a public-domain recording of Satie's
Gymnopédie No. 1, the calendar holds made-up events, and the message comes from a 555 test number. `dynamic-grid.png` was recaptured the same way at commit ed14deb, when the cluster went to seven apps, with the apps ranked by Honeycomb's own launch counts. Never commit screenshots from a personal phone.
