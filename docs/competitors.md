# 경쟁 앱 분석 (2026-11, APK 열어 봄)

## 한눈에
| | Albo 1.27.0 | 링크비 1.1.2 | 링크메모 |
|---|---|---|---|
| 규모 | 10만+ · ★4.7 (리뷰 1.9만) | 1천+ · ★5.0 (리뷰 6) | 개인용 |
| 방향 | 저장한 걸 실행 (요리 · 여행) | 저장한 걸 AI가 꺼내 씀 (MCP) | 영상 · 댓글까지 깊게 보관 |
| 앱 | Flutter | React Native (Expo) | Kotlin + 웹 화면 |
| 서버 | Supabase + 자체 서버 | Supabase + Vercel | 없음 (폰 안) |
| AI | 서버에서 Gemini (+ 구글 검색으로 장소 확인) | 서버 (모델 불확실) | 폰에서 직접 (내 Gemini 키) |
| 지도 | Mapbox | 네이버 지도 | 없음 |
| 돈 | 구독 (결제 안 하면 가입 막힌다는 불만) | AI 횟수(꿀) · 광고 · 프로 | 무료 |

## AI 방식
- 두 앱 모두 앱 → 자기 서버 → AI → 결과만 앱으로. 앱 안에 키 · 프롬프트 없음.
- Albo: `/extracts` 로 보내고 완료될 때까지 물어봄(폴링). 종류별로 따로 정리 — 레시피(영양 · 단계 이모지), 영화/드라마(TMDB), 게임(IGDB), 책(Hardcover).
- Albo 폰 안 기능: 음성 메모 받아쓰기(폰 음성 인식) · 언어 판별/번역(ML Kit) · 바코드.

## Albo 인스타 저장함 가져오기
- 숨은 웹뷰(로그인 상태)에서 인스타 내부 주소를 직접 부름
  - `instagram.com/api/v1/feed/saved/posts/` — 저장한 게시물
  - `instagram.com/api/v1/collections/list/` — 컬렉션 목록
- 틱톡 · 샤오홍슈도 비슷하게 가져옴.

## 서버 기능 이름 (= 기능 목록)
- Albo: saves · collections · imports · quick_paste · extracts · meal_plan · grocery · travel_map · workout_plan · decider(골라주기) · rediscovery(다시 보기) · recaps · journal · widget · tmdb · igdb · hardcover · Claude 연결 · 친구 공유 · 커뮤니티 · 미션 · 추천인
- 링크비: items · folders · tags · trash · imports/kakao · imports/youtube · unsorted-ai(한 번에 정리) · reminders · recommendations · ai-credits/honey · pro · MCP(mcp.linkbee.ai.kr)

## 링크메모가 앞서는 것
영상 파일 보관 · 댓글 꿀팁 · 받아쓰기 · 긴 영상 목차 · 화면 글자 읽기 · 장면 캡처 · 무료 · 로그인 없음 · 폰에만 저장

## UI (스토어 스크린샷 기준)
- Albo: 흰 바탕 · 사진 위주 바둑판(높이 제각각) · 위에 아이콘 종류 칩(장소 · 영화 · 레시피 · 책) · 지도 · 레시피는 체크 목록 · **정리 모드: 한 장씩 넘기며 지우기/두기 ("45개 남음")** · 무엇을 볼지 넘겨서 고르기 · 기능이 많아 복잡함
- 링크비: 토스 느낌 · 위 탭 피드/지도/폴더 · 2열 사진 카드 · 상세는 큰 사진 + 태그 + 내 메모 + AI 요약 · **AI 폴더 나누기 결과를 아래 창에 묶음별로 보여주고 체크 후 한 번에 적용** · 리마인드 날짜 고르는 아래 창 · 위에 꿀 개수(광고 느낌)
- 링크메모에 맞는 것: 한 장씩 정리 모드 · 종류 칩 · (지도 생기면) 위 탭 · AI 폴더 추천을 여러 개 한 번에
