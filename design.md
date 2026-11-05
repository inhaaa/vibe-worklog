# WorkLog 디자인 시스템

여러 화면이 공유하는 업무 앱 디자인 기준. 상세 계약은 `docs/design/workspace-themes.md`를 따른다.

## 방향

- Genre: modern-minimal. Shape: app screen.
- Hallmark Refract의 밝은 표면을 업무 앱에 적용. 장식 광원과 모션은 사용하지 않는다.
- 사용자가 요청한 두 테마: 차콜(기본), 블루.
- 정보 계층: 사원 기록, 수평 구분선, 표, 간결한 입력 영역.

## 토큰

`frontend/src/tokens.css`가 색상·서체의 단일 기준이다.

- `--t-paper`: 흰 본문 표면.
- `--t-canvas`, `--t-surface`: 페이지와 보조 영역.
- `--t-ink`, `--t-muted`: 본문과 보조 글자.
- `--t-blue`, `--t-blue-soft`, `--t-blue-hover`: 테마별 액션과 선택색. 기존 이름은 호환 계약이다.
- `--t-green`, `--t-amber`, `--t-danger`: 문구와 함께 사용하는 업무 상태색.
- `--t-line`: 구분선. `--t-field-border`: 입력 경계. `--t-focus`: 즉시 표시하는 초점선.
- `--font-body`, `--font-display`: 같은 한국어 시스템 산세리프.

## 크기와 상태

- 본문16px, 라벨14px, 제목28px. 숫자에는 tabular-nums.
- 컨트롤44px, 모서리8px. 터치 영역을 줄이지 않는다.
- 흰 본문과 옅은 메뉴 바탕. 중첩 카드, gradient, 장식 점은 사용하지 않는다.
- hover/active/focus/disabled/오류/처리 중/완료 상태는 기존 업무 동작과 일치한다.
- reduced-motion에서는 애니메이션·전환을 제거한다.
- 최대 폭1440px 내 업무 본문을 가운데 정렬한다. 모바일에서 메뉴를 접고 표 내부 스크롤을 사용한다.

## 변경 경계

현재 링크·폼·필드·권한·모달 탐색·업무 상태를 유지한다. 새 화면도 독자 색상 대신 공통 토큰을 사용한다.
