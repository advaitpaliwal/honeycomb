# Set up Honeycomb over USB

This makes Honeycomb the default Home app for the phone’s primary user. It leaves the stock launcher enabled so Android can continue to handle the bottom gesture and recent apps. It is not a kiosk: you can still open Settings and change Home. No root, bootloader unlock or device-owner enrollment is involved.

Use an Android 14 or newer phone, signed in as its primary user, without an existing Honeycomb installation. The script refuses to overwrite existing app data. Install [Android Platform Tools](https://developer.android.com/tools/adb) and [SDK Build Tools](https://developer.android.com/tools/releases/build-tools) on the computer. Put `adb` and `aapt` on PATH, or set `AAPT` to the full path to `aapt`. Enable USB debugging, connect the phone, unlock it, and approve this computer when Android asks.

```sh
adb devices -l
scripts/setup-phone.sh --serial SERIAL /path/to/honeycomb.apk
```

Replace `SERIAL` with the identifier shown for your phone. The APK must be a trusted build of `org.honeycomb.launcher`. The script saves the original Home holder and navigation state on this computer before installing anything. It requests the [Home role](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME) and verifies the holder. It requests usage access for Honeycomb’s app grid and notification access (including notification contents) for media controls. It checks that gesture navigation is active. Complete Honeycomb’s on-screen onboarding. Other phones may restrict these grants or lack the standard gesture overlay.

## Undo

```sh
scripts/setup-phone.sh --serial SERIAL --undo
```

Run this on the same computer, with the same user account. Undo restores the previous Home holder, reverses the gesture overlay change, and removes Honeycomb and its local app data. Other apps remain installed. Keep the record under `${XDG_STATE_HOME:-$HOME/.local/state}/honeycomb` until undo completes. If setup is interrupted, reconnect the same phone and use `--undo` before retrying.
