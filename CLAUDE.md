# 링크메모 — 작업 규칙

- **앱(코드)은 사용자가 "만들어 / 올려 / 시작"이라고 할 때만 고친다.** 질문이면 답만 하고, 고칠 게 있으면 "고쳐서 올릴까요?" 먼저 묻는다. (코드를 올리면 APK 빌드가 돌아서 비용이 듦)
- 기획은 먼저 판단해서 말한다. 좋으면 docs/TODO.md 에 적고, 별로면 이유 · 대안을 말한다. 결정은 사용자가.
- 한국어 · 쉬운 말 · 짧게. 결과 먼저.
- UI: 미니멀. 회색 작은 설명 글 넣지 않기. 회색 바탕 + 흰 카드 + 둥근 모서리, 강조색은 잉크 블랙(#191f28). 모션은 transform · opacity 만.
- 시안은 사진(design/)으로 먼저 보여준다. 고를 것이 있으면 번호 붙인 여러 안을 한 장에.
- 구조: 안드로이드(Kotlin) 껍데기 + 화면은 app/src/main/assets/web (index.html · app.css · js/app.js).
  - 저장 일꾼: SaveService.kt (yt-dlp 로 설명 · 댓글 · 썸네일 · 영상, 웹페이지는 Jsoup)
  - 데이터: files/data/items.json + files/items/<id>/ (Store.kt)
- 화면은 컴퓨터 브라우저에서 가짜 데이터로 열어 볼 수 있다 (app.js 의 devBridge).
