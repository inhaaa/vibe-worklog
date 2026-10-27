# 전체 React 프론트 전환 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 독립 파일 담당을 병렬 처리하고 합친 뒤 계약·보안을 검토한다.

**Goal:** 기존 업무 기능과 보안 경계를 유지하면서 모든 실제 화면을 React로 렌더한다.

**Architecture:** 기존 URL과 ModelAndView의 업무 데이터를 명시적인 JSON 화면 인터페이스로 제공한다. React가 HTML/폼을 소유하고 Spring은 업무·인증·데이터 권한과 정적 파일 제공을 담당한다.

**Tech Stack:** Spring Boot/Java25, React, TypeScript, Vite, Vitest. 버전은 공식 요구사항과 registry 확인 뒤 고정한다.

실행 결과: 2026-10-26 아래8개 작업을 완료했다. Java479·프론트71·Python47·HTTP24·로컬 새 이미지와 브라우저 관측의 실제 범위·남은 검증은 [보고서](../../quality/react-migration.md)에 기록했다.

## 작업과 검증 순서

- [x] 서버: `common/web`의 React ViewResolver·안전한 envelope와 `common/config`·운영자 보안의 JSON 실패 분기 테스트부터 작성한다. allowlist·비밀 제외·XSS·401/403/503·기존 redirect를 검증한 후 구현한다.
- [x] 공통: `frontend/src/types.ts`, `ui.tsx`, 탐색/폼 transport의 계약을 정의한다. CSRF 폼 인코딩·최신 응답 우선·실패/다운로드·사용자 정보 제거 테스트를 먼저 실행해 실패를 확인하고 구현한다.
- [x] 관리: `frontend/src/pages/AdminPages.test.tsx`에서 11개 view의 기존 필수 폼·버전/명령·검색/페이징·빈상태를 검증한 후 `AdminPages.tsx`를 구현한다.
- [x] 업무: `WorkflowPages.test.tsx`에서 승인/자기승인·정정/확인·원장·미확정 시간·감사 페이지 계약을 고정한 후 `WorkflowPages.tsx`를 구현한다.
- [x] 공개/개인: 인증/초대/복구 비밀값 미복원, 홈·달력·운영자 독립 경계 테스트 후 `CorePages.tsx`를 구현한다.
- [x] 빌드: 프로젝트 전용 npm lock과 Vite/TypeScript 설정, Maven 자원 연동·Docker 프론트 빌드 단계를 추가한다. 기존 Thymeleaf dependency를 제거한다. 원문 템플릿/자산은 삭제하지 않는다.
- [x] 프론트 `npm test`, `npm run build`; Java25 Maven `test`, 전용 PostgreSQL `verify`를 실행하고 실제 실패를 고친다. 새 계약 때문에 달라진 표현 검사만 변경하고 업무 기대값은 보존한다.
- [x] 실제 패키지 HTTP·브라우저 검증과 문서/라이선스 동기화 후 `git diff --check`, status/diff·신규 파일을 검토한다. 실행하지 못한 검사와 남은 한계를 구분해 보고한다.

확정 인터페이스는 [전체 전환 계약](../specs/2026-10-26-react-full-migration.md)을 따른다. 추가 기능·DB 스키마 변경·외부 배포·Push는 범위 밖이다.
