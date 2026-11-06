// CI-only TLS peer. Keys remain in RUNNER_TEMP; never print request bodies.
import https from 'node:https';
import fs from 'node:fs';
const [key, cert, state] = process.argv.slice(2);
const counts = { ok: 0, redirect: 0, forbidden: 0, lost: 0 };
const save = () => fs.writeFileSync(state, JSON.stringify(counts), { mode: 0o600 });
save();
https.createServer({ key: fs.readFileSync(key), cert: fs.readFileSync(cert) }, (req, res) => {
  if (req.url === '/ok') {
    counts.ok++; save(); res.writeHead(200, { 'content-type': 'text/plain' }); res.end('fixture-ok');
  } else if (req.url === '/redirect') {
    counts.redirect++; save(); res.writeHead(302, { location: 'https://localhost:19443/forbidden' }); res.end();
  } else if (req.url === '/lost') {
    counts.lost++; save(); req.resume(); req.on('end', () => req.socket.destroy());
  } else {
    counts.forbidden++; save(); res.writeHead(500); res.end('unexpected-path');
  }
}).listen(19443, '127.0.0.1', () => console.log('TLS stand-in ready'));
