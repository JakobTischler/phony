// End-to-end UI/bridge contract. Real audio and Plex authentication need an Android device.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const {chromium} = require('playwright');
const assets = path.resolve(__dirname, '../app/src/main/assets');
const server = http.createServer((req, res) => {
  const file = path.resolve(assets, '.' + new URL(req.url, 'http://localhost').pathname);
  if (!file.startsWith(assets + path.sep)){ res.writeHead(403).end(); return; }
  fs.readFile(file, (error, data) => {
    if (error){ res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', ({'.html':'text/html', '.js':'text/javascript', '.css':'text/css'})[path.extname(file)] || 'application/octet-stream');
    res.end(data);
  });
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless:true, executablePath:process.env.PHONY_TEST_BROWSER || undefined});
  try {
    for (const viewport of [{width:390, height:844}, {width:950, height:720}]){
      const context = await browser.newContext({viewport});
      await context.addInitScript(() => {
        localStorage.setItem('phony.owner', '"TEST"');
        window.calls = [];
        const album = {id:'plex:server/1:10', title:'Test album', artist:'Test artist', year:'1999', provider:'plex', cover:''};
        const songs = [0,1,2,3].map(i => ({id:String(i), title:i < 2 ? 'Repeated title' : 'Song ' + (i+1), artist:'Test artist', dur:180, disc:1, number:i+1}));
        window.catalogMock = {albums:[], more:false, loading:false, error:'', key:'server/1'};
        window.nowMock = JSON.parse(localStorage.getItem('test.now') || 'null');
        window.stateMock = {plexId:window.nowMock?.id, index:0, pos:0, dur:180000, playing:false, ended:false, sideEnd:false, flipped:false};
        const methods = {
          getVolume: () => .7, hasListenerAccess: () => false, hasAudioPermission: () => false,
          spotifyStatus: () => JSON.stringify({signedIn:false}), getBox: () => '[]', getDrawer: () => '[]', radioWaiting: () => '[]', radioNotice: () => '{"id":0}',
          plexStatus: () => JSON.stringify({state:'ready', signedIn:true, selection:{serverName:'Home server', libraryName:'Music'}}),
          plexCatalog: () => JSON.stringify(window.catalogMock), plexNow: () => window.nowMock ? JSON.stringify(window.nowMock) : '',
          plexAlbums: reset => {
            const second = {...album, id:'plex:server/1:11', title:'Second album'};
            const more = !reset && window.catalogMock.albums.length > 0;
            window.catalogMock = {...window.catalogMock, albums:more ? [album,second] : [album], more:!more, error:''};
            setTimeout(() => window.phonyPlexCatalogChanged(), 0);
          },
          plexSearch: query => {
            const result = {...album, id:'plex:server/1:12', title:'Search result', artist:query || 'Test artist'};
            window.catalogMock = {...window.catalogMock, albums:query ? [result] : [album], query, more:false, error:''};
            setTimeout(() => window.phonyPlexCatalogChanged(), 0);
          },
          plexPlayAlbum: id => {
            window.nowMock = {...album, id, tracks:songs};
            localStorage.setItem('test.now', JSON.stringify(window.nowMock));
            window.stateMock = {...window.stateMock, plexId:id, playing:true, index:0};
            setTimeout(() => window.phonyPlexAlbum(JSON.stringify(window.nowMock), ''), 0);
          },
          getState: () => JSON.stringify(window.stateMock),
          play: () => { window.stateMock.playing = true; window.stateMock.error = ''; },
          pause: () => { window.stateMock.playing = false; },
          seekTo: pos => { window.stateMock.pos = pos; window.stateMock.sideEnd = false; },
          skipTo: index => { window.stateMock.index = index; window.stateMock.pos = 0; window.stateMock.sideEnd = false; window.stateMock.flipped = false; },
          plexFlip: () => { window.stateMock.index = 2; window.stateMock.pos = 0; window.stateMock.sideEnd = false; window.stateMock.flipped = true; },
          plexEndSide: () => { window.stateMock.index = 1; window.stateMock.pos = 180000; window.stateMock.sideEnd = true; window.stateMock.playing = false; },
          plexCancelPlayback: () => { window.nowMock = null; localStorage.removeItem('test.now'); },
        };
        window.PhonyNative = new Proxy(methods, {get(target, key){ return (...args) => { window.calls.push([key,...args]); return target[key] ? target[key](...args) : ''; }; }});
      });
      const page = await context.newPage(); page.setDefaultTimeout(8000);
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);
      await page.evaluate(() => { if (S.mode === 'pocket') cardTo(true); });
      await page.waitForFunction(() => !cardSettling());
      await page.locator('#spine').click();
      await page.getByRole('button', {name:'Browse Plex albums'}).click();
      const bay = page.locator(viewport.width < 500 ? '.boxbay.c' : '.boxbay.o');
      await bay.getByRole('button', {name:'Test album by Test artist', exact:true}).waitFor();
      await bay.getByRole('button', {name:'MORE ALBUMS', exact:true}).click();
      await bay.getByRole('button', {name:'Second album by Test artist', exact:true}).waitFor();
      await bay.getByPlaceholder('Search albums or artists').fill('Miles');
      await bay.getByRole('button', {name:'SEARCH', exact:true}).click();
      await bay.getByRole('button', {name:'Search result by Miles', exact:true}).waitFor();
      await bay.getByRole('button', {name:'CLEAR', exact:true}).click();
      await bay.getByRole('button', {name:'Test album by Test artist', exact:true}).waitFor();
      await bay.getByRole('button', {name:'Test album by Test artist', exact:true}).click();
      await page.getByRole('button', {name:'Open the case and play Test album', exact:true}).click();
      await page.waitForFunction(() => S.src.kind === 'plex' && S.tracks.length === 4);
      assert.equal(await page.locator('#jlist li').count(), 4);
      await page.evaluate(() => { S.cmdAt = 0; next(); });
      await page.waitForFunction(() => window.stateMock.index === 1);
      await page.evaluate(() => seekTo(32));
      assert.equal(await page.evaluate(() => window.stateMock.pos), 31800);
      await page.evaluate(() => { S.cmdAt = 0; Object.assign(window.stateMock, {index:1, pos:180000, playing:false, sideEnd:true}); });
      await page.waitForFunction(() => S.sideEnd && !S.playing);
      await page.evaluate(() => flipTape());
      await page.waitForFunction(() => S.flipped && S.side === 'B' && !S.ejected);
      await page.evaluate(() => toSideB());
      await page.waitForFunction(() => window.stateMock.index === 2 && window.stateMock.playing);
      // The fold-out J-card must use positions, not titles, for repeated track names.
      await page.evaluate(() => pickFromCard(1, {t:'Repeated title'}, null, nowAlbum()));
      assert.equal(await page.evaluate(() => window.stateMock.index), 1);
      await page.evaluate(() => endSideA());
      assert(await page.evaluate(() => window.calls.some(([name]) => name === 'plexEndSide')));
      await page.evaluate(() => { loadTrack(0, true); });
      await page.evaluate(() => { S.cmdAt = 0; Object.assign(window.stateMock, {playing:false, error:'Plex connection failed'}); });
      await page.waitForFunction(() => !S.playing && document.querySelector('#nowlabel').textContent.includes('RETRY'));
      await page.evaluate(() => play());
      assert.equal(await page.evaluate(() => window.stateMock.playing), true);
      await page.reload();
      await page.waitForFunction(() => S.src.kind === 'plex' && S.tracks.length === 4);
      assert.equal(await page.evaluate(() => window.calls.filter(([name]) => name === 'plexPlayAlbum').length), 0, 'Reopening must not restart the native album');
      await page.evaluate(() => { skinI = SKINS.findIndex(s => s.id === 'digital'); applySkin(); });
      assert(await page.evaluate(() => window.calls.some(([name,on]) => name === 'plexAutoReverse' && on === true)));
      await page.evaluate(() => { askNotes(nowAlbum()); });
      assert.equal(await page.evaluate(() => window.calls.filter(([name]) => name === 'fetchNotes').length), 0, 'Plex metadata must not request Spotify album details');
      await page.evaluate(() => { chooseSource({kind:'remote'}); });
      assert.equal(await page.evaluate(() => window.nowMock), null);
      assert.deepEqual(errors, []);
      await context.close(); console.log(`Plex album/transport flow passed at ${viewport.width}×${viewport.height}`);
    }
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
