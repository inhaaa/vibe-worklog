# 공통 시트 셀 직접 편집 검증

기준일: 2026-10-29, Asia/Seoul. [설계](../superpowers/specs/2026-10-29-sheet-cell-editing-design.md)·[실행 계획](../superpowers/plans/2026-10-29-sheet-cell-editing.md)에 따른 후속 작업이다. 기존 [공통 시트 검증](common-sheet.md)의 상시 입력 필드를 셀 편집 방식으로 바꿨다. 구현·검증·단위별 Push와 공개 통합 후보를 준비했으며, 실제 서버 배포는 관리 연결이 없어 미실행이다.

## 사용 방식과 보존 계약

- 일반 상태는 텍스트·선택 항목의 표시명으로 보여주고, 클릭·Enter·F2·타이핑으로 해당 셀만 편집한다. Tab/Shift+Tab은 편집 가능한 다음/이전 셀, Enter/Shift+Enter는 확정 후 아래/위 행으로 이동한다.
- Esc는 현재 셀의 편집 시작 행을 복원한다. 근태정책 적용일 보정·결재 방식의 지정 결재자 초기화도 복원하면서 앞서 확정한 다른 셀의 변경은 보존한다. 전체 취소는 기존 조회값으로 돌아간다.
- 저장된 코드·사번과 시스템 보호 필드는 입력기 없이 표시하고 편집을 차단한다. 행 선택·활성 checkbox는 직접 토글을 유지한다.
- 조직·사원·결재선·공통코드 그룹/상세·근태코드 편집에 적용한다. 조회 표·소속/근무/권한 링크·달력·트리·주간 시간 입력 행렬은 기존 목적을 유지한다.
- 페이지가 초안·commandId를 소유하며 입력마다 즉시 갱신한다. Grid의 원본 행 직접 변경을 막고 기존 안정적 clientRowId·expectedVersion·사유·50행·409/422/503·시트 간 이동 보호를 보존한다. 숫자 빈값과 문자열 날짜·null 선택도 기존 API 형식을 유지한다.
- 비편집 셀도 저장 전에 제약을 검사한다. 변경 행만 검사하여 원본 과거 정책의 적용일이 다른 행 저장을 막지 않게 한다. 필터로 숨은 선택·변경 행은 저장·취소 범위에 계속 포함한다.

## 실행 근거

첫 실패 테스트로 상시 input 존재를 확인한 뒤 구현했다. 공통 편집기의 별도 실패/회귀로 타이핑 시작 커서, Delete 초안 반영, Enter 암시적 제출, 행마다 다른 옵션의 정렬, 취소 뒤 오류 청소를 확인·보완했다. 공개 통합에서는 V14 기초 테이블 때문에 실패하던 두 공개 테스트의 합성 자료 정리 helper만 보완했다. 제품 권한·FK·RLS를 완화하지 않았다.

| 검증 | 실제 결과 |
| --- | --- |
| 최종 프론트 전체 | **16파일246개 통과**, 실패/skip0·exit0. `frontend` 디렉터리에서 `node node_modules/vitest/vitest.mjs run --maxWorkers=1 --testTimeout=30000` 실행. 2026-10-29 08:12:33 KST 시작, 고정된 `bb58875` 소스 기준 |
| 공통 편집기 회귀 | 기존17개+신규29개=46개 통과. 전체246개에 포함 |
| 관리 시트 회귀 | 조직/사원/결재선49개·코드25개 통과. 전체246개에 포함 |
| 타입·production build | 통과. 기존500KB chunk 경고 유지 |
| 공개 후보 보안·DB | Java 선택39개(단위10+전용 PostgreSQL29)·Python 배포 계약7개 통과. 후보의 이후 Java/리소스/pom 변경0을 확인해 결과 재사용 |
| 공개 인증 화면 | 공개 AuthPages9개 통과. 이후 해당 소스/의존성 변경0 확인 |
| 공개 후보 JAR | 최종 package 성공, dist8개와 JAR 내 자산8개 집합·바이트 완전 일치, public profile/identity guard 포함 |
| 문서 | 관련9문서 상대 링크169개·diff whitespace 검사 통과 |

병렬 실행 중 AG Grid 재마운트/포커스 대기·시간 제한 실패가 있어 실제 셀 준비/이동 완료를 기다리도록 테스트를 보완했다. 뒤로가기 인증 오류 검증은 비동기 popstate 완료를 기다리며 기존 개인정보 차단 검사를 유지했다. 최초 전체 실행은 저장소 루트에서 CSS 상대 경로3건이 실패했으며 올바른 frontend 디렉터리에서3건과 최종 전체를 통과했다. 제품 설정·기본 테스트 제한은 바꾸지 않았다.

독립 검토에서 선택 항목의 행별 표시명 정렬, 전체 취소 후 오류 제거, 로컬 오류의 실제 접근성 설명 연결을 보완했다. 마지막으로503 결과 미확정 뒤 셀을 열기만 하고 Esc를 누르면 명령이 바뀌던 회귀를 RED→GREEN 확인했다. 실제 입력이 없으면 기존 명령을 보존하고, 실제 입력 뒤에는 원래 값으로 다시 입력했어도 연관 필드 snapshot 취소를 유지한다. 이 수정 후 제품·테스트 소스를 고정하고 최종246개를 다시 실행했다.

