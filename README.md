# PHONY

A portable cassette player for the Galaxy Z Fold 8.

- **Closed** (cover screen): the front of the player. Reels turn in the window, the keys on the silver side panel click, the tape pack moves from reel to reel as the side plays.
- **Open** (inner screen): the J-card with the tracklist, tape counter and tape shelf on the left, the cassette filling the bay on the right.

## Shells

PHONY has seven shells: blue, black recorder, pink 80s, clear orange, silver digital, beat-up yellow and clear blush. There's no menu. Each shell hides a spot (a label, a sticker, a rubber band, a loose screw, a button); press and hold it for about a second and the next shell snaps on. A quick tap does nothing. The shell you land on stays.

## Music

Tap the title at the top of the J-card (open view) to choose:

- **Whatever is playing now** – Spotify, YouTube Music, podcasts. PHONY becomes the remote and shows the song, the queue and the cover. Two songs in a row from the same album swap in the worn album tape.
- **All songs** or **an album** saved on the phone. Albums play on the album tape with their own cover art.

## The tape box

Press **■** while the tape is stopped to eject it. Closed, the player slides up and the tape box is underneath; open, the J-card slides away and the box is behind it. The box holds the albums saved in your Spotify library, ten to a box, oldest release year first (same year: by artist). Tap a spine to take the case out, tap the case to load the tape, and Spotify plays that album. Save or remove an album in Spotify and the box follows.

The first time, tap the note in the box to sign in to Spotify (once, in the browser). The first album you play asks Spotify's permission once too. Needs Spotify Premium and the Spotify app on the phone.

## The drawer

Under the boxes is a drawer of playlist tapes: your twelve most recently played Spotify playlists, tossed in loose. Each playlist gets its own shell (fifteen designs, after real 80s and 90s tapes) and its name written on the label in a pen of its own: ballpoint, Sharpie, felt tip, pencil, gel, or paint pen on the dark shells. The shell and pen stay with the playlist. Tap one and Spotify plays it. Start a playlist in Spotify itself and its tape goes into the player.

"Recently played" comes from Spotify's recent history plus every playlist PHONY sees playing; if that's fewer than twelve, the rest are your playlists in Spotify's order. The drawer needs one more Spotify sign-in to read playlists (tap the note in the drawer).

## The J-card

Tap the song title and artist on the J-card (open view) and the album's J-card unfolds across the screen: the cover, the spine, both sides of the tape with running times and credits, the words to the song that's playing (they light up line by line as it plays), liner notes about the album, and the band with a photo. Swipe anywhere on the card to unfold further; at the far end the card sits on the left and the tape plays beside it. Tap a song to play it, or tap a line of the words to jump to it. **FOLD IT UP** or tap outside to put it back.

Album details and the band photo come from Spotify, the liner notes and band story from Wikipedia, the words from LRCLIB (lrclib.net, an open lyrics library). Not every song or album has all of them; the card leaves out what it can't find. Everything found is saved on the phone, so a card opens again with no signal. On Wi-Fi, PHONY also works through every album in the tape box in the background, so their J-cards (and the words to every song on them) are ready offline. The music itself still comes from Spotify: to play offline, download the album or playlist in the Spotify app.

## How it's built

- `app/src/main/assets/index.html` – the whole player: drawing, keys, sounds, J-card. Opens in a desktop browser too (uses a silent sample mix).
- `MainActivity.kt` – shows the page full screen and bridges it to the phone.
- `PlaybackService.kt` – plays saved songs in the background (Media3), with lock-screen controls.
- `Library.kt` – reads songs and album art from Android's media library.
- `RemoteWatcher.kt` – follows and controls other apps' players.
- `SpotifyBox.kt` – reads your saved albums and recent playlists from Spotify and tells the Spotify app what to play.
- `LinerNotes.kt` – fetches what goes on the fold-out J-card. Uses Spotify's App Remote library (`app/libs`, from github.com/spotify/android-sdk, Apache 2.0).

## Builds

Every push to `main` builds a signed APK and puts it at:

https://github.com/hughhowey/phony/releases/download/latest/phony.apk

Signing uses two repository secrets: `PHONY_KEYSTORE_BASE64` and `PHONY_KEYSTORE_PASSWORD`. Keep the same key forever; a different key means uninstalling before the next update installs.

Fonts are from Google Fonts under the SIL Open Font License (see `assets/fonts/licenses`).
