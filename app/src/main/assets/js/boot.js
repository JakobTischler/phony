// PHONY · start-up
/* ---------- boot ---------- */
applyFx(); setVol(N ? Math.max(0, N.getVolume()) : S.vol); renderJList(); tapeChanged(); layout();
const saved = store.get('src', null);
if (N && saved){
  if (saved.kind === 'remote' && N.hasListenerAccess()) chooseSource(saved, true);
  else if (saved.kind === 'local' && N.hasAudioPermission()) chooseSource(saved, true);
}
if (N && S.src.kind === 'demo') $('#mixtitle').textContent = 'Tap here for music';
// know the drawer's playlists from the start, so a playlist already playing in Spotify gets its tape
if (N) try { readBox(); } catch (e) {}
readRadio(); ensureBlank();
// browser version only: jump the sample clock, for trying things out
if (!N) window.phonyTestSeek = t => { S.t = t < 0 ? dur(S.idx) + t : t; };
if (N) try { radioNoticeId = JSON.parse(N.radioNotice()).id; } catch (e) {}
requestAnimationFrame(loop);
// the pens' fonts only load when asked for, and the tapes are drawn on canvas, so ask
const penFonts = ['"Permanent Marker"', '"Nothing You Could Do"', '"Rock Salt"', '700 1px Caveat', '"Gochi Hand"', '"Reenie Beanie"'];
if (document.fonts) Promise.all(penFonts.map(f => document.fonts.load((/^\d/.test(f) ? f : '40px ' + f)).catch(() => {}))).then(() => document.fonts.ready).then(() => {
  Object.keys(thumbCache).forEach(k => delete thumbCache[k]); refreshShells(); tapeChanged(); if (boxVisible()) renderBoxes();
});