## 실제 브라우저

기존 로컬 합성 백엔드와 최신 production 자산을 읽기 검증 프록시 `127.0.0.1:18100`으로 연결했다. 일반 업무 POST는405로 차단하며 로그인/로그아웃만 허용한다. 실제 업무 DB 저장은 이 브라우저 검증에서 하지 않았다. 저장 payload·409/422·503 재시도는 자동 테스트 근거다.

- 데스크톱1265px에서 사원 고정 셀 클릭 시 editor0개, 이름 클릭 시 editor1개, 입력 즉시 변경1행, Tab으로 입사일·Shift+Tab으로 이름 복귀, Esc/전체 취소 후 변경0행을 확인했다.
- 390px 사원 화면의 실제 문서 폭375px, 화면 전체 가로 넘침0, 클릭 편집·Esc 복원을 확인했다. 임시 viewport는 원복했다.
- 인라인 style 태그0개와 콘솔 error/warn0을 확인했다. CSP는 기존 self를 유지한다.
- 빈 신규 사원 행의 저장 시도를 로컬 검증으로 차단하고 필수 오류3개·첫 셀 포커스, `aria-invalid=true`·실제 `필수 입력입니다.` 설명 연결, 전체 취소 후 신규 행/오류/초안 제거를 실제 브라우저에서 확인했다.

증거는 ignored `target/sheet-cell-editing/`의 PC·모바일·편집 중 스크린샷이다. 이 프록시 결과를 새 JAR 실행·외부 배포 완료로 확대하지 않는다.

## 공개 배포 상태

사용자는 공개 배포를 승인했다. 기존 공개 worktree는 보존하고 별도 [`codex/public-sheet-release` 후보](https://github.com/inhaaa/vibe-worklog/blob/codex/public-sheet-release/docs/quality/public-sheet-release-20261029.md)에 공개 공유 계정 보호와 개발 V14·시트 개선을 통합·Push했다. 해당 후보에서 Java 선택39개(단위10+전용 PostgreSQL29)·Python 배포 계약7개와 공개 AuthPages9개를 통과했고, production build와 실제 프론트8파일의 JAR 바이트 일치를 확인했다. 후보 Java 소스/리소스/pom은39개 검증 기준과 동일하다.

최종 제품 소스는 `bb58875`, 후보 통합은 `db378b2`다. 최종 JAR SHA256은 `50f8ff03583e0ab7205d90ce5e761c7533da9d10418c997edbd6f6b9c36d28cb`다. 공개 AuthPages2파일을 제외한 공통 프론트37파일은 원본과 동일하며 LF 정규화 manifest SHA256은 `68e5ba41be9e834c98f6945e64229cca35ec7c827beac12e06dbcad273729172`다. 원본246개 결과와 공개 AuthPages9개 결과를 이 범위에 맞게 사용한다.

아래는 셀 구현 완료 당시의 배포 준비 이력이다. 당시 정상 SSH 관리 자격·연결된 Hostinger 콘솔이 준비되지 않아 서버 반영을 하지 않았다. 사용자에게 hPanel → VPS `srv1852986.hstgr.cloud` → Browser terminal/Webconsole → root 프롬프트 연결을 요청했다. 비밀번호·세션 URL을 채팅에 보낼 필요는 없다. 서버 작업은 [공개 배포 절차](../development/worklog-public-updates.md)의 `/srv/worklog`·`worklog-demo`·`worklog_demo` 경계와 전용 백업·독립 복원·V14 업그레이드 보존 검증부터 이어간다. V14 뒤 구 앱만 무조건 롤백하지 않는다.

셀 구현 단위에서는 기본 로컬 DB·기존 공개 서버/DB·PriceSys·demo·portfolio·전역 설정을 변경하지 않았다. 새 API·DB migration·추가 npm 의존성도 없다. 새 장시간 부하·전수 취약점 재검사는 수행하지 않았다. 기존 Vite500KB 번들 경고는 유지된다.

참고한 공식 API: [Cell Editing](https://www.ag-grid.com/react-data-grid/cell-editing/), [Edit Components](https://www.ag-grid.com/react-data-grid/cell-editors/), [Saving Values](https://www.ag-grid.com/react-data-grid/value-setters/). 실제 설치36.2.0의 타입·소스와 테스트 결과를 함께 확인했다.


## 후속 공개 반영 완료

사용자가 Hostinger 콘솔 연결을 완료한 뒤 공개 보안을 통합한 `12f0f78`·V14를 실제 반영했다. 독립 복원·39개 테이블399행/권한 보존, 공개72항목·프록시/포트4항목·PC/390px6회를 확인했다. 모바일 긴 코드 겹침은 wrapper 최소 폭 한 줄(`17a9e25`)로 보완하고 선택49개·타입/build·공개 기하 RED→GREEN을 확인했다. 기존246개와 합산하지 않는다. 세부 SHA·이미지·백업·미실시 범위는 [실제 배포 결과](public-sheet-deploy-20261029.md)를 따른다.
