// PHONY · two cards that ask for something once: whose player this is, and which Spotify app to sign in as

/* ---------- whose player this is: a label-maker strip on first run ---------- */
function owner(){ return (store.get('owner', '') || '').trim(); }
// the black shell's tag: "PROPERTY OF" whoever typed their name; nobody yet, a warning instead
function ownerTag(){ const o = owner(); return o ? 'PROPERTY OF ' + o.toUpperCase() : 'HANDS OFF'; }
function card(host, inner){
  const v = el('div', 'setup'); v.append(inner); host.append(v);
  requestAnimationFrame(() => v.classList.add('show'));
  const close = () => { v.classList.add('gone'); setTimeout(() => v.remove(), 300); };
  return close;
}
function askOwner(){
  if ($('.setup')) return;
  const host = S.mode === 'open' ? $('#inner') : $('#cover');
  const c = el('div', 'setupcard');
  c.append(el('div', 'sk', 'WHOSE PLAYER IS THIS?'));
  const strip = el('div', 'dymostrip'), input = el('input', 'dymoin'); input.type = 'text'; input.maxLength = 14; input.autocomplete = 'off'; input.spellcheck = false; input.placeholder = 'YOUR NAME';
  strip.append(el('span', 'dymolead', 'PROPERTY OF'), input); c.append(strip);
  const keys = el('div', 'skeys'), done = el('button', 'sdone', 'DONE'); done.disabled = true; keys.append(done); c.append(keys);
  input.addEventListener('input', () => { done.disabled = !input.value.trim(); });
  input.addEventListener('keydown', e => { if (e.key === 'Enter' && input.value.trim()) done.click(); });
  const close = card(host, c);
  done.addEventListener('click', () => { store.set('owner', input.value.trim().toUpperCase()); ensureAudio(); sfx('key'); close(); applySkin(); });
  setTimeout(() => input.focus(), 400);
}

/* ---------- which Spotify app: paste the client ID of your own ----------
   Spotify serves only a few accounts per app, so each person makes one (see the README). A build
   may carry an ID already; a pasted one takes over. */
function openSpotifySetup(){
  if ($('.setup')) return;
  const host = S.mode === 'open' ? $('#inner') : $('#cover');
  const c = el('div', 'setupcard');
  c.append(el('div', 'sk', 'YOUR SPOTIFY APP'));
  const steps = el('ol', 'ssteps');
  ['developer.spotify.com/dashboard → Create app', 'Redirect URI: phony://callback · APIs: Web API, Android', 'Settings → User Management: add the accounts that will use it', 'Copy the Client ID and paste it here']
    .forEach(t => steps.append(el('li', '', t)));
  c.append(steps);
  const input = el('input', 'idin'); input.type = 'text'; input.autocomplete = 'off'; input.spellcheck = false; input.placeholder = 'client id';
  input.value = (boxStatus.clientId || ''); c.append(input);
  const keys = el('div', 'skeys'), cancel = el('button', 'scancel', 'NOT NOW'), done = el('button', 'sdone', 'DONE'); keys.append(cancel, done); c.append(keys);
  const ok = () => /^[0-9a-f]{32}$/.test(input.value.trim().toLowerCase());
  done.disabled = !ok(); input.addEventListener('input', () => { done.disabled = !ok(); });
  const close = card(host, c);
  cancel.addEventListener('click', () => { ensureAudio(); sfx('tick'); close(); });
  done.addEventListener('click', () => {
    ensureAudio(); sfx('key'); close();
    if (N){ N.setSpotifyClientId(input.value.trim().toLowerCase()); setTimeout(() => { readBox(); renderBoxes(); N.spotifyLogin(); }, 300); }
  });
  setTimeout(() => input.focus(), 400);
}
