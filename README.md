# PHONY

A portable cassette player for the Galaxy Z Fold 8.

- **Closed** (cover screen): the front of the player. Reels turn in the window, the keys on the silver side panel click, the tape pack moves from reel to reel as the side plays.
- **Open** (inner screen): the J-card with the tracklist, tape counter and tape shelf on the left, the cassette filling the bay on the right.

## Music

Tap the title at the top of the J-card (open view) to choose:

- **Whatever is playing now** – Spotify, YouTube Music, podcasts. PHONY becomes the remote and shows the song, the queue and the cover. Two songs in a row from the same album swap in the worn album tape.
- **All songs** or **an album** saved on the phone. Albums play on the album tape with their own cover art.

## How it's built

- `app/src/main/assets/index.html` – the whole player: drawing, keys, sounds, J-card. Opens in a desktop browser too (uses a silent sample mix).
- `MainActivity.kt` – shows the page full screen and bridges it to the phone.
- `PlaybackService.kt` – plays saved songs in the background (Media3), with lock-screen controls.
- `Library.kt` – reads songs and album art from Android's media library.
- `RemoteWatcher.kt` – follows and controls other apps' players.

## Builds

Every push to `main` builds a signed APK and puts it at:

https://github.com/hughhowey/phony/releases/download/latest/phony.apk

Signing uses two repository secrets: `PHONY_KEYSTORE_BASE64` and `PHONY_KEYSTORE_PASSWORD`. Keep the same key forever; a different key means uninstalling before the next update installs.

Fonts are from Google Fonts under the SIL Open Font License (see `assets/fonts/licenses`).
