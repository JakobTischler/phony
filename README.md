# PHONY

A portable cassette player for the Galaxy Z Fold 8.

- **Closed** (cover screen): the front of the player. Reels turn in the window, the keys on the silver side panel click, the tape pack moves from reel to reel as the side plays.
- **Open** (inner screen): the J-card with the tracklist, tape counter and tape shelf on the left, the cassette filling the bay on the right.

## Music

Tap the title at the top of the J-card (open view) to choose:

- **Whatever is playing now** – Spotify, YouTube Music, podcasts. PHONY becomes the remote and shows the song, the queue and the cover. Two songs in a row from the same album swap in the worn album tape.
- **All songs** or **an album** saved on the phone. Albums play on the album tape with their own cover art.

## The tape box

Press **■** while the tape is stopped to eject it. Closed, the player slides up and the tape box is underneath; open, the J-card slides away and the box is behind it. The box holds the albums saved in your Spotify library, newest first, ten to a box. Tap a spine to take the case out, tap the case to load the tape, and Spotify plays that album. Save or remove an album in Spotify and the box follows.

The first time, tap the note in the box to sign in to Spotify (once, in the browser). The first album you play asks Spotify's permission once too. Needs Spotify Premium and the Spotify app on the phone.

## How it's built

- `app/src/main/assets/index.html` – the whole player: drawing, keys, sounds, J-card. Opens in a desktop browser too (uses a silent sample mix).
- `MainActivity.kt` – shows the page full screen and bridges it to the phone.
- `PlaybackService.kt` – plays saved songs in the background (Media3), with lock-screen controls.
- `Library.kt` – reads songs and album art from Android's media library.
- `RemoteWatcher.kt` – follows and controls other apps' players.
- `SpotifyBox.kt` – reads your saved albums from Spotify and tells the Spotify app what to play. Uses Spotify's App Remote library (`app/libs`, from github.com/spotify/android-sdk, Apache 2.0).

## Builds

Every push to `main` builds a signed APK and puts it at:

https://github.com/hughhowey/phony/releases/download/latest/phony.apk

Signing uses two repository secrets: `PHONY_KEYSTORE_BASE64` and `PHONY_KEYSTORE_PASSWORD`. Keep the same key forever; a different key means uninstalling before the next update installs.

Fonts are from Google Fonts under the SIL Open Font License (see `assets/fonts/licenses`).
