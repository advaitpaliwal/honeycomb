# AGENTS.md — Honeycomb Launcher

## Non-Negotiable Engineering Rules

1. **Research First, Library First — NEVER Hand-Roll Solved Primitives:**
   - Before implementing any UI component, drag-and-drop, gesture, animation, keyboard, icon set, pager, sheet, slider, switch, or menu, ALWAYS search Maven Central / Google Maven / GitHub first for the latest official AndroidX / Jetpack Compose / Material 3 primitive or battle-tested production open-source library (such as `sh.calvin.reorderable:reorderable` for grid/list reordering).
   - Keep `androidx.compose:compose-bom` current and actually USE native Material 3 components (`Switch`, `Slider`, `ModalBottomSheet`, `DropdownMenu`, `SingleChoiceSegmentedButtonRow`, `HorizontalPager`, `LazyVerticalGrid` + `animateItem()`).
   - Never write custom raw `pointerInput` / `Canvas` re-implementations of standard controls or drag-reorder physics.
   - Whenever replacing a subsystem, delete all dead files, tests, and manifest entries in the same change.

2. **Show Visuals Before Shipping UI Changes:**
   - Preview/show UI mockups or changes before shipping major layout/UX shifts. Keep Honeycomb radically simple—no unrequested UI bloat.

3. **Design Language & Quality Gates:**
   - Respect `DesignLanguageTest.kt`: outside `design/`, never use raw `Color(...)`, `Color.White`/`Color.Black`, `RoundedCornerShape(...)`, `TextStyle(...)`, `fontSize =`, or `tween(<digit>)`.
   - Before releasing, always pass `./gradlew :launcher:testDebugUnitTest :launcher:lintVitalApp :launcher:assembleApp` with `ANDROID_HOME` set.
   - Install signed GitHub Release APKs directly onto Advait's connected Pixel 9a over ADB after verifying SHA-256; never take screenshots of his physical phone.
