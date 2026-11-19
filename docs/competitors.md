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
