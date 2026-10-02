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
    text('Library saved. Plex browsing and playback are coming next.');
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
