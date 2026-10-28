# 공통 업무 그리드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 아래 단위를 TDD와 독립 검토 후 작은 커밋으로 진행한다.

**Goal:** 승인한 공통 그리드 설계를 구현하고 적용 가능한 기존 편집·조회 표를 순차 전환한다.

**Architecture:** AG Grid Community의 typed 열 정의와 안정적인 행 ID를 사용한다. WorklogSheet가 테마·크기·정렬·필터·키보드·표시 상태를 담당하고 기존 페이지가 draft·직렬화·권한·저장·충돌·이력을 담당한다. 새 API·DB 마이그레이션은 만들지 않는다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, AG Grid Community/React 36.2.0 후보, Vitest, Vite, 기존 Java 25/Spring MVC.

## 작업·보존 경계

- 작업트리: `C:/Users/ihpark/.codex/worktrees/common-sheet/vibe-attendance`, 브랜치 `codex/common-sheet`, 시작점 `ed96992`.
- 원래 `codex/annual-requests`의 미커밋 작업은 수정·커밋·삭제하지 않는다. 새 연간 화면은 그 작업과 안전하게 통합할 수 있는 시점에만 전환한다.
- 2026-10-29 사용자가 설계, 모든 적용 가능한 시트, 작은 단위 커밋과 일반 Push를 승인했다. 단계별 재확인을 요구하지 않는다.
- 매 단위는 관련 테스트 → 타입·build → diff 검토 → 필요한 파일만 commit → `git push -u origin codex/common-sheet` 순서로 진행한다.

## 단위 1: 승인 설계·계획 기준선

- [x] 승인한 `docs/superpowers/specs/2026-10-29-common-sheet-design.md`를 구현 기준으로 갱신한다.
- [x] `npm --prefix frontend ci --no-fund` 후 `npm --prefix frontend test`로 기존 168개 기준선을 확인한다.
- [x] 문서 링크·변경 범위를 검증하고 `docs: 공통 업무 그리드 실행 계획 확정` 커밋·Push.

## 단위 2: 의존성·CSP를 보존하는 스타일 공급

대상: `frontend/package.json`, lock, `frontend/vite.config.ts`, CSS 자산 빌드 보조/테스트, `ReactPageViewResolver.java`, `ReactPageViewTest.java`.

- [x] npm registry에서 버전·MIT·React peer dependency를 검증한다.
- [x] 스타일 자산의 고정 경로·외부 파일 공급·data URL 제거에 대한 실패 테스트를 먼저 실행한다. HTML 테스트는 `/react/sheet.css` 링크를 기대한다.
- [x] 동일 버전 Community/React만 로컬 의존성으로 추가한다. `theme="legacy"`와 외부 CSS를 사용하고 패키지의 inline 폰트/이미지는 self-host 자산으로 빌드한다. `unsafe-inline`, `unsafe-eval`, 외부 CDN·data 허용을 추가하지 않는다.
- [x] `npm --prefix frontend test`, `npm --prefix frontend run build`, Java HTML 단위 테스트와 production CSS 자산 검사를 통과한 뒤 커밋·Push.

## 단위 3: 공통 WorklogSheet

대상: `frontend/src/components/WorklogSheet.tsx`, 테스트, 공통 sheet CSS.

- [x] 행 ID 기반 편집·선택·정렬/필터·읽기 전용·빈 결과·숨은 변경/선택 건수·오류 행 포커스를 검증하는 실패 테스트를 작성·실행한다.
- [x] `ColDef<T>[]`, `rows: T[]`, `getRowId(row)`, 기존 페이지가 제공하는 셀과 선택/변경 상태로 공통 그리드를 구현한다. 제어형 셀은 변경 즉시 draft에 반영해 이동 보호와 저장 직렬화가 최신 값을 보도록 한다.
- [x] 기본 높이40px, 열 조절·식별 열 고정·단일 열 정렬·기본 필터·현재 페이지 적용 안내·키보드 탐색·작은 화면 내부 스크롤을 제공한다.
- [x] 페이지의 행 ID/오류를 유지하고 오류 요약으로 숨은 행 노출·스크롤·셀 포커스를 제공한다. 테마·버튼·상태 표시를 공통화한다.
- [x] 관련 테스트와 build 후 독립 코드 검토를 받아 커밋·Push.

