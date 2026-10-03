# Plex setup

Open the music chooser by tapping the J-card title, then choose **Connect to Plex**.
Sign in in your browser and return to PHONY. Choose a server, then one of its music
libraries. The saved library appears in the music chooser; tap it to change the
selection or sign out locally. Closing an unfinished sign-in cancels it.

After selecting a library, choose **OPEN TAPE BOX**, or **Browse Plex albums** in
the music chooser. Tap an album spine, then its case to load and play the tape.
Use **MORE ALBUMS** to load the next page, or **REFRESH** to reload the collection.
The **PLEX / SPOTIFY** buttons select which collection fills the box; switching
the box itself does not interrupt the current music.

Plex audio streams directly through PHONY, including background, lock-screen, and
Bluetooth controls. Albums have their own artwork and tracklists. Side A pauses
at its last track in the native player, including with the screen off; press Stop
to flip and Play for Side B. The digital shell uses auto-reverse. Reopening the
Activity restores an existing native queue; a full process restart requires
loading an album again.

This version supports albums and direct playback of formats the device's Media3
player can decode. Plex playlists, server-side transcoding, offline downloads,
Plex listening-history updates, and mixtape recording are not included yet.
An unavailable track fails album loading rather than silently omitting music.
J-cards use Plex's track metadata; lyrics still use the existing lyrics provider.
No Plex developer application, pasted token, or Plex app is required.

PHONY tries secure addresses advertised by Plex, in local, remote, then relay
order. The server must be signed in to Plex and reachable from the phone with
secure connections enabled. HTTP-only servers and manual addresses are not yet
supported. Discovery includes accessible shared servers; Plex Home profile
switching is not included. Only music libraries (`type=artist`) are offered.

## Implementation

- [PlexConnection](../app/src/main/java/com/hughhowey/phony/plex/PlexConnection.kt)
  owns asynchronous sign-in and selection state. Pending sign-ins survive activity
  recreation; cancellation and sign-out invalidate in-flight results.
- [PlexApi](../app/src/main/java/com/hughhowey/phony/plex/PlexApi.kt) handles the
  [documented PIN flow](https://forums.plex.tv/t/authenticating-with-plex/609370),
  resource discovery, and music sections. Requests have timeouts and do not follow
  redirects with credentials. Each server uses its own resource access token.
- [PlexStore](../app/src/main/java/com/hughhowey/phony/plex/PlexStore.kt) encrypts
  session data with Android Keystore AES-GCM in separate app-private preferences.
  The app already disables backup. Tokens and server addresses never enter JS.
- [plex.js](../app/src/main/assets/js/plex.js) renders the setup card using existing
  UI primitives. Only small bridge, script-loading, and chooser hooks touch the
  original app. Selecting Plex does not change the current playback source.
- [PlexCatalog](../app/src/main/java/com/hughhowey/phony/plex/PlexCatalog.kt) pages
  albums and resolves current server connections. Cover images are cached on the
  phone and served through the WebView's local origin without exposing tokens.
- [PlexPlayback](../app/src/main/java/com/hughhowey/phony/plex/PlexPlayback.kt) owns
  the current native queue and side boundaries inside the media service. Synthetic
  media URIs keep server addresses and tokens out of the media session and JS.
- [PlexAudioStream](../app/src/main/java/com/hughhowey/phony/plex/PlexAudioStream.kt)
  sends authenticated byte-range requests, rejects redirects, and detects truncated
  responses. Selecting a different library or signing out clears Plex playback.

## Validation

Run `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` on Windows with your
Android Studio JDK and Android SDK 35. The unit tests cover resource filtering,
server-specific tokens, connection ordering, music filtering, auth URL encoding,
pagination, disc/track ordering, side-boundary policy, and actual HTTP range,
redirect, and end-of-file behavior against a local test server.

The browser contract test in [tests/plex-setup.cjs](../tests/plex-setup.cjs) uses a
simulated native bridge. Install Playwright in an ignored directory with
`npm install --prefix build/plex-validation playwright --no-audit --no-fund`, set
`NODE_PATH` to that directory's `node_modules`, and run `node tests/plex-setup.cjs`.
Set `PHONY_TEST_BROWSER` to an installed Chromium browser executable, or install
Playwright's Chromium. The test exercises setup at phone and unfolded sizes,
selection restoration, cancellation, empty/error states, long lists, safe text
rendering, and isolation from Spotify/playback. It does not authenticate to Plex.
Run `node tests/plex-playback.cjs` with the same environment for album pagination,
case loading, transport commands, Side A/B, queue restoration, repeated song
titles, error display, auto-reverse selection, and switching away from Plex.

On an Android device, check:

1. Sign in, return from the browser, choose a server and music library, then restart.
   The library remains selected without exposing a token in the UI.
2. Close/cancel sign-in, including while the initial request is pending; a late
   response must not reopen the browser or sign the user in.
3. Let a sign-in expire; retry starts a new sign-in. Recreate the activity while
   browser sign-in is pending; returning to the app still checks the saved PIN.
4. Test an unavailable server, an account with no servers, a server with no music,
   a shared music library, and a revoked account authorization.
5. Sign out during discovery; no late response restores the old account or library.
6. Confirm Spotify setup and local music playback still work, and setup fits both
   narrow and unfolded screens.
7. Load an album, seek within a track, skip tracks, and lock the phone. Verify music
   continues and lock-screen/Bluetooth controls work. Let Side A finish while the
   screen is off, reopen, flip, and play Side B. Repeat with the digital shell.
8. Test your actual audio formats (including FLAC and MP3), a multi-disc album, a
   library with more than 50 albums, network loss/retry, and changing networks.
   If an existing stream cannot reconnect after changing networks, reload its
   album from the box to discover the server's current connection addresses.
