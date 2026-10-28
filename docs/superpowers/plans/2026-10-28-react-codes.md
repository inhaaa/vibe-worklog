# React 공통코드·근태코드 관리 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 승인된 설계대로 같은 세션에서 독립 파일을 병렬 구현하고 명세·품질 검토를 거친다.

**Goal:** 별도 공통코드·근태코드 시트와 신청 정책 버전 고정을 구현하고 로컬 검증·문서·커밋·일반 Push를 마친다.

**Architecture:** 기존 Spring/MyBatis 모놀리스와 React/CSRF 폼, OrgCommands 배치 경계를 재사용한다. [상세 계약](../../design/code-management.md)을 스키마·DTO·HTTP 기준으로 사용한다. 기존 자료와 계산·원장은 유지한다.

**Tech Stack:** 저장소의 Java25·Spring Boot·PostgreSQL·Flyway·React·TypeScript·Vitest. 신규 의존성 없음.

## 1. 기준선·계약

- [x] README/kickoff/PRD·확장 설계7절·기존 시트/명령 경계를 확인한다.
- [x] 상세 스키마·API·capability·미지원 상태를 선행 문서화한다.
- [x] 기존 Java/React 기준선을 실행하고 기존 dirty 문서 변경을 별도로 보존한다.

## 2. 관리 서버·V14 (담당 codes_admin)

Files: `kr/tna/codes/CodeData.java`, `CodeMapper.java`, `CodeManagementService.java`, `CodeController.java`, `V14__code_management.sql`, `CodeManagementWebIT.java`.

- [x] 관리자 GET/CSRF bulk와50/51행·권한·RLS·strict parsing·전체 rollback·409draft·멱등성·시스템 보호·불변 버전 테스트를 먼저 추가한다. Maven 선택 IT로 신규 기능 부재 RED를 확인한다.
- [x] 공통그룹/상세·근태유형/불변버전, 기존/신규 tenant 시스템 정책과 최소 권한을 구현한다. 단건 반복 저장 대신 executeBatch를 사용한다.
- [x] 선택 IT GREEN, 명세·독립 품질 검토를 거친다.

## 3. 신청 정책 고정 (담당 codes_workflow)

Files: `kr/tna/request/AttendanceTypeResolver.java`, `LeaveWorkflowService.java`, `LeaveWorkflowMapper.java`, `RequestData.java`, 관련 IT.

- [x] 현재/미래·동일일revision·기간경계·disabled신규차단·기존대기승인·취소버전승계·기존자료 테스트를 추가하고 RED를 확인한다.
- [x] 날짜별 실제 정책을 고정하고 승인에서 저장된 capability를 재검증한다. 기존 연차/정정/취소/결재경로/효력/원장을 유지한다.
- [x] 선택 IT GREEN, 관리 서버와 교차 검토한다.

## 4. React (담당 codes_frontend)

Files: `frontend/src/pages/CodePages.tsx`, `CodePages.test.tsx`, `App.tsx`, 필요시 `WorkflowPages.tsx`와 `app.css`.

- [x] 별도 메뉴·시트·입력/수정/삭제예정/취소·지원상태·버전이력·실패복원·충돌·이동보호 테스트를 먼저 추가하고 RED를 확인한다.
- [x] 서버 CodeData에 맞춰 구현하고 Vitest 전체·TypeScript/Vite를 통과한다. 저장 중 잠금·레이블·첫 오류 초점·모바일 overflow를 확인한다.

## 5. 통합·로컬 검증 (담당 root)

- [x] allowlist·보안·감사·schema 기대값·복원 probe를 연결한다. 현재 JDK25 자식 환경에서 `mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`를 직렬 실행한다.
- [x] V13 기본 DB의 백업·기존 열/행해시·ACL/RLS·container/volume을 기록하고 새 app/migrate 빌드·V14 적용·기존자료 보존·실행 JAR·health를 대조한다.
- [x] 별도 합성 DB의 실제 HTTP·기본 보안·PC/모바일·신청 승인/취소·독립 복원을 확인하고 임시 환경을 정상 종료한다.
- [x] 실행 수치·실패와 보완·미실시 범위를 `docs/quality/code-management.md` 및 README/PRD/local/관련 계약에 반영한다.

## 6. 완료

- [x] 독립 명세 검토 뒤 코드품질·권한 검토, `git diff --check`·문서 링크·범위·비밀값 확인.
- [x] 기존 사용자 dirty 변경을 섞지 않고 필요한 파일만 commit·일반 Push, 원격 SHA 확인.
- [ ] [공개 데모 절차](../../development/worklog-public-updates.md)의 실제 접속 조건·현재 버전 확인 후 별도 공개 보안 통합·검증·전용 배포. 실행 불가 조건은 구체적으로 기록하며 현재 공개 버전을 유지한다.

실행 결과: [4단계 보고서](../../quality/code-management.md). 최종 Java618·React168·업무 HTTP129·보안24·화면6·로컬 V14/자료보존·합성복원 통과. 공개 작업트리에서 별도 V13 통합이 진행 중이며 해당 계획이 V14를 제외해 이번 공개 통합/배포는 미실시로 남긴다. 로컬 구현·문서·커밋·일반 Push와 공개 반영 여부를 구분한다.
