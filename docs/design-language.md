# Honeycomb's design language: Native

Honeycomb keeps the watch's structure (the face, the Smart Stack, the honeycomb grid and the list, Notification Centre and Control Centre) and paints it in Android's own design system, Material 3 Expressive, the system Pixel uses. It follows however the owner has set up their phone: its colours, light or dark, font, font size, Bold text, animations and icons. Honeycomb's own values apply only where the phone gives nothing.

## Follows the phone

| Setting on the phone | Read with | Where in the code |
|---|---|---|
| Wallpaper colours (Material You) | `dynamicDarkColorScheme` / `dynamicLightColorScheme` (Android 12, API 31, and later) | `Native.wallpaper`, `HoneycombTheme` |
| No wallpaper scheme (Android 11 and earlier) | MaterialKolor `dynamicColorScheme` from the honey seed #FF9F0A, TonalSpot | `Native.honey` |
| Dark theme | `isSystemInDarkTheme()` (`Configuration.uiMode`) | `HoneycombTheme`, `Native.isDark` |
| Font (Samsung, OnePlus and Pixel font choices) | `FontFamily.Default`, the platform's default typeface | `NativeFont`, every `HoneycombType` style |
| Font size | `Configuration.fontScale`, applied to the layout density, so every `wsp` size follows it | `ProvideScale` |
| Bold text | `Configuration.fontWeightAdjustment` (API 31+), applied to every weight by Compose's Android font resolver | automatic for `FontFamily.Default` |
| Remove animations, or animator duration scale 0 | `Settings.Global.ANIMATOR_DURATION_SCALE` == 0, observed live | `Motion.snaps`, `Motion.rememberReduceMotion`, `Motion.orSnap` |
| Icon shape | `AdaptiveIconDrawable.getIconMask()` (the system's `config_icon_mask`) | `systemMask`, `circleIcon` |
| Themed icons switch (Wallpaper & style) | the home app's `com.android.launcher3.themedicon.option` provider, `icon_themed` / `boolean_value` | `IconTheme`, `IconThemeProvider` |
| Wallpaper colours on the watch faces and the breathing circle | the same scheme's roles, read by `FacePalette(scheme)` and `BreathPalette`, as Pixel's lock-screen clocks take the wallpaper's colours | `Palettes.kt` |

### Faces and artwork

The watch faces' default colours are the phone's. Each face reads roles from the scheme, never a value of its own, so it changes with the wallpaper and with light or dark. Containers carry the big colour fields and their on-container roles draw on them. Primary and tertiary are the two accents, and both read on the ground in light and dark.

| Face | Colours (roles) | Font |
|---|---|---|
| Stacked | hour `primary`, minutes `tertiary` | bundled Big Shoulders Display digits (`watch_digits`), shared by Stacked, Split and Hour: a condensed display face lets the digits fill a tall screen at one uniform scale. Digits it does not draw use the phone's font |
| Info | date `primary`, time `onSurface` | the phone's font, 600 and 500 |
| Split | field `primaryContainer`, rising field `tertiaryContainer`, digits `onPrimaryContainer` on the field and `onTertiaryContainer` where they cross into the rising colour | `watch_digits` |
| Hour | numeral `inversePrimary`, hands `onPrimaryContainer`, second hand `tertiary` | `watch_digits` |
| Classic | dial `secondaryContainer`, marks, numerals and hands `onSecondaryContainer`, second hand `tertiary` | the phone's font, 600 |
| Plain | field `tertiaryContainer`, hands `onTertiaryContainer`, second hand `primary` | none |

The face editor's colour row starts with **Phone colours**, the default, then Blue, Mint and Pink. A fixed pick is a seed (`FaceSeeds`), never painted directly: the face draws from a whole TonalSpot scheme generated from it in the phone's light or dark, so a pick works in both modes. A saved choice from before ("Original") reads as Phone colours.

The feed pause's breathing circle runs `tertiary` → `primary` → `primaryContainer` from the centre out.

`Palettes.kt` sits outside `design/`, so `DesignLanguageTest` scans it like any screen: a colour literal there fails the build. The only artwork literals left are the editor's three seeds in `design/HoneycombColors.kt`. `Display.face` still divides the font size back out, because a face is fitted to its frame.

## Scale

Everything is specified in **watch points (wp)**. One wp is the window's width ÷ 211, so a Honeycomb screen is the watch screen enlarged to the phone's width. `Float.wp`, `Int.wp` and `Float.wsp` (for text) are the only units screens use. Layout units are dp on a 360 dp wide phone, so Material's dp values (corners) apply directly.

## Colour

Every colour is a Material 3 colour role on the current scheme. `HoneycombTheme` provides the scheme to `MaterialTheme` and publishes it to `HoneycombColors`, which names the roles Honeycomb uses:

| Token | Role | Honey fallback, dark | Used for |
|---|---|---|---|
| `background` | black in dark mode, `surface` in light | #000000 | the grid, the list, Settings and setup |
| `faceGround` | black, always | #000000 | behind the face layer; the face itself sits on `background` |
| `sheet` | `surface` | #18120C | Notification Centre, Control Centre |
| `platter` | `surfaceContainerHigh` | #302921 | notifications, Smart Stack cards, rows |
| `pileSliver` | `surfaceContainer` | | the edge under a notification pile |
| `button`, `circle` | `surfaceContainerHighest` | | tiles, round controls and the status capsule at rest |
| `label` | `onSurface` | | text and glyphs |
| `secondaryLabel` | `onSurfaceVariant` | | times, dates, subtitles |
| `accent` / `onAccent` | `primary` / `onPrimary` | #FFB868 / #4A2800 | a toggle that is on, progress, the primary action |
| `tonal` / `onTonal` | `secondaryContainer` / `onSecondaryContainer` | | icon discs, tonal buttons, themed icons |
| `error`, `destructive` | `error`, `errorContainer` | | low battery, remove buttons |
| `outline` | `outline` | | a switch's thumb when off |
| `sheetDim` | `scrim` at 60% | | dimming under a sheet |
| `pressed` | `onSurface` at 10% | | M3's pressed state layer |

The glyph or text on any fill is `HoneycombColors.contentFor(fill)`, Material's own pairing (primary to onPrimary and so on). There is one accent. Apple's system tints (blue, indigo, green and the rest) are gone: Wi-Fi, Bluetooth, Quiet and every other tile are the same `primary` when on.

The approved sheet's primary is #FABA73 (material-color-utilities in the browser); MaterialKolor 2.1.1 gives #FFB868 for the same seed and style, and #302921 for the card, an exact match. `FollowsThePhoneTest` pins both within 3% and 7%.

## Type

Every UI style is the phone's own font (`FontFamily.Default`) at a Material 3 role's weight and tracking, sized in wsp so the watch structure keeps its proportions.

| Style | M3 role | Weight | Size / line height (wsp) |
|---|---|---|---|
| `largeTitle` | headlineSmall | Regular, onSurface | 20 / 24 |
| `title` | titleLarge | Regular | 17 / 19.5 |
| `headline` | titleMedium | Medium | 15 / 17.5 |
| `body` | bodyLarge | Regular | 14.5 / 17 |
| `caption` | bodyMedium | Regular | 14 / 16.5 |
| `footnote` | labelMedium | Medium | 11.5 / 14 |
| `smallControl` | labelLarge | Medium | 11.5 / 14 |
| `time` | titleMedium, tabular | Medium | 17 / 19.5 |

Material components get `HoneycombType.material`, M3's default `Typography`, which is also the platform font.

## Shapes and components

Corners are Material 3 Expressive's scale (`NativeShapes`): extraSmall 4, small 8, medium 12, large 16, largeIncreased 20, extraLarge 28, and full (capsules and circles). Surfaces are flat and tonal: no rims, no gloss, no glass.

| Component | Is | Used in |
|---|---|---|
| `Platter` | a `platter` card, extraLarge corners, 12 wp padding | notifications, Smart Stack cards, Settings rows, list rows |
| `WatchButton` | an 86 × 58 wp tile, largeIncreased corners; `button` when off, `primary` when on, glyph in the matching on-colour | Control Centre |
| `CircleButton` | a `circle` disc, 36 wp; a `tint` fills it (remove in `destructive`) | back, ×, bell, previous, play and next |
| `CapsuleButton` | a `tonal` capsule, 40 wp tall; `filled` makes it `primary` | Edit, Clear, Show less; setup's main action is filled |
| `Row` | a `Platter` with a 28 wp `tonal` icon disc and a `body` label | Settings, Stack editor, feeds, widget picker |
| `StatusLine` | the time in `time`; battery in a `button` capsule, `error` at 20% or less | every sheet |

## Icons

Each app's icon is drawn as the phone draws it: an adaptive icon's own layers, uncropped, cut with the system's icon mask, so a device's icon shape applies. A legacy icon is scaled to fill the same mask. With the phone's Themed icons switch on, an icon that ships a monochrome layer is that layer in `onTonal` on `tonal`; one without keeps its colours.

The switch lives in the home app, not in Settings. Wallpaper & style (AOSP ThemePicker, `ThemedIconUtils.java`) resolves the home activity's `com.android.launcher3.themedicon.option` meta-data to a content provider and reads and writes `icon_themed` with column `boolean_value`. Honeycomb declares that meta-data and `IconThemeProvider` (authority `<package>.grid_control`), readable only by a caller holding `BIND_WALLPAPER`, the same contract as Launcher3, Lawnchair and Neo Launcher. It is off until the user turns it on.

## Layouts kept from the watch

| Screen | Layout |
|---|---|
| Control Centre | two columns of `WatchButton` tiles and the battery capsule centred at the top, on `sheet` |
| Notification Centre | one `Platter` per notification, the app icon overlapping its top-left corner, the time top-right in `secondaryLabel`, on `sheet` |
| Smart Stack | `Platter` cards (a widget's card wraps the widget), then the Edit `CapsuleButton`; no date or clock row |
| Now Playing | the artwork, the title, and three `CircleButton`s |
| Settings-style lists and setup | a `largeTitle`, then `Row`s with 8 wp gaps; setup's main action filled, Skip tonal |

## Gestures

The face's gestures are watchOS 27's (watchos-sim/screens/04, 10, 11). One table in `Space.kt` holds them all.

| On the face | Opens | Where watchOS does the same |
|---|---|---|
| Swipe up from the middle | The Smart Stack, rising from below (16 dp or a quick flick is enough); swipe down from its first card, or Back, lowers it | the Smart Stack (Digital Crown up or swipe up) |
| Tap | The dynamic grid: seven apps over the blurred face, the most used in the centre and the rest outward. The seventh sits where watchOS puts its all-apps circle: a phone reaches all apps by swiping, so that circle would be a wasted slot | the dynamic grid (Crown press) |
| On the dynamic grid: a swipe left or up | The full grid, apps by use, zooming in from the centre. A screen reader reaches it by the cluster's "All apps" action | the full grid (its all-apps button, or scrolling); Pixel's swipe up to all apps; the iPhone's App Library |
| Swipe left, anywhere | In a level: the face to the dynamic grid, the dynamic grid to the full grid. The levels zoom under the finger (half the screen's width is a whole level) and settle on the `snappy` spring when let go; a short or slow swipe springs back | the iPhone's App Library, reached by swiping left past the last Home Screen page; watchOS 10.2's optional "Swipe to Switch Watch Face" |
| Swipe right, anywhere | Out a level: the full grid to the dynamic grid, the dynamic grid to the face, following the finger the same way | the same sideways swipe, back |
| Swipe left from the right edge | On the face and the dynamic grid, the middle 200 dp of the right edge is kept from Android's Back (Android's per-edge limit), so the swipe goes in a level; Back works from the whole left edge, the rest of the right edge, on the full grid and in every app | |
| Touch and hold | The face picker | the face gallery |
| Swipe down from the top edge | Notification Centre, or Control Centre from the right third | iPhone |
| Back | Up one level: full grid, dynamic grid, face | the Crown press |
| Swipe up from the navigation line | On the bare face, the dynamic grid; anywhere else, or from an app, back to the face. Android keeps this edge (a home app cannot take it without root), so it arrives as Home | the Crown press ("Press to see the watch face or Home Screen") |

The dynamic grid's apps are ranked by how often each was opened over the last week (`UsageStatsManager` events); without usage access, by how often Honeycomb opened them; with neither, alphabetically. A slot the owner moved or picked by touch and hold stays put; the others follow use.

## Motion

The motion in `Motion` stays as recorded. With Remove animations on (animator duration scale 0) every motion snaps: Compose's own animations follow the scale, and Honeycomb's hand-run ones read `HoneycombTheme.reduceMotion` or `Motion.orSnap`. No screen reads the setting itself.

## Enforcement

`DesignLanguageTest` fails the build if any of these appear outside `design/`:

- `Color(0x` or `Color(` with RGB numbers
- `Color.White` or `Color.Black`
- `RoundedCornerShape(`
- `TextStyle(`
- `fontSize =`
- alpha copies of White or Black
- a literal `tween(`

`FollowsThePhoneTest` pins the scheme order, light and dark, the phone's font for Bold text, and snapping at animator scale 0.
