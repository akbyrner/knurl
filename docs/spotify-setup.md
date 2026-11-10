# Spotify setup

Detent never plays Spotify audio itself. It controls the official Spotify app on the same phone through Spotify's
App Remote, and reads your library through the Web API. You need a Spotify Premium account, the Spotify app signed in
on the phone, and a free Spotify developer app of your own.

## 1. Create a developer app

1. Open the [Spotify developer dashboard](https://developer.spotify.com/dashboard) and create an app.
2. Under **APIs used**, tick **Web API** and **Android**.
3. Copy the **Client ID** into `local.properties` (git ignores that file):

   ```
   spotify.clientId=your_client_id_here
   ```

   Detent uses PKCE, so the **client secret is not needed**. Never put it in the app or the repository.

## 2. Redirect URIs

Add both, exactly as written:

| URI | Used for |
| --- | --- |
| `app.detent://spotify-callback` | The browser sign-in (Web API access) |
| `app.detent://spotify-remote` | The App Remote connection to the Spotify app |

## 3. Android package and fingerprint

Under the app's **Android packages**, add:

- Package name: `app.detent`
- SHA-1 fingerprint of the key that signs your build. For debug builds:

  ```sh
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
  ```

If you later sign release builds with a different key, add that key's fingerprint as well.

**Save** the app in the dashboard. A missing or unsaved entry shows up in Detent as "Spotify hasn't approved Detent
yet" or "Spotify doesn't recognise Detent".

## 4. User management

A development-mode app only works for accounts you add under **User management** (the owner is added automatically),
up to five people. That is plenty for a personal app.

## Permissions Detent asks for

| Scope | Why |
| --- | --- |
| `user-library-read` | Liked Songs, saved albums, and whether the current song is liked |
| `user-library-modify` | The heart button (like / unlike) |
| `playlist-read-private`, `playlist-read-collaborative` | Your playlists and their tracks |
| `user-follow-read` | Artists you follow |
| `user-read-recently-played` | The Recently Played carousel |
| `user-read-playback-state` | Reading the queue (Up Next) |
| `user-modify-playback-state` | Playback control through the Web API |
| `app-remote-control` | Controlling the Spotify app through App Remote |

When the list changes, the saved sign-in is treated as signed out and Detent asks you to sign in once more. Spotify
shows the permission page in your browser; approve it and you are returned to Detent.

## First run

1. Sources → Spotify → **Sign in with Spotify**, approve the page that opens.
2. Pick a playlist, album or song. The first time, the Spotify app may open an "Allow Detent" screen; tap Allow.
3. Playback runs in the Spotify app; Detent mirrors it.

## Troubleshooting

| Message | Cause |
| --- | --- |
| Spotify isn't set up | No `spotify.clientId` in `local.properties` when the app was built |
| The Spotify app isn't installed | Install and sign in to the Spotify app on the phone |
| Open the Spotify app and sign in | Spotify is installed but signed out |
| Spotify hasn't approved Detent yet | The package, fingerprint or redirect URI is missing or unsaved in the dashboard |
| redirect_uri: Not matching configuration | The sign-in redirect URI is missing from the dashboard |
| HTTP 403 on like / queue | Signed in before a permission was added; sign out in Settings and in again |

## Notes and limits

- Spotify does not allow Detent to jump to an arbitrary song inside its queue, so queued songs are reached by
  skipping ahead.
- Spotify forgets shuffle and repeat when a new playlist starts; Detent sets them again afterwards.
- Spotify's Web API has been renaming endpoints; Detent tries the newer paths first and falls back to the older ones.
- Why not stream inside Detent? Spotify's terms forbid recording or re-streaming its audio, and the open-source
  Spotify client libraries no longer get audio keys from Spotify, so remote control of the official app is the
  supported way.
