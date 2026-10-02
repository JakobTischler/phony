# Plex setup

Open the music chooser by tapping the J-card title, then choose **Connect to Plex**.
Sign in in your browser and return to PHONY. Choose a server, then one of its music
libraries. The saved library appears in the music chooser; tap it to change the
selection or sign out locally. Closing an unfinished sign-in cancels it.

This first integration step saves the connection only. Plex album browsing and
audio playback are not implemented yet. Existing Spotify and local playback are
unchanged. No Plex developer application, pasted token, or Plex app is required.

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

## Validation

Run `gradle :app:testDebugUnitTest :app:assembleDebug` with JDK 17 or 21,
Gradle 8.10.2, and Android SDK 35. The unit tests cover resource filtering,
server-specific tokens, connection ordering, music filtering, and auth URL encoding.

The browser contract test in [tests/plex-setup.cjs](../tests/plex-setup.cjs) uses a
simulated native bridge. Install Playwright in an ignored directory with
`npm install --prefix build/plex-validation playwright --no-audit --no-fund`, set
`NODE_PATH` to that directory's `node_modules`, and run `node tests/plex-setup.cjs`.
Set `PHONY_TEST_BROWSER` to an installed Chromium browser executable, or install
Playwright's Chromium. The test exercises setup at phone and unfolded sizes,
selection restoration, cancellation, empty/error states, long lists, safe text
rendering, and isolation from Spotify/playback. It does not authenticate to Plex.

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
