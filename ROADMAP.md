# Honeycomb roadmap

What is planned, not what has shipped. Honeycomb must stay useful on stock Android with no outside services.

## Today

A watch-style face with a Smart Stack (Live Updates, Now Playing, and borderless Android widgets you add), swipe-down
instant search from the face, the 7-app dynamic grid, the full app honeycomb grid and A–Z list view with scrubber,
Notification Centre, Control Centre, and built-in offline **Block** (per-app feed/short-form blocking for Reels,
Explore, Shorts, Spotlight + zero-network website blocking with categories, custom domains, 24h strict mode, and a
Quick Settings tile). Recent apps are Android's own recent-apps screen (swipe up from the bottom and hold). No
notification dots or badges by default. Signed APKs ship on GitHub Releases (and auto-update via Obtainium).

## Next

- **Distribution:** publish a Google Play listing ([release procedure](docs/release.md)) and F-Droid / IzzyOnDroid metadata.
- **More phones:** run the [cross-device matrix](docs/testing-devices.md) on physical phones (Samsung, OnePlus, Nothing, Pixel) and document what each phone maker's permissions allow.
- **Ranking research:** study how Apple's Smart Stack decides what to show before changing Honeycomb's order. See
  [research questions and sources](docs/research.md). Prototypes are not described as features until they are verified.
