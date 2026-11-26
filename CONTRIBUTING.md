# Contributing to Honeycomb

Honeycomb is a watchOS-inspired home screen for Android. It is an ordinary Android app: it must work without root,
an unlocked bootloader or any outside service.

## Start here

1. Follow the [README quick start](README.md#build-from-source).
2. Read [the roadmap](ROADMAP.md), [the design language](docs/design-language.md), [research areas](docs/research.md)
   and [how to verify a change](docs/verification.md).
3. Keep proposals small. Explain what a person can do differently, what already works and what is still a guess.
   Bring primary-source evidence before proposing a new ranking policy.

## Code and builds

- One module, `launcher`, package `org.honeycomb.launcher`. Prefer names that describe Android roles, such as
  `LauncherActivity`.
- `app` is the build people install; `debug` builds are for tests. Keep platform-only APIs out of `app`.
- `scripts/check-names.sh` keeps names from before the project was called Honeycomb out of the tree; CI runs it.

Use JDK 17 and the SDK versions in the README. Run the unit tests, build the app, and run the instrumentation tests on
the `honeycomb-a16` emulator (API 36); the test script resets app data only on that emulator. A test should fail for
broken behaviour, not for a renamed file or a changed string. New permission, widget and lifecycle wiring needs tests
that observe the result.

## Privacy and safety

Never commit credentials, `.env`, `local.properties`, signing keys, APKs, database dumps or personal screenshots.
Capture screenshots on a fresh emulator with made-up data, look at every image, and strip metadata before adding it to
`docs/images/`.

Honeycomb shows no notification dots or badges by default. Don't quietly broaden permissions or override someone's
choice to turn off notification or usage access.

CI runs a secret scan on every pull request. In the description, include build and test results, where screenshots
came from, permission changes and known limits. Keep third-party licences and attribution.

## Releases

Releases are built and signed by the `release-app` workflow from a version tag; see [releasing](docs/release.md).
Pull-request CI never makes a release.
