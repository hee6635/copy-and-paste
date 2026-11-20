// 올리기 전 점검: 화면을 가짜 데이터로 열어 오류 확인 + 긴 파일 찾기
// node .claude/skills/ship/check.js [스크린샷 저장 폴더]
const fs = require('fs'), path = require('path');
const root = path.resolve(__dirname, '../../..');
const web = path.join(root, 'app/src/main/assets/web');
const out = process.argv[2] || require('os').tmpdir();
let bad = 0;

// 1) 500줄 넘는 파일
const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
const long = [...walk(web), ...walk(path.join(root, 'app/src/main/java'))]
  .filter(f => /\.(js|css|html|kt)$/.test(f))
  .map(f => [path.relative(root, f), fs.readFileSync(f, 'utf8').split('\n').length])
  .filter(([, n]) => n > 500);
for (const [f, n] of long) console.log(`! 긴 파일 ${f} ${n}줄`);

// 2) 화면 열어서 오류 확인
(async () => {
  const { chromium } = require('playwright');
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 390, height: 844 } });
  p.on('pageerror', e => { bad++; console.log('✕ 화면 오류:', e.message); });
  p.on('console', m => { if (m.type() === 'error') { bad++; console.log('✕ 콘솔 오류:', m.text()); } });
  await p.goto('file://' + path.join(web, 'index.html'));
  await p.waitForTimeout(1500);
  const shot = path.join(out, 'ship-home.png');
  await p.screenshot({ path: shot });
  await b.close();
  console.log(bad ? `✕ 오류 ${bad}개` : '✓ 화면 오류 없음', '· 스크린샷', shot);
  process.exit(bad ? 1 : 0);
})();
