# 시트 헤더·데이터 정렬과 한 줄 행 (2026-11-01~02)

사용자 요청 두 항목을 공통 WorklogSheet와 호출 화면에 반영했다. 일반 개발본 구현은 `5c70b5e`, 최초 설계/계획은 `195a196`이다. 로컬5175에 적용했고 공통 API·업무 규칙·DB/Flyway·환경/설정·의존성은 변경하지 않았다.

## 표시 규칙과 변경

- 모든 헤더를 중앙 정렬한다. 정렬/필터 버튼과 열 크기 조절은 유지한다.
- 실제 number·숫자 편집 필드·기존 수량/시간 숫자 열은 우측이다. 문자열 사번/코드를 임의의 숫자로 파싱하지 않는다.
- 짧은 표시 값은 중앙, 공백 분리4어절 이상/문장형 텍스트는 좌측이다. 코드값 대신 선택 라벨·formatter와 실제 렌더된 문구를 사용한다. 값이 바뀌면 이전 정렬 class를 제거한다.
- 본문은 기본40px 한 줄이다. 페이지의 큰 rowHeight11개와 wrapText/autoHeight5쌍을 제거하고, 공통 모듈에서도 자동 줄바꿈/높이를 막는다.
- 긴 값은 말줄임·기본 브라우저 tooltip/title로 확인한다. 복합 경고/계산 근거도 DOM 텍스트로 읽어 전체 내용에 포함하며 원천 값과 저장 payload를 자르지 않는다.
- 복합 셀의 block 설명을 한 줄로 이어 배치하되 상세 링크·경고·보호·오류 요약과 클릭/F2·Tab/Enter·Esc·IME 계약을 유지한다. 전역 app.css나 다른 카드/폼의 줄바꿈 규칙은 바꾸지 않았다.

수정 파일은 공통 `WorklogSheet.tsx`·`worklog-sheet.css`·회귀 테스트와 Admin/ApprovalRoute/Code/OrgSheet/Workflow5개 호출부다. 이 모듈을 쓰는 Core/Identity를 포함한7개 페이지 파일의 시트에 공통으로 적용된다.

## 실제 검증

- TDD: 처음 추가4개 모두 RED(기존17개 통과) → 관련50개 GREEN. 별도 컴포넌트 출력 누락을 재현하는1개를 추가로 RED→GREEN 처리했다.
- 최종 프론트16파일251개 통과, TypeScript `tsc --noEmit`와 Vite production build exit0. 기존 JS chunk500kB 경고는 남으며 이번 단위에서 무관한 번들 분할은 하지 않았다.
- 기존 CSS specificity 충돌을 보완하고 조직장/예외 등 자식 컴포넌트의 경고가 tooltip·정렬 판단에 포함되도록 확인했다. 독립 읽기 리뷰 최종 차단0이다.
- 실제 로컬 사원 화면: 헤더8개 중앙·40px 행, 짧은 값 중앙·복합 문구 좌측·nowrap, 상세 링크3개가 inline-flex로 유지됨을 확인했다.
- 사원 이름을3어절에서4어절로 **미저장 편집**하면 좌측으로 바뀌고, Esc로 원래 이름과 ‘변경0행’이 복원됐다. 이후 재조회에서도 기존 이름을 확인했다. 업무 저장은 실행하지 않았다.
- 실제 공통코드1280px/390px: 두 시트의 헤더16개 모두 중앙, 본문4행 모두40px, 숫자4개 모두 우측, 셀32개 모두nowrap, 페이지 가로 넘침0. 모바일 고정 열은 선택2개만 유지했다. viewport override는 종료 전에 해제했다.

추가 관측으로 현재 페이지 필터를 입력할 때 수동 조절한 열 너비가 초기화되는 경우를 확인했다. 이번 정렬·행 높이 합격 항목과 구분하며 열 너비 유지 개선은 후속이다.

이 변경은 프론트 표현만 바꿔 Java/DB 회귀·새33분 부하·전체 취약점 검사·신규 백업/복원을 다시 실행하지 않았다. 이전656개 Java·부하/보안 수치를 이번 UI 단위의 재실행으로 표시하지 않는다.

## 공개 반영 상태

공개 작업트리에는 같은 프론트8파일을 `47fb527`로 통합했다. 공개 AuthPages 보호·백엔드·V14·Dockerfile·패키지 lock 변경0을 확인했다. 공개 통합본16파일252개와 TypeScript/Vite build도 exit0으로 통과했고 두 통합 커밋을 일반 Push했다. 공개/일반 빌드의 sheet.css SHA256은 `d3ed27c65d7f3e3c9fb9abca250215e2abfd060a4ed6c67c2530a058c9b8bb0e`로 같다. 공개 검증 원시는 공개 worktree의 `target/sheet-alignment-20261101/public-*`에 보존했다. 일반 Git Push/소스 준비와 실제 공개 앱 배포를 구분한다.

최초 접속 준비에서는 SSH22 실패·웹콘솔 세션 만료로 반영을 보류했다. 이후 사용자가 연결한 정상 웹콘솔에서 2026-11-02 09:37 KST 공개 `3529a25` 앱만 교체했다. 공개72+4항목·정적8자산·PC/390px4회·편집/Esc/F2/Tab과 DB43표407행/권한 exact 보존·기존 서비스/Caddy 보존을 확인했다. DB 변경·Flyway·bootstrap·sample-data는 실행하지 않았다. 실제 이미지·시각·검증 범위와 증거는 [공개 배포 결과](public-sheet-alignment-deploy-20261102.md)를 따른다.

## 증거

원시는 일반 개발 worktree의 Git 제외 `target/sheet-alignment-20261101/`에 보존했다. PC/모바일 screenshot, DOM computed style JSON, RED/관련/전체 테스트와 빌드 로그를 포함한다. 새 라이브러리·전역 설치·설정 변경은 없다.

| 근거 | SHA256 |
| --- | --- |
| `final-tests.log` | `7a86570c72095234601046667887043c3ca53cb4c5d8681a09682537e4b125e3` |
| `final-build.log` | `f2de94a0dea082dddefc9a2a88b05f376a1c61fad64f78f21d557878e396558c` |
| `browser-observations.json` | `8898cefa3302042011dffb03aed10b3e80196126cb820dba0ce3b1a14ef3a82e` |
| `codes-desktop.png` | `c4ddee79c2b2f120d84dfe8f71832ec64f52805a0fa8900ef623378b569056ca` |
| `codes-mobile.png` | `472330843ce3abb0541dc744b68916845d6bb9c71ec0b7bb6e00455135a7b90f` |
