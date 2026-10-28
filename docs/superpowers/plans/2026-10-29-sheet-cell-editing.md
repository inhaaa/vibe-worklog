# 공통 시트 셀 직접 편집 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 독립 파일을 병렬 담당하고 커밋·Push는 검증된 단위별로 순차 실행한다.

**Goal:** 상시 입력 박스를 없애고 업무 시트를 클릭·키보드로 직접 편집한다.

**Architecture:** `sheetEditorColumn`이 텍스트 표시와 AG Grid editor를 연결하고 기존 페이지가 초안을 소유한다. 공통 검증과 현재 셀 취소를 보완하며 기존 업무 API·보안을 보존한다.

**Tech Stack:** 기존 React 19.3.1, TypeScript 6.0.3, AG Grid Community 36.2.0, Vitest.

## 단위 1: 설계·범위 고정

- [x] 현재 코드·테스트·배포 문서를 읽고 입력/저장 경계를 확인한다.
- [x] [설계](../specs/2026-10-29-sheet-cell-editing-design.md)의 인터페이스와 적용 범위를 고정한다.
- [x] 문서 diff·상대 링크 확인 후 설계 커밋·Push.

## 단위 2: 공통 편집 엔진

대상: `frontend/src/components/WorklogSheet.tsx`, `worklog-sheet.css`, 공통 editor 및 테스트.

- [x] 평소 input 부재→클릭 editor, 입력 즉시 draft, Esc 시작값 복원, required 오류 이동의 실패 테스트를 먼저 실행한다.
- [x] `SheetEditorField`와 `sheetEditorColumn`을 구현한다. `value`는 원시 문자열, `options`는 `[value,label]`, `onChange(value)`는 페이지 갱신으로 정의한다.
- [x] 클릭/F2/Enter 시작, Tab/Enter 이동, 한글 조합 보존과 읽기전용 보호를 구현한다.
- [x] `validate(): boolean`과 필터 해제/오류 셀 editor 포커스를 구현한다.
- [x] `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`와 production build 후 공통 단위 커밋·Push.

## 단위 3: 관리 시트 연결

대상: `CodePages.tsx`, `OrgSheetPages.tsx`, `ApprovalRoutePages.tsx`와 각 테스트.

- [x] 각 페이지에 평소 input 부재·보호 셀 편집 차단·편집 직후 최신 payload 회귀를 먼저 추가한다.
- [x] 입력 열을 공통 editor helper로 교체하고 기존 update/조건/원시값 변환을 재사용한다. submit에서 `sheet.current?.validate()` 실패 시 제출을 차단한다.
- [x] 기존 테스트를 셀 활성→editor 변경의 사용자 흐름으로 갱신한다. 409/422/503,50행,시트 간 미저장 보호와 필터 숨은 선택 회귀를 실행한다.
- [x] 관련 테스트·타입/build 통과 후 조직/사원, 결재선, 코드 단위별로 커밋·Push한다.

## 단위 4: 실사용 검증·완료 기록

- [x] 전체 frontend 테스트·타입/build와 실제 브라우저 PC/390px를 검증한다.
- [x] 독립 코드 검토에서 발견한 범위 내 문제를 회귀 테스트와 함께 수정·커밋·Push한다.
- [x] README·검증 보고서·개발 안내에 실제 실행 결과와 제한을 기록한다.
- [x] 최종 diff·status·원격 SHA를 확인한다.

## 단위 5: 공개 배포

- [x] 기존 공개 브랜치 보안을 보존한 별도 `codex/public-sheet-release` 후보에 최종 `bb58875`를 통합하고 커밋·Push한다.
- [x] 공개 Java39개·Python 계약7개·AuthPages9개, 최종 프론트246개 공통 소스 대조·타입/build·JAR 자산8개 바이트를 확인한다. 서버 적용 검증과 구분한다.
- [ ] 정상 서버 관리 세션 연결: 현재 SSH 자격/연결된 Hostinger Webconsole이 없으며 사용자에게 root 프롬프트 연결을 요청했다.
- [ ] 공개 전용 DB 백업/독립 복원/V14 보존 검증과 제한된 서버 이미지 준비를 실행한다.
- [ ] WorkLog 앱·마이그레이션만 반영하고 공개 HTTP·공유 계정 보호·기존 서비스 보존을 확인한다.
- [x] 후보 SHA/패키징/검증을 기록하고 실제 서버 배포 미실행 및 필요한 연결 절차를 보고한다. 서버 이미지/스키마·배포 시각을 실행 전 확정하지 않는다.
