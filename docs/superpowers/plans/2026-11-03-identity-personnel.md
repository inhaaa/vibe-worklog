# 사번·권한·내정보 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. 작업 단위별 TDD와 독립 검토를 실행한다.

**Goal:** 담당자 99999와 입사연도별 5자리 사번, 단일 로그인/업무 권한 선택 및 7개 내정보 탭을 실제 저장·조회 경로에 연결한다.

**Architecture:** UUID 참조와 기존 인증/권한·OrgCommands·인사 저장 계약을 재사용한다. 인증/화면과 인사 저장은 담당 파일을 분리하고 합성 자료 전환은 별도 명시 명령으로 실행한다.

**Tech Stack:** 기존 Java25/Spring/MyBatis/PostgreSQL/React/TypeScript/Vitest. 신규 의존성 없음.

## 1. 인증·권한 UI

- [x] `AuthStore`, `CompanyAuthenticationProvider`, `LocalDemoLogin`, `AuthPages`, `App`, `navigation` 및 관련 테스트를 확인한다.
- [x] 회사 간 같은 사번 격리·사번 로그인·미부여 역할 선택 방지·현재 사용자 유지의 실패 테스트를 실행한다.
- [x] 검증된 회사 안에서 사번으로 계정을 찾고 이메일 이행 호환을 유지한다. 데모는 명시 계정 allowlist와 실제 DB 상태·해시·현재 권한 검증을 유지한다.
- [x] 보유 권한 선택으로 메뉴를 바꾸고 권한 관리는 기존 독립 페이지에서 수행한다. 자기 승인은 서버에서 계속 거부한다.
- [x] 관련 Java/프론트 테스트와 타입 검사를 통과시킨다.

## 2. 인사정보 저장·조회·탭

- [x] `EmployeePersonnel*`, `ProfileCompany*`, `PersonnelPage`, `IdentityPages` 및 V16을 먼저 읽는다.
- [x] 후속 migration의 복합 FK/RLS·상세 DTO·입력 계약을 정의한다. 주민번호 원문 필드는 만들지 않는다.
- [x] 기본 항목/각 이력의 등록·버전 충돌·현재 권한·타 회사/본인 경계·빈 값·탭 이동의 실패 테스트를 먼저 실행한다.
- [x] 기존 인사 명령/공유 잠금 읽기 패턴으로 담당자 편집·본인 조회를 연결한다. 발령은 실제 소속 이력을 재사용한다.
- [x] 관련 PostgreSQL 통합·프론트 테스트와 타입/빌드를 통과시킨다.

## 3. 사번 발급·합성 자료 후속 전환

- [x] `EmployeeService`, `OrgBulkService`, bootstrap/approval setup/공개 후속 자료 코드를 확인한다.
- [x] `2026 + 1 -> 26001`, `2025 + 2 -> 25002`, 001 시작/999 초과 거부·예약99999·중복·재시도·기존 UUID 보존 테스트를 먼저 실행한다.
- [x] 연도별 발급은 기존 고객사 독점 잠금 안에서 수행한다. 데모 전용 명령은 고정 합성 회사·현재 자료·감사 증거를 검증하고 담당자 이름/번호 및 다른 실제 입사연도 번호만 전환한다.
- [x] 기존 계정 없는 합성 사원에 명시 테스트 계정을 준비하고 재실행 무변경과 기존 업무 자료 보존을 검증한다.

## 4. 통합·검토·반영

- [x] 전체 `mvnw.cmd -B -Dfrontend.skip=true verify`와 프론트 `npm.cmd test`, `npm.cmd run build`를 실행한다.
- [x] 독립 사양 검토 후 코드 품질 검토를 수행하고 발견 문제를 수정·재검증한다.
- [x] README·실제 결과 문서를 갱신하고 `git status`/`git diff --check`/최종 diff를 확인해 단위별 commit/push한다.
- [x] 최신 공개 보안을 통합하고 승인된 WorkLog 전용 배포 절차의 백업/복원·DB/계정 보존·HTTP/PC/모바일·다른 서비스 보존 결과를 기록한다. 실행하지 못한 검증은 미실시로 남긴다.

최종 확인: 개발·공개 통합의 실제 실패/재검증 범위는 [개발 검증](../../quality/identity-personnel-20261103.md)·[공개 통합](../../quality/public-identity-personnel-release-20261103.md)을 따르고, 실제 서버/HTTP/화면·보존 결과는 [최종 공개 반영](../../quality/public-identity-personnel-deploy-20261103.md)에 기록했다. 새 장시간 부하·전체 취약점 재검사는 이번 범위에서 수행하지 않았다.
