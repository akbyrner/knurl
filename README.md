# Detent

A phone-as-iPod music player for Android: one black plane, Flow album carousel, click wheel.

## Building

- JDK 17, Android SDK 35 (`local.properties` needs `sdk.dir=...`).
- Spotify: put `spotify.clientId=<your Spotify app's Client ID>` in `local.properties`
  (git ignores that file). Add `app.detent://spotify-callback` as a Redirect URI in the Spotify dashboard.
- Spotify playback is remote control of the official Spotify app (Spotify's App Remote library, in `app/libs/`, from
  github.com/spotify/android-sdk). In the Spotify dashboard, add Detent's package name `app.detent` with its signing
  fingerprint (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`) and the
  Redirect URI `app.detent://spotify-remote`.
- `./gradlew :app:assembleDebug`
