# Architecture

Knurl is a single-activity Jetpack Compose app. One long-lived object, `KnurlController`, owns all navigation and
playback state; the UI is a pure function of that state, and the click wheel is the only input.

Stack: Kotlin 2.0, Jetpack Compose (BOM 2024.12), AndroidX Media3 1.5 (ExoPlayer + MediaSession), Spotify App Remote
0.8.0, Android Gradle Plugin 8.7, Gradle 8.11. `minSdk` 29, `targetSdk` 35.

## Layers

```
 click wheel / buttons
        │  tick(±1), button(), toggleShuffle(), toggleLike() ...
        ▼
 KnurlController  ──────────────►  Compose UI (KnurlApp, screens)
   │   │   │                          reads snapshot state, draws
   │   │   └── SpotifyRemote   App Remote: play / pause / skip / seek / shuffle / repeat + live player state
   │   └────── SpotifyApi      Web API: library, playlists, artists, queue, like state   (SpotifyAuth: PKCE tokens)
   └────────── PlayerController  Media3 MediaController → PlaybackService (music on the phone)
```

`KnurlApplication` creates the controller once, so it survives rotation and activity recreation. It is not
persisted across process death; `restoreSession()` rebuilds the one thing that matters (the Spotify connection).

## Navigation model

`KnurlController` keeps a stack of `Entry(screen, selected)`. Select pushes, Menu pops, like the iPod. `Screen` is a
sealed interface (main menu, Flow, Albums, Spotify home, Queue, Now Playing, …).

A screen is drawn one of three ways:

- **List screens** (`ListScreen`) – built from a `ListModel` (title, row count, title/subtitle lambdas, optional cover
  header, track numbers, values, check marks). `listModel(screen)` returns it.
- **Flow screens** (`AlbumFlow`) – a carousel of `Album` tiles. `isFlowScreen`, `flowTiles`, `flowEmptyText` and
  `flowTitle` describe them. The local Flow, Spotify home (category tiles with a symbol and colour), playlists,
  followed artists, recently played and an artist's albums all use this, so a new carousel is a few lines.
- **Now Playing** – custom layout (`NowPlayingScreen`).

`rowCount(screen)` is the selectable length for either kind, and `tick()` moves `selected`. Menu screens that have
something playing are wrapped in `MenuWithMiniPlayer`, which centres the mini-player card in the free space between
the last row and the controls (a long list pushes it to the bottom).

## The click wheel

`ClickWheel` is a Canvas drawn in the design's 328-unit space and hit-tested as one circle. Touch handling tracks the
angle with `atan2` around the centre and accumulates it; every 16° of travel emits a tick (+1 clockwise, −1 counter-
clockwise), so speed doesn't depend on where on the ring you touch. A touch that moves less than the platform touch
slop is a tap: the centre disc is Select, and the ring is split into four 90° quadrants (Menu / Next / Play-Pause /
Prev). Haptics (`Haptics`) use `Vibrator` primitives for the tick and a heavier click for presses.

The four corner buttons (`ModeButton`) are separate composables placed around the wheel in `KnurlApp`; they share a
glossy matte look, glow when on, and draw their symbols from SVG path strings.

## Playback sources

`KnurlController.source` (`None`, `Local`, `Spotify`) decides which backend the shared controls talk to. It is
Compose snapshot state, so menus and the mini player redraw when it changes. `nowPlayingInfo()` produces one
`NowPlayingInfo` for either source.

**Local** – `PlayerController` wraps a Media3 `MediaController` bound to `PlaybackService` (a `MediaSessionService`,
so playback continues in the background with a notification). Library data comes from `MediaStore`; album art from
`loadThumbnail`. Shuffle, repeat, seek, volume (`STREAM_MUSIC`, one step per tick) and the upcoming queue come from
the player.

**Spotify** – see [spotify-setup.md](spotify-setup.md) for registration. Two clients cooperate:

- `SpotifyRemote` wraps the App Remote. It connects to the installed Spotify app, subscribes to player state
  (track, position, paused, shuffle, repeat, cover image) and sends commands. Position is extrapolated from the time
  a report arrived so the progress bar moves smoothly. Connecting needs a visible Activity (Spotify may show an
  "Allow" screen the first time), which `KnurlApplication` tracks.
- `SpotifyApi` calls the Web API for what the remote can't: browsing the library, loading queues, checking and
  setting liked state. Lists are capped (500 items) and handled with the same `SpotifyList(items, loading, error)` type
  so every screen can show "Loading…" or a short error. Endpoint renames are handled by trying the new path first and
  falling back (`/me/library` → `/me/tracks`, playlist `tracks` → `items`).

Behaviours worth knowing:

- Starting a playlist makes Spotify forget shuffle and repeat, so Knurl sets them again after playback starts.
- Spotify has no "play this queue item" call. Selecting a queued song skips forward one song at a time.
- Seeks from the wheel come in bursts, so only the last position is sent (debounced 300 ms).
- Closing Knurl leaves Spotify playing. `lastSource` is saved, and `MainActivity.onResume()` calls
  `restoreSession()` which quietly reconnects if Spotify was last.

## Persistence and privacy

- `knurl` SharedPreferences: shuffle, repeat, last source, local favourites.
- `spotify` SharedPreferences (private, `allowBackup` is off): access token, refresh token, expiry and the scopes
  that were granted. If the requested scopes change, the saved sign-in counts as signed out so the person signs in
  again with the new permissions.
- Sign-in is Authorization Code with PKCE; there is no client secret in the app or the repository. Knurl has no
  analytics and talks only to Spotify and (for art) Spotify's image host.

## Adding things

- **A new Spotify carousel** – add a `Screen`, load its list with `load(state) { spotifyApi.something() }` in
  `select()`, and return tiles from `flowTiles`.
- **A new source** (Subsonic, Audius …) – add a `Source` case, put its commands behind the same controller functions
  that `togglePlayPause`, `skip`, `seekBy` and `nowPlayingInfo` already branch on, and a row in `sourceItems`.
- **A corner button** – add an icon to `ModeIcon` (path strings in a 72-unit square) and place a `ModeButton` in
  `KnurlApp`.
