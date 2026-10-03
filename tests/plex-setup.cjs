// UI contract tests with a simulated native bridge; no Plex account or credentials required.
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
    const types = {'.html':'text/html', '.js':'text/javascript', '.css':'text/css'};
    res.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
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
        localStorage.setItem('phony.owner', JSON.stringify('TEST'));
        const initial = {state:'signed_out', busy:false, message:'', signedIn:false, servers:[], libraries:[]};
        window.plexMock = JSON.parse(localStorage.getItem('test.plex') || 'null') || initial;
        window.bridgeCalls = [];
        window.setPlexMock = update => {
          window.plexMock = {...window.plexMock, ...update};
          window.phonyPlexChanged?.();
        };
        const methods = {
          getVolume: () => .7,
          hasListenerAccess: () => false,
          hasAudioPermission: () => false,
          spotifyStatus: () => JSON.stringify({signedIn:false}),
          getBox: () => '[]', getDrawer: () => '[]', radioWaiting: () => '[]',
          radioNotice: () => '{"id":0,"text":""}',
          plexStatus: () => JSON.stringify(window.plexMock),
          plexOpen: () => {},
          plexLogin: () => window.setPlexMock({state:'waiting'}),
          plexCheckLogin: () => window.setPlexMock({state:'servers', signedIn:true, servers:[{id:'server', name:'Home server'}]}),
          plexServers: () => window.setPlexMock({state:'servers', message:'', libraries:[], servers:[{id:'server', name:'Home server'}]}),
          plexSelectServer: id => {
            if (id !== 'server') throw new Error('Wrong server ID passed to native');
            window.setPlexMock({state:'libraries', serverId:id, serverName:'Home server', message:'', libraries:[
            {id:'music', name:'Music <img src=x onerror="window.injected=true">'},
            {id:'classical', name:'Classical'}
          ]}); },
          plexSelectLibrary: id => {
            assertLibrary(id);
            window.setPlexMock({state:'ready', selection:{serverName:'Home server', libraryName:'Classical'}});
            localStorage.setItem('test.plex', JSON.stringify(window.plexMock));
          },
          plexCancelLogin: () => { if (window.plexMock.state === 'waiting') window.setPlexMock(initial); },
          plexSignOut: () => { localStorage.removeItem('test.plex'); window.setPlexMock({...initial, selection:null}); }
        };
        function assertLibrary(id){ if (id !== 'classical') throw new Error('Wrong library ID passed to native'); }
        window.PhonyNative = new Proxy(methods, {get(target, key){
          return (...args) => {
            window.bridgeCalls.push([key, ...args]);
            return target[key] ? target[key](...args) : '';
          };
        }});
      });
      const page = await context.newPage();
      page.setDefaultTimeout(5000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);
      await page.evaluate(() => { if (S.mode === 'pocket') cardTo(true); });
      await page.waitForFunction(() => !cardSettling());
      await page.locator('#spine').click();
      await page.getByRole('button', {name:'Connect to Plex'}).click();
      assert(await page.getByRole('button', {name:'SIGN IN TO PLEX', exact:true}).evaluate(button => {
        const rect = button.getBoundingClientRect();
        return button.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
      }), 'Plex sign-in must be above the raised J-card and receive taps');
      await page.getByRole('button', {name:'SIGN IN TO PLEX', exact:true}).click();
      await page.getByRole('button', {name:'CHECK SIGN-IN'}).click();
      await page.getByRole('button', {name:'Home server', exact:true}).click();
      assert.equal(await page.locator('.plexcard img').count(), 0, 'Library names must render as text');
      await page.getByRole('button', {name:'Classical', exact:true}).click();
      assert.match(await page.locator('.plexcard').innerText(), /Library saved/);
      await page.waitForFunction(() => getComputedStyle(document.querySelector('.plexsetup')).opacity === '1');
      const bounds = await page.locator('.plexcard').boundingBox();
      assert(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1);
      await page.screenshot({path:`build/plex-validation/plex-${viewport.width}.png`});
      await page.getByRole('button', {name:'DONE', exact:true}).click();
      await page.reload();
      await page.evaluate(() => { if (S.mode === 'pocket') cardTo(true); });
      await page.waitForFunction(() => !cardSettling());
      await page.locator('#spine').click();
      await page.getByRole('button', {name:'Classical Home server'}).click();
      assert.match(await page.locator('.plexcard').innerText(), /Classical · Home server/);
      await page.getByRole('button', {name:'CHANGE SERVER OR LIBRARY'}).click();
      await page.evaluate(() => setPlexMock({servers:[], message:'No Plex servers are available to this account.'}));
      assert.match(await page.locator('[role=status]').innerText(), /No Plex servers/);
      await page.getByRole('button', {name:'REFRESH SERVERS'}).click();
      await page.getByRole('button', {name:'Home server', exact:true}).click();
      await page.evaluate(() => setPlexMock({libraries:[], message:'This server has no music libraries available to your account.'}));
      assert.match(await page.locator('[role=status]').innerText(), /no music libraries/);
      await page.evaluate(() => setPlexMock({message:'Could not connect securely to this server.'}));
      assert.match(await page.locator('[role=status]').innerText(), /connect securely/);
      await page.getByRole('button', {name:'RETRY CONNECTION', exact:true}).click();
      assert(await page.getByRole('button', {name:'Classical', exact:true}).isVisible());
      await page.getByRole('button', {name:'SIGN OUT', exact:true}).click();
      await page.getByRole('button', {name:'SIGN IN TO PLEX', exact:true}).click();
      await page.getByRole('button', {name:'CLOSE', exact:true}).click();
      assert.equal(await page.evaluate(() => window.plexMock.state), 'signed_out');
      await page.locator('.setup').waitFor({state:'detached'});
      await page.evaluate(() => openPlexSetup());
      await page.evaluate(() => setPlexMock({message:'Plex sign-in expired. Please sign in again.'}));
      assert.match(await page.locator('[role=status]').innerText(), /expired/);
      await page.evaluate(() => setPlexMock({state:'servers', signedIn:true, servers:Array.from({length:30}, (_, i) => ({id:String(i), name:'Server ' + i}))}));
      await page.getByRole('button', {name:'Server 29', exact:true}).scrollIntoViewIfNeeded();
      assert(await page.getByRole('button', {name:'Server 29', exact:true}).isVisible());
      const forbidden = await page.evaluate(() => window.bridgeCalls.filter(([name]) => /^(play|pause|loadSource|useRemote|spotifyLogin|setSpotifyClientId)$/.test(name)));
      assert.deepEqual(forbidden, [], 'Plex setup must not change playback or Spotify');
      assert.deepEqual(errors, []);
      await context.close();
      console.log(`Plex UI flow passed at ${viewport.width}×${viewport.height}`);
    }
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
