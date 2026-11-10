# Detent

A phone-as-iPod music player for Android: one black plane, Flow album carousel, click wheel.

## Building

- JDK 17, Android SDK 35 (`local.properties` needs `sdk.dir=...`).
- Spotify: put `spotify.clientId=<your Spotify app's Client ID>` in `local.properties`
  (git ignores that file). Add `app.detent://spotify-callback` as a Redirect URI in the Spotify dashboard.
- Spotify playback uses an unofficial player, librespot, bundled as `app/src/main/jniLibs/arm64-v8a/liblibrespot.so`
  (git ignores it). Build it with `~/src/detent-spike/build.sh` and copy the stripped binary there.
  Without it Detent still works for music on the phone and for browsing Spotify.
- `./gradlew :app:assembleDebug`
