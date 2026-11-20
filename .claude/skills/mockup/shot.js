// HTML 시안을 사진으로: node shot.js <html> <png>
const path = require('path');
(async () => {
  const [html, png] = process.argv.slice(2);
  const b = await require('playwright').chromium.launch();
  const p = await b.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  await p.goto('file://' + path.resolve(html));
  await p.waitForTimeout(500);
  await p.screenshot({ path: png, fullPage: true });
  await b.close();
  console.log('✓', png);
})();
