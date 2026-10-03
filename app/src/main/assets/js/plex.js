// Plex setup only. Native code owns authorization, discovery, and saved credentials.
let plexCard = null;
function plexStatus(){
  try { return JSON.parse(N.plexStatus()); }
  catch (_) { return {state:'signed_out', message:'Plex setup is unavailable. Reopen the app.', servers:[], libraries:[]}; }
}
function appendPlexSource(host){
  if (!N.plexStatus) return;
  const p = plexStatus(), selected = p.selection;
  host.append(heading('YOUR PLEX LIBRARY'));
  host.append(row(selected ? selected.libraryName : 'Connect to Plex',
    selected ? selected.serverName + ' · Manage connection' : 'Sign in and choose your music library.',
    () => { closeSheet(); openPlexSetup(); }));
  if (selected) host.append(row('Browse Plex albums', selected.libraryName + ' · Open the tape box', () => { closeSheet(); openPlexBox(); }));
}
function openPlexSetup(){
  if ($('.setup') || !N) return;
  const content = el('div', 'setupcard plexcard');
  content.setAttribute('role', 'dialog');
  content.setAttribute('aria-modal', 'true');
  content.setAttribute('aria-label', 'Plex setup');
  // The raised pocket J-card is a separate stacking context above the cover.
  // Keep setup at the document root so it stays above both player layouts.
  const close = card(document.body, content);
  content.parentElement.classList.add('plexsetup');
  const closeSetup = () => {
    N.plexCancelLogin(); plexCard = null; close();
    if (!sheet.hidden) openSheet();
  };
  plexCard = {content, close:closeSetup};
  content.addEventListener('keydown', event => {
    if (event.key === 'Escape'){ event.stopPropagation(); closeSetup(); }
    if (event.key === 'Tab'){
      const buttons = [...content.querySelectorAll('button:not(:disabled)')];
      if (!buttons.length) return;
      const first = buttons[0], last = buttons[buttons.length - 1];
      if (event.shiftKey && document.activeElement === first){ event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last){ event.preventDefault(); first.focus(); }
    }
  });
  renderPlexSetup();
  N.plexOpen();
}
function renderPlexSetup(){
  if (!plexCard) return;
  const p = plexStatus(), c = plexCard.content;
  const focusKey = c.contains(document.activeElement) ? document.activeElement.dataset.plexKey : null;
  c.replaceChildren();
  c.append(el('div', 'sk', 'YOUR PLEX LIBRARY'));
  const text = value => c.append(el('p', 'plexcopy', value));
  const button = (title, action, key, disabled = false) => {
    const b = el('button', 'plexchoice', title);
    b.type = 'button'; b.dataset.plexKey = key; b.disabled = disabled;
    b.addEventListener('click', action); c.append(b); return b;
  };
  if (p.state === 'signed_out'){
    text('Connect your own music collection. Sign in securely in your browser, then return to PHONY.');
    button('SIGN IN TO PLEX', () => N.plexLogin(), 'login', p.busy);
  } else if (p.state === 'waiting'){
    text('Finish signing in to Plex in your browser, then return here.');
    button('CHECK SIGN-IN', () => N.plexCheckLogin(), 'check', p.busy);
  } else if (p.state === 'ready' && p.selection){
    text(p.selection.libraryName + ' · ' + p.selection.serverName);
    text('Library saved. Open the tape box to choose an album.');
    button('OPEN TAPE BOX', () => { plexCard.close(); openPlexBox(); }, 'browse');
    button('CHANGE SERVER OR LIBRARY', () => N.plexServers(), 'servers', p.busy);
  } else if (p.state === 'libraries'){
    text('Choose a music library on ' + p.serverName + '.');
    (p.libraries || []).forEach(library => button(library.name, () => N.plexSelectLibrary(library.id), 'library-' + library.id, p.busy));
    button('CHOOSE ANOTHER SERVER', () => N.plexServers(), 'servers', p.busy);
    if (!p.busy && !(p.libraries || []).length){
      button('RETRY CONNECTION', () => N.plexSelectServer(p.serverId), 'retry');
    }
  } else {
    text('Choose your Plex server.');
    (p.servers || []).forEach(server => button(server.name, () => N.plexSelectServer(server.id), 'server-' + server.id, p.busy));
    button('REFRESH SERVERS', () => N.plexServers(), 'refresh', p.busy);
  }
  const status = el('p', 'plexcopy plexstatus', p.message || (p.busy ? 'Connecting…' : ''));
  status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite'); c.append(status);
  const keys = el('div', 'skeys');
  if (p.signedIn){
    const signOut = el('button', 'scancel', 'SIGN OUT'); signOut.dataset.plexKey = 'signout';
    signOut.addEventListener('click', () => N.plexSignOut()); keys.append(signOut);
  }
  const done = el('button', 'sdone', p.state === 'ready' ? 'DONE' : 'CLOSE'); done.dataset.plexKey = 'close';
  done.addEventListener('click', () => plexCard.close()); keys.append(done); c.append(keys);
  const target = [...c.querySelectorAll('button')].find(b => b.dataset.plexKey === focusKey && !b.disabled);
  (target || c.querySelector('button:not(:disabled)'))?.focus({preventScroll:true});
}
window.phonyPlexChanged = () => {
  renderPlexSetup();
  if (!sheet.hidden) openSheet();
};

