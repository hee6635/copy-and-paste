'use strict';
/* 컴퓨터 브라우저에서 화면만 볼 때 쓰는 가짜 브리지 (폰에서는 쓰지 않음) */
// ── 컴퓨터에서 화면만 볼 때 쓰는 가짜 브리지 ──
function devBridge() {
  const now = Date.now();
  let data = [
    { id: 'L', url: 'https://m.blog.naver.com/x/1', platform: 'web', kind: '웹', status: 'done', category: '개발·IT', title: '클로드 스킬 처음 깔아 둘 10개', author: '네이버 블로그', createdAt: now,
      ai: { listName: '스킬', places: [{ name: 'find-skills', t: -1, info: '필요한 스킬을 검색해 찾아 줌' }, { name: 'frontend-design', t: -1, info: '화면 디자인을 덜 뻔하게' }, { name: 'n8n-skills', t: -1, info: 'n8n 자동화 만들 때만' }],
        summary: ['처음엔 [[find-skills]] 하나만 깔고 필요할 때 늘리기'], tips: [{ text: '이름이 흔한 스킬은 저장소 이름으로 검색', from: 'body', who: 'author' }], model: 'gemini-flash-lite-latest' } },
    { id: 'a', url: 'https://www.instagram.com/reel/x', platform: 'instagram', kind: '릴스', status: 'done', category: '여행',
      ai: { area: '나트랑', sumFrom: ['작성자 댓글', '댓글'], places: [{ name: '콩카페', t: 3, info: '코코넛 커피 [[1일 1잔]] 추천' }, { name: '청안딤섬', t: 19, info: '딤섬 한 판 [[약 3천 원]] · [[북경오리]]' }], summary: ['잠만 잘 거면 벤타나, 야경은 아트네스트', '전체 일정은 댓글에 [[6박]] 남기면 받음'], course: ['콩카페', '포나가르 사원', '청안딤섬', '제시프루츠'], folder: '여행 › 나트랑', kind: 'recipe', events: [{ title: '나트랑 야시장 불꽃놀이', date: '2026-12-24', time: '20:00', end: '', place: '나트랑 해변' }, { title: '콩카페 1+1', date: '2026-12-01', time: '', end: '2026-12-07', place: '' }], recipe: { servings: 2, ingredients: ['감자 2개', '올리브유 1큰술', '소금 1/2작은술'], steps: ['찬물에 10분', '200도 10분'] },
        tips: [{ text: '6시 전에 가야 웨이팅 없음', from: 'author', who: 'yytable' }], video: true, facts: [{ t: 5, text: 'Lai Seafood 랍스터 1kg 120만동' }, { t: -1, text: '화요일 휴무: Ớt Hiểm' }], model: 'gemini-2.5-flash',
        log: { total: 112000, rows: [{ step: '영상 올리기', ms: 8200, note: '12.3MB', ok: true }, { step: '영상 처리 대기', ms: 14000, note: '확인 7번', ok: true }, { step: '최신 flash #1', ms: 1200, note: '503 구글 서버가 바빠요(모델 혼잡) → 잠시 뒤 또는 다른 모델', ok: false }, { step: '2.5 flash #1', ms: 88000, note: '성공', ok: true }],
          usage: { totalTokenCount: 21000, promptTokenCount: 18000, videoTokens: 15000, thoughtsTokenCount: 2400, candidatesTokenCount: 600 }, why: ['실패 1번 → 다시 시도 · 다른 모델로 넘어감', 'AI가 답하기 전에 오래 생각함 (생각 2400 · 답 600 토큰)', '영상이 길어서 볼 양이 많음 (영상 15000 토큰)'] }, transcript: [{ t: 0, text: '오늘은 나트랑 3일차' }, { t: 12, text: '콩카페 먼저 가요' }] }, title: '나트랑 시내 맛집 4곳 추천', author: 'yytable', desc: '나트랑 시내에서 현지인이 많이 가는 식당 4곳!\n1. Lai Seafood\n2. Ớt Hiểm Thai Cuisine', duration: 48, w: 720, h: 1280, memo: '12월 여행 때 저녁', tags: ['여행'], createdAt: now,
      captures: [{ id: 'k1', t: 19, file: 'x.jpg', text: '랍스터 1kg', memo: '가격표', ai: true, at: { sec: 'places', i: 1 } }, { id: 'k2', t: 12, file: 'y.jpg', text: '', memo: '', at: { sec: 'facts', i: 0 } }, { id: 'k3', t: 20, file: 'z.jpg', text: '', memo: '' }], comments: [{ a: 'min_j', t: '@soo 여기 가자 😍', l: 3 }, { a: 'ha_eun', t: '저장각 ㅋㅋ', l: 1 }, { a: 'traveler_kim', t: '저녁 6시 전에 가야 웨이팅 없어요', l: 312, r: [{ a: 'yytable', t: '맞아요! 6시 넘으면 30분 넘게 기다려요', l: 20, own: true }] }, { a: 'minji', t: '저장해둡니다', l: 54 }] },
    { id: 'b', url: 'https://youtube.com/shorts/y', platform: 'youtube', kind: '쇼츠', status: 'done', category: '디자인', title: '피그마 아이콘 파일 50개 이상 무료 배포', author: 'figma.create', desc: '댓글에 icon 남기면 보내드려요', duration: 35, w: 720, h: 1280, tags: ['디자인'], createdAt: now, comments: [], commentError: '[화면] HTTP 403 login_required\n[직접] HTTP 403', captures: [] },
    { id: 'L', url: 'https://youtube.com/watch?v=abcdefghijk', platform: 'youtube', kind: '영상', status: 'done', category: '여행', title: '베트남 한 달 살기 총정리', author: 'travel', duration: 1450, long: true, longSkipped: true, createdAt: now, comments: [], captures: [], subs: [{ t: 200, text: '숙소는 한 달 단위로 직접 연락하면 싸요' }, { t: 210, text: '시내 쪽이 편해요' }], ytChapters: [{ t: 0, title: '인트로' }],
        ai: { long: true, summary: ['숙소는 한 달 단위로 직접 연락하면 [[30%]] 저렴', '우기([[10~12월]])는 피하기'], chapters: [{ t: 0, title: '왜 나트랑?', info: '물가 · 바다' }, { t: 200, title: '숙소 고르는 법', info: '한 달 [[60~80만 원]]' }, { t: 345, title: '공항 → 시내', info: '그랩 [[약 15만 동]]' }], places: [] } },
      { id: 'c', url: 'https://brunch.co.kr/z', platform: 'web', kind: '웹', status: 'done', category: '요리', title: '홈카페 라떼아트 기초', author: 'brunch.co.kr', desc: '', body: '우유 온도는 60도가 좋아요.', tags: [], createdAt: now, comments: [], captures: [] },
    { id: 'p', url: 'https://www.instagram.com/p/DfwaHRvn2PN/', platform: 'instagram', kind: '사진 3', status: 'done', title: '클로드 · GPT · 제미나이 한 번에', author: 'ai_tips', desc: '댓글에 드림팀 남기면 DM', createdAt: now - 1000, comments: [], captures: [], tags: [], photos: [{ file: 'p1.jpg', text: '클로드 하나로\nGPT · 제미나이 연결' }, { file: 'p2.jpg', text: '1. 플러그인 설치' }, { file: 'p3.jpg', text: '' }], thumb: 'p1.jpg' },
    { id: 'q', url: 'https://www.instagram.com/p/old/', platform: 'instagram', kind: '게시물', status: 'done', title: '예전 사진 게시물', author: 'x', desc: '', createdAt: now - 2000, comments: [], captures: [], tags: [] },
    { id: 'd', url: 'https://www.instagram.com/reel/z', platform: 'instagram', status: 'saving', stage: '영상 받는 중', progress: 62, createdAt: now },
    { id: 'e', url: 'https://www.instagram.com/reel/q', platform: 'instagram', status: 'error', error: '인스타 로그인이 필요해요', errorDetail: 'ERROR: login required', createdAt: now },
  ];
  return {
    items: () => JSON.stringify(data),
    add: (url, mode, memo) => JSON.stringify({ id: 'n' + Math.random(), url, mode, memo, platform: 'web', status: 'saving', stage: '대기 중', progress: 0, createdAt: Date.now() }),
    update: () => '', remove: () => {}, retry: () => {}, capture: () => '', removeCapture: () => '',
    open: () => {}, copy: () => {}, shareText: () => {}, exportFile: () => {},
    settings: () => JSON.stringify({ quality: '720', igLogin: false, used: 1.2e9, tool: '2026.11.01', app: '0.1' }),
    setQuality: () => {}, refetchComments: () => {}, setGeminiKey: () => {}, setAiOn: () => {}, aiRedo: () => {}, aiCancel: () => {}, batteryUnrestrict: () => {}, setAiSpeed: () => {}, backup: () => {}, restore: () => {},
      usage: () => JSON.stringify({ today: { 'gemini-flash-lite-latest': { n: 12, tok: 34000 } }, month: { 'gemini-flash-lite-latest': { n: 40, tok: 120000 }, 'gemini-flash-latest': { n: 3, tok: 9000 } }, last429: { at: Date.now(), model: 'gemini-flash-latest', metric: 'generativelanguage.googleapis.com/generate_requests_per_model_per_day', value: '20', retry: '33s' }, reset: '11월 20일 오후 5시', limits: { 'gemini-flash-lite-latest': 1000, 'gemini-flash-latest': 20 }, dom: 19, dim: 30, memos: 30 }),
      setLimit: () => {}, addCalendar: (j) => console.log('cal', j), manualAsk: () => {}, igPhotos: () => {}, playNative: (id, n, t) => console.log('native', id, n, t), manualPaste: (id) => JSON.stringify(Object.assign({}, data.find(x => x.id === id), { aiError: undefined, ai: { summary: ['제미나이 앱이 정리한 [[요약]]'], tags: ['테스트'], model: '제미나이 앱', manual: true } })), downloadVideo: () => {},
      ask: (q) => setTimeout(() => App.onAsk(JSON.stringify({ answer: '나트랑은 2개 저장했어요. 청안딤섬은 딤섬 한 판 [[약 3천 원]]이에요.', refs: ['a'] })), 600), setWatch: () => {}, removeMany: () => {}, updateMany: () => {}, removeCaptures: () => '',
    meta: () => localStorage.meta || JSON.stringify({ folders: [{ id: 'f1', name: '여행', parent: null }, { id: 'f2', name: '나트랑', parent: 'f1' }, { id: 'f3', name: '디자인', parent: null }], order: [] }),
    setMeta: (j) => { localStorage.meta = j; },
    diag: (u) => { const st = [['링크 종류', 'ok', '인스타 · 게시물 abc'], ['인터넷 연결', 'ok', '응답 200'], ['인스타 로그인', 'ok', '로그인됨'], ['내용 읽기 (yt-dlp)', 'ok', '제목 ✓ · 설명 320자'], ['댓글 가져오기', 'fail', '[화면] HTTP 403']];
      st.forEach((x, i) => setTimeout(() => App.onDiag(JSON.stringify({ i, name: x[0], state: x[1], msg: x[2], detail: x[1] === 'fail' ? '[화면] HTTP 403 {"message":"login_required"}' : '' })), 150 * i));
      setTimeout(() => App.onDiag('{"end":true}'), 900); }, igLogin: () => {}, igLogout: () => {}, deleteVideos: () => {}, updateTool: () => {}, toast: () => {},
  };
}
