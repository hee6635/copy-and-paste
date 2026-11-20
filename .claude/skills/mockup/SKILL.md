---
name: mockup
description: 링크메모 화면 시안을 사진(design/*.png)으로 만든다. 고를 게 있으면 번호 붙인 여러 안을 한 장에. "시안", "/mockup", "어떻게 생길지 보여줘", 새 화면 · 기능 디자인을 정할 때 쓴다. 앱 코드는 고치지 않는다.
---

# /mockup — 시안 사진

시안은 코드 전에 사진으로 먼저 보여준다. **앱 코드(app/)는 건드리지 않는다.**

## 디자인 규칙 (링크메모)

- 회색 바탕(`#f2f4f6`) + 흰 카드(`#fff`) + 둥근 모서리(14~20px)
- 강조색은 잉크 블랙 `#191f28` 하나. 다른 색은 위험(`#e0455c`) · 별 배경(`#fff4e5`) 정도만.
- 글자: Pretendard (`app/src/main/assets/web/fonts/PretendardVariable.woff2`), 제목 굵게 · 짧게
- **회색 작은 설명 글 넣지 않기.** 꼭 필요한 말만, 버튼 · 제목 안에.
- 아이콘은 선 아이콘(stroke 1.8). 이모지 · 그라데이션 · 그림자 남발 금지.
- 모션 설명이 필요하면 transform · opacity 만 쓴다고 적는다.
- 색 · 간격은 `app/src/main/assets/web/app.css` 의 `:root` 값을 그대로 쓴다.
- 흔한 AI 느낌 금지: 보라 그라데이션, 큰 히어로 문구, 카드마다 이모지, 의미 없는 배지.

## 순서

1. 실제 화면과 맞추려고 관련 화면을 먼저 본다: `node .claude/skills/ship/check.js <scratchpad>` 의 스크린샷, 또는 해당 js 의 마크업.
2. scratchpad 에 HTML 하나를 쓴다. 폰 화면(390×844)을 가로로 나란히. 여러 안이면 각 폰 위에 큰 번호 **1 2 3** 과 2~4글자 이름.
   - 글꼴은 `file://` 로 위 woff2 를 불러온다. 가짜 내용은 dev.js 처럼 현실적인 한국어로.
3. 사진으로 찍는다:
   `node .claude/skills/mockup/shot.js <html> design/<이름>.png`
4. 사진을 Read 로 열어 규칙을 어긴 곳이 없는지 본 뒤 SendUserFile 로 보여준다.
5. 고르게 한다: "몇 번으로 할까요?" — 결정은 사용자. 정해지면 docs/TODO.md 해당 줄에 `(시안 design/<이름>.png)` 를 붙인다.
6. 올리는 건 사용자가 말할 때. design/ 은 APK 빌드가 안 돈다.
