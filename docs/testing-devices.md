# Testing across devices

Use a fresh emulator for each revision. The original `scripts/test-launcher-device.sh` deliberately forces a 480 × 854 display; `scripts/test-matrix.sh` uses the allocated profile's native size and density instead. It refuses physical phones, shared ports and any AVD whose name does not start with `honeycomb-matrix-`.

On a Linux machine with the Android SDK, build the revision first:

```sh
./gradlew :launcher:assembleDebug :launcher:assembleDebugAndroidTest
```

Use `avdmanager list device` and `sdkmanager --list` to select installed [system images and profiles](https://developer.android.com/studio/run/managing-avds). Cover small (480 × 854 at 220 dpi), compact (Pixel 5), Pixel 9, Pixel 9 Pro XL, Pixel Fold and Pixel Tablet; distribute Android 14–17 among them. Example for a **new** profile and a free port:

```sh
avdmanager create avd -n honeycomb-matrix-pixel9 -d pixel_9 -k 'system-images;android-36;google_apis;x86_64'
emulator -avd honeycomb-matrix-pixel9 -port 5604 -no-audio -no-snapshot
```

For a full sequential pass on Linux, pre-create the profiles, select gesture navigation and shut them down, then use `scripts/test-matrix.sh --profiles 5622 NEW_OUTPUT_DIR honeycomb-matrix-small honeycomb-matrix-compact honeycomb-matrix-pixel9 honeycomb-matrix-xl17 honeycomb-matrix-fold honeycomb-matrix-tablet`. This boots one private read-only instance at a time with two cores, 2048 MiB, no audio, and Lavapipe graphics. It records boot failures as incomplete and closes each instance before the next profile. Choose an unused even port; 5572, 5590 and 5592 are refused.

For an already running profile, select gesture navigation in Android Settings. Run with GNU `timeout`, `adb`, `sha256sum`, Python 3 and Pillow available. Supply a new output directory each time:

```sh
scripts/test-matrix.sh emulator-5604 "$HOME/matrix/$(git rev-parse HEAD)/pixel9"
```

For multiple already booted, dedicated profiles, `scripts/test-matrix.sh --batch NEW_OUTPUT_DIR emulator-5600 emulator-5604` runs them sequentially, collecting per-step outcomes into `results.json`. A refused profile remains incomplete; the batch returns failure if any suite did not pass. Keep at most two small emulator processes running on a shared host. Shut down each dedicated emulator after collecting its evidence.

`environment.txt` identifies the profile, actual dimensions, active navigation overlay, build fingerprint and APK hashes. `instrumentation.txt` records individual test outcomes. The existing `PhoneSizesTest` exercises the face, apps, Smart Stack and both centres and records images; `SetupLayoutTest` checks setup. Device-generated evidence is copied and JPEG previews are capped at 1600 px. Do not infer a real system gesture from `AppReturnTest`: it sends an explicit home intent.

### Gesture walk

The walk drives the real system gesture line from Android Settings rather than sending a home intent. It needs an
[agent-device](https://github.com/callstack/agent-device) executable bound to the emulator (it calls its `open`, `press`, `swipe`, `snapshot` and `screenshot`
commands) and the screen's pixel size in its current orientation. Start with setup finished and the default Stacked
face:

```sh
scripts/test-matrix.sh --walk "$BOUND_AGENT_DEVICE" WIDTH HEIGHT NEW_OUTPUT_DIR
```

To run the instrumentation suite on a remote SDK host first and the walk only after it finishes, use
`scripts/test-matrix.sh --suite-then-walk HOST /absolute/path/to/scripts/test-matrix.sh SERIAL /absolute/new/remote-output "$BOUND_AGENT_DEVICE" WIDTH HEIGHT NEW_LOCAL_OUTPUT`.
Don't touch the emulator while the suite runs: before starting instrumentation the runner stops any leftover
`uiautomator` process and refuses to start while another UI automation client is connected. The walk checks the
foreground screen after each action, saves JPEGs and writes `walk.json`. Review the JPEGs for visual faults; a missing
image is missing coverage.

## Physical devices

Use a dedicated Firebase project with billing turned off. Read the [current no-cost (Spark plan) limits](https://firebase.google.com/docs/test-lab/usage-quotas-pricing): four instrumentation runs and four Robo runs require eight physical executions; five fit in one day. Never switch to a billed project. Use `gcloud firebase test android models list --project="$PROJECT"` to choose supported model/version pairs. The runner verifies billing is disabled, looks up each physical model and Android version, and disables flaky retries. For example (use APKs from the intended SHA):

```sh
scripts/test-matrix.sh --firebase "$PROJECT" instrumentation "$APP_APK" "$TEST_APK" \
  e1q:36 cancunf:34 caiman:35 Frogger:36
scripts/test-matrix.sh --firebase "$PROJECT" robo "$APP_APK" e1q:36
```

These commands together consume the full five-execution physical quota if available. For the equivalent direct command, before **each** submission:

```sh
test "$(gcloud billing projects describe "$PROJECT" --format='value(billingEnabled)')" = False || exit 1
gcloud firebase test android run --project="$PROJECT" --type instrumentation \
  --app launcher/build/outputs/apk/debug/launcher-debug.apk \
  --test launcher/build/outputs/apk/androidTest/debug/launcher-debug-androidTest.apk \
  --device "model=$MODEL,version=$API" \
  --directories-to-pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence
```

For Robo, use `--type robo` with the same app and device and omit `--test`. Robo exploration and its screenshots do not prove it visited a particular destination; mark unvisited steps untested. Run main first, then rebuild and repeat for each PR head in `gh pr list --json number,headRefOid`; retain separate evidence per SHA.