## 단위 4: 공통·근태코드 파일럿

대상: `CodePages.tsx`, `CodePages.test.tsx`, 공통·근태코드 계약 문서.

- [x] 기존 409/422·503 재시도·50행·그룹 간 미저장 보호 테스트를 보존하고 정렬/필터 후 ID 기반 편집과 저장의 실패 테스트를 추가한다.
- [x] 공통코드 그룹·상세와 근태코드를 같은 모듈로 연결한다. 시스템 소유·고정 코드·비활성 적용일·정책 버전 계약은 기존 페이지에 남긴다.
- [x] PC/390px 실제 그리드·편집·취소·키보드·가로 넘침을 검증한다. 저장 payload·409/422 오류·재시도는 실제 AG Grid를 사용하는 자동 회귀로 검증하며 브라우저에서 기본 DB 저장은 하지 않는다.
- [x] 테스트·타입·build·독립 검토 후 문서와 함께 커밋·Push.

## 단위 5: 조직·사원·결재선 편집과 관련 조회

대상: `OrgSheetPages.tsx`, `ApprovalRoutePages.tsx`, 각 테스트·계약.

- [x] 각 화면의 공통 그리드 표시와 기존 저장·취소·충돌·권한 동작을 테스트 먼저 정의한다.
- [x] stable clientRowId, 새 행 초점, 사원 상세/소속/근무·계정 링크, 현재 조직장 문맥, 결재자 선택·조직장 해석과 원자적 저장을 보존하며 전환한다.
- [x] 이력·배정·선택 조직 직원 등 독립 조회 표도 typed 열 정의로 전환한다. 화면별 파일 담당을 나눠 병렬 구현하고 커밋은 순차로 한다.
- [x] 각 페이지 관련 테스트·build·검토 후 조직/사원과 결재선을 별도 커밋·Push.

## 단위 6: 나머지 적용 가능한 조회 표

대상: `AdminPages.tsx`, `IdentityPages.tsx`, `WorkflowPages.tsx`, `CorePages.tsx` 및 테스트.

- [x] 표별 행 식별자·전체/현재 페이지 범위·날짜/시간/숫자 표시·폼/링크를 파악하고 적용 목록을 기록한다.
- [x] 조회 전용 WorklogSheet로 전환하며 업무 계산·API·테넌트/권한은 변경하지 않는다. 달력·카드·시간 입력 행렬·합계처럼 그리드 변환에 부적합한 구조는 이유를 기록하고 유지한다.
- [x] 관련 테스트에서 접근성 이름·링크·표시·조회 결과를 확인한다. 화면 묶음별 테스트·build 후 작은 단위로 커밋·Push.

## 단위 7: 최종 회귀·공개 데모 반영

대상: `docs/quality/common-sheet.md`, README, 개발 안내, 공개 전용 작업트리/절차.

- [x] 전체 React 테스트·TypeScript·production build, 추가 의존성 audit, Java 관련 단위/통합 검증을 실행하고 실제 결과를 기록한다.
- [x] 실제 브라우저 PC·390px에서 편집/조회/정렬/필터·열 조절·고정·취소·접근성·CSP와 콘솔 오류·가로 넘침을 확인한다. 저장·409/422 오류는 자동 회귀 결과로 별도 기록하며 jsdom 결과로 브라우저 레이아웃을 통과 처리하지 않는다.
- [x] 적용·유지 화면과 근거, 제한, 커밋 SHA와 미실시 검증을 보고서·README에 기록한다.
- [ ] 공개 보안 통합·전용 백업/복원·배포: 마지막 공개 기록V13이며 V14를 포함하는 개발본의 무마이그레이션 교체를 금지한다. 정상 관리 연결이 없어 이번 외부 배포는 미실행으로 보고했다. PriceSys·demo·portfolio와 WorkLog DB를 변경하지 않았다.
- [x] 최종 diff·상태·원격 SHA를 확인하고 문서 커밋·Push. 기존 연간 작업을 본 단위에 임의로 포함하지 않는다.
