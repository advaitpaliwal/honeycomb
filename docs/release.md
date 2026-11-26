# Releasing Honeycomb for Android

## Signing certificate

Every release APK is signed with one key. Its certificate's SHA-256 (DER, hexadecimal) is:

```
341395e89944cae9ede496c6fe9df0aa5b1c23aaa9623776d16f4dfe7c0c3715
```

To check a downloaded release, put the APK and its `.sha256` file side by side and run:

```sh
shasum -a 256 -c honeycomb-app.apk.sha256
apksigner verify --print-certs honeycomb-app.apk   # from the Android SDK build tools
```

The certificate SHA-256 it prints must match the value above. The checksum only proves the download is complete; the
certificate proves who signed it. Android installs an update only when it is signed with the same key, so a build from
any other key (including your own debug builds) cannot update a release install in place.

## Cutting a release (maintainers)

1. Review the exact commit on `main` to release, especially build scripts and workflows.
2. Tag it: `git tag -a v0.1.0 <commit> -m 'Honeycomb 0.1.0'` and `git push origin refs/tags/v0.1.0`. The tag sets
   `versionName` (without the `v`); the workflow run number sets `versionCode`, which must exceed every code already
   distributed.
3. The `release-app` workflow builds `:launcher:assembleApp`, verifies the APK's signature against the certificate
   above, and attaches `honeycomb-app.apk` and `honeycomb-app.apk.sha256` to a **draft** GitHub Release. A failed
   build or a missing secret creates no release. The signing key reaches the workflow only through the protected
   `release` environment's secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`); never put them in the repository.
4. Install the draft's APK fresh and as an update over the previous release, then publish the draft.

Builds of `assembleApp` without `ANDROID_KEYSTORE_PATH` are signed with the local debug key and are not releases.

A Google Play release needs an Android App Bundle (`:launcher:bundleApp`) and Play App Signing. Play's signing key
need not match the key above, so an install from Play and an install from GitHub cannot update each other.

[Android app signing](https://developer.android.com/studio/publish/app-signing) ·
[GitHub environment secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)

## Privacy

Honeycomb has no accounts and collects or shares no data. It does not request internet, location or calendar access,
and its settings are excluded from Android cloud backup. On the phone it reads:

- installed apps and app usage, to fill and order the grid;
- media titles and artists, for Now Playing, and notifications and Live Updates, for Notification Centre and the Smart
  Stack, when you grant notification access;
- the widgets you add, and Control Centre settings;
- with the optional feed control, the foreground app's package, view IDs and tab labels, to act on the phone only.

See the [permissions](../launcher/src/main/AndroidManifest.xml), [notification access](../launcher/src/main/java/org/honeycomb/launcher/FaceData.kt)
and [feed control](../launcher/src/main/java/org/honeycomb/launcher/FeedGuard.kt). Android's Clear storage removes
every Honeycomb setting. Apps you open from Honeycomb and the widgets you add run on their own and may use the network.