/* Plex albums use the existing cassette cases, with a separately selected box source. */
let plexCatalogState = {albums:[], loading:false, more:false, error:''};
let pendingPlexAlbum = null, plexPlaybackError = '';
function plexBoxActive(){ return !!N && !!N.plexCatalog && store.get('boxProvider', 'spotify') === 'plex'; }
function openPlexBox(){
  store.set('boxProvider', 'plex'); showBox();
}
function readPlexBox(){
  try { plexCatalogState = JSON.parse(N.plexCatalog()); } catch (_) { }
  const old = new Map(CASES.map(a => [a.id, a]));
  CASES = sortCases((plexCatalogState.albums || []).map(a => Object.assign(old.get(a.id) || {pal:NEUTRAL, tracks:[]}, a)));
  boxStatus = {signedIn:!!plexStatus().selection, state:plexCatalogState.loading ? 'loading' : 'ready'};
  loadCovers();
}
function appendBoxSources(host){
  if (!N || !N.plexCatalog) return;
  const bar = el('div', 'boxsources'); bar.setAttribute('aria-label', 'Tape box source');
  for (const [value, label] of [['plex', 'PLEX'], ['spotify', 'SPOTIFY']]){
    const button = el('button', '', label);
    button.setAttribute('aria-pressed', String(plexBoxActive() === (value === 'plex')));
    button.addEventListener('click', () => { store.set('boxProvider', value); showBox(); }); bar.append(button);
  }
  host.append(bar);
}
function plexBoxStatusCard(){
  let text = '', action = null;
  if (!plexStatus().selection){ text = 'Connect to Plex to fill this box.'; action = openPlexSetup; }
  else if (plexCatalogState.error){ text = plexCatalogState.error; action = () => N.plexAlbums(true); }
  else if (!CASES.length){
    text = plexCatalogState.loading ? 'Fetching your Plex albums…' : 'No albums found. Tap to refresh.';
    if (!plexCatalogState.loading) action = () => N.plexAlbums(true);
  }
  if (!text) return null;
  const b = el('button', 'case note'), sp = el('span', 'sp'); sp.append(el('span', 'hw', text)); b.append(sp);
  b.disabled = !action; if (action) b.addEventListener('click', action); return b;
}
function appendPlexMore(host){
  if (!plexStatus().selection) return;
  const search = el('form', 'plexsearch'); search.setAttribute('role', 'search');
  const input = el('input', ''); input.type = 'search'; input.maxLength = 120; input.autocomplete = 'off'; input.spellcheck = false;
  input.placeholder = 'Search albums or artists'; input.value = plexCatalogState.query || '';
  const submit = el('button', '', 'SEARCH'); submit.type = 'submit'; submit.disabled = plexCatalogState.loading;
  search.addEventListener('submit', event => { event.preventDefault(); N.plexSearch(input.value); });
  search.append(input, submit);
  if (plexCatalogState.query){
    const clear = el('button', '', 'CLEAR'); clear.type = 'button'; clear.disabled = plexCatalogState.loading;
    clear.addEventListener('click', () => N.plexSearch('')); search.append(clear);
  }
  host.append(search);
  const bar = el('div', 'boxsources');
  if (plexCatalogState.more){
    const more = el('button', '', plexCatalogState.loading ? 'LOADING…' : 'MORE ALBUMS'); more.disabled = plexCatalogState.loading;
    more.addEventListener('click', () => N.plexAlbums(false)); bar.append(more);
  }
  const refresh = el('button', '', 'REFRESH'); refresh.disabled = plexCatalogState.loading;
  refresh.addEventListener('click', () => N.plexAlbums(true)); bar.append(refresh); host.append(bar);
}
window.phonyPlexCatalogChanged = () => {
  if (plexBoxActive()){ readPlexBox(); if (boxVisible()) renderBoxes(); }
  if (isPlex() && !N.plexNow()){
    pendingPlexAlbum = null; chooseSource({kind:'demo'}); toast('Plex library changed. Choose an album from the tape box.');
  }
};
function loadPlexAlbum(album){
  pause(); syncKeys(); pendingPlexAlbum = album; plexPlaybackError = '';
  toast('Loading ' + album.title + '…');
  N.plexPlayAlbum(album.id, autoReverse());
}
window.phonyPlexAlbum = (raw, error) => {
  if (!raw){ pendingPlexAlbum = null; if (error) toast(error); return; }
  let album; try { album = JSON.parse(raw); } catch (_) { return; }
  const art = pendingPlexAlbum; pendingPlexAlbum = null;
  applyPlexAlbum(album, art, false);
};
function applyPlexAlbum(album, cover, restoring){
  if (!album.tracks || !album.tracks.length) return;
  newTape(); S.cue = 0; S.mix = null; S.rec = null; S.playlist = null;
  S.src = {kind:'plex', id:album.id, title:album.title, artist:album.artist, year:album.year};
  store.set('src', S.src);
  S.boxAlbum = {id:album.id, title:album.title}; S.albumMode = true;
  S.tracks = album.tracks; S.idx = 0; S.t = 0; S.dispP = 0;
  S.playing = !restoring; S.cmdAt = restoring ? 0 : performance.now(); S.startAt = performance.now();
  ALBUM.title = album.title; ALBUM.artist = album.artist; ALBUM.key = album.id;
  ALBUM.img = cover ? (cover.img || art(cover, 880, 880)) : null;
  if (!cover && album.cover){
    const img = new Image(); img.onload = () => {
      if (isPlex() && S.src.id === album.id){ ALBUM.img = img; refreshShells(); tapeChanged(); }
    }; img.src = album.cover;
  }
  S.tape = ALBUM_TAPE;
  if (!restoring) insert(ALBUM_TAPE);
  renderJList(); trackChanged(); tapeChanged(); syncKeys(); renderBoxes();
}
function restorePlexPlayback(){
  if (!N || !N.plexNow) return;
  try { const raw = N.plexNow(); if (raw) applyPlexAlbum(JSON.parse(raw), null, true); } catch (_) { }
}
function syncPlexState(st){
  if (st.plexId !== S.src.id) return false;
  if (performance.now() - S.cmdAt > 200){
    const side = st.flipped || st.index >= half() ? 'B' : 'A';
    if (S.sideEnd !== !!st.sideEnd || S.flipped !== !!st.flipped || S.side !== side){
      S.sideEnd = !!st.sideEnd; S.flipped = !!st.flipped; S.side = side;
      refreshShells(); trackChanged(); tapeChanged();
    }
  }
  if (st.error && st.error !== plexPlaybackError) toast(st.error);
  plexPlaybackError = st.error || '';
  const label = st.error ? 'PLAYBACK ERROR · PRESS ▶ TO RETRY' : st.buffering ? 'BUFFERING FROM PLEX…' : '';
  if (label) $('#nowlabel').textContent = label;
  else if (S.plexNotice){ trackChanged(); }
  S.plexNotice = !!label;
  return true;
}
