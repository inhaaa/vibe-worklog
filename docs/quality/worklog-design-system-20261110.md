# WorkLog 디자인 시스템 검증 결과

- 기준: 브랜치 `claude/worklog-design-system-20261110` (기준 `codex/demo-entry-public-20261109` `8cab191`)
- 범위: 1단계 기반. 2~5단계 결과는 아래에 단계별로 추가한다.
- 자료: 합성 envelope만 사용했다. DB, 업무 POST, 공개 서버는 쓰지 않았다. 캡처와 도구는 git 밖 `target/hallmark-tools/worklog-design-20261110`에 있다.
- 설계·계획: [설계](../superpowers/specs/2026-11-10-worklog-design-system-design.md), [1단계 계획](../superpowers/plans/2026-11-10-worklog-design-stage1-foundation.md), [ADR-0006](../adr/0006-worklog-design-layer.md)

## 1단계 기반 (2026-11-10)

| 항목 | 기대 | 실제 값 |
| --- | --- | --- |
| 프런트 테스트 | 전체 통과 | 35파일 474개 통과 |
| 타입·빌드 | 성공 | `tsc --noEmit`과 `vite build` 성공 |
| 검증 도구의 결정성 | 같은 빌드 두 번 캡처 시 차이 0 | 24장 차이 0 |
| legacy 층 감싸기 전후 픽셀 비교 | 24장 차이 0 | 24장 차이 0(AG Grid 시트 화면 포함) |
| 합성 캡처 JS 오류 | 0 | 0 (6개 화면 × 차콜·블루 × 1440·390px = 24장) |
| 390px 페이지 가로 넘침 | 기준 이하 | 0 (기준도 0) |
| 글꼴 | 24장 모두 Pretendard Variable | 24장 모두 `"Pretendard Variable"` |
| 대비 계약 | `tokens.test.js` 통과 | 16개 통과. 글자 토큰 11개는 흰 바탕 4.5:1 이상, 흐린 글자는 라벨 바탕에서 4.57:1, 입력 경계는 3.12:1, 두 테마 brand 확인 |
| 모바일 메뉴 열기·Esc 닫기 | 메뉴가 열리고 닫힘 | 헤드리스 확인 통과. 열림 `flex`, `aria-expanded=true`, Esc 후 `none`, 상단바 터치 대상 5개 모두 44px |
| 도움말 팝오버 | 열기·Esc·초점 복귀 | 단위 테스트 4개 통과, 실제 브라우저에서 열림·Esc 닫힘·초점이 도움말 버튼으로 복귀 |
| 조직장 칸 문구 | "조직장 미배정 조직장 배정"으로 띄어 표시 | 회귀 테스트 통과 |
| Java 테스트 | Java 변경 없음 | 미실행 |
| 실제 DB·공개 서버·실기기 | 범위 밖 | 미실행 |

빌드 결과의 Pretendard woff2는 92개이고, `frontend/dist` 전체 크기는 5.2MB다. `app.css`는 204,150 bytes, `sheet.css`는 271,197 bytes다. 화면은 `unicode-range`가 맞는 조각만 내려받는다.

### 커밋

- `8bce618`: 기존 CSS를 legacy·vendor 층으로 옮김
- `c62a370`: Pretendard와 디자인 토큰
- `91e795f`: 기본 컨트롤
- `9b8dc2c`: 부품 변형 보호
- `098fa9c`: 밝은 셸
- `8e6c5b0`: `PageHeader`
- `9fc8702`: 화면 머리 19곳 교체
- `cb940f4`: 조직장 칸 띄어쓰기

### 계획과 달라진 점

- **계약 테스트 파일 형식:** 계약 테스트를 `.test.js`로 작성했다(`cssLayers.test.js`, `design/tokens.test.js`). tsconfig에 node 타입이 없어 `.ts` 테스트의 `node:fs` import가 `tsc` 단계에서 TS2591로 실패했다. `viteProxy.test.js`와 같은 관례를 따랐다.
- **기본 컨트롤 규칙의 범위:** 처음 적용했을 때 층 없는 일반 규칙이 legacy 층의 부품별 변형까지 이겼다. 그 결과 다음 세 곳의 모양이 깨졌다.
  - 신청서의 기간 선택 상자(`t-request-period-trigger`)
  - "유형 변경" 텍스트 버튼(`t-request-text-button`)
  - 캘린더 ⋯ 버튼(`t-attendance-more`)

  그래서 기본 컨트롤 규칙에 `:not([class*=" t-"])`를 붙여 부품 클래스가 없는 컨트롤에만 적용했다. 다시 캡처해 세 곳이 원래 모양으로 돌아온 것을 확인했다.
- **상단바 버튼 높이:** "사번 변경"(부품 변형이라 legacy 44px)과 "로그아웃"의 높이가 달라, `shell.css`에서 상단바 버튼을 30px로 맞췄다. 모바일·터치에서는 44px이다.
- **동작 확인 방법:** 모바일 메뉴와 도움말은 브라우저 창 수동 확인 대신 헤드리스 확인 스크립트(`menu-check.mjs`, `help-check.mjs`)로 검증했다.

### 다음 단계로 넘긴 것

- **legacy 모양으로 남은 부품 변형 14개:** 각 부품을 옮기는 단계에서 정리한다.
  - 2단계: 시트
  - 3단계: 신청·상세 모달(`t-request-*`, `t-modal-*`)
  - 4단계: 캘린더 ⋯
  - 5단계: 홈 위젯 설정(`t-work-home-settings-*`)
- **본문 바탕:** legacy 카드가 남아 있는 동안은 연회색(g50)으로 둔다. 카드를 걷어 내는 단계에서 흰 바탕으로 바꾼다.
- **아직 legacy 구조인 화면:** 사원 헤더 카드, AG Grid 시트, 공통 모달, 캘린더는 2~4단계에서 옮긴다.
- **라이선스 CSV 누락:** 기존 `docs/development/frontend-licenses.csv`에 이미 도입된 AG Grid 행이 없다. 이번 범위가 아니므로 기록만 한다.
