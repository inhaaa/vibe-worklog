# 조직코드·조직도 조직장 관리 구현 계획

> 작업에는 Superpowers 하위 에이전트 병렬 구현과 테스트 주도 개발을 사용한다. 항목별로 실제 검증 결과를 기록한다.

**목표** 신규 조직의 서울 생성 연도 코드를 자동 발급하고 조직도에서 조직장 배정·후임 변경·종료·예약을 관리한다. [승인된 상세 명세](../specs/2026-11-17-organization-management-design.md)

**기반** 개발 `fa86e53`의 인사기본·최초 발령과 업무별 메뉴를 포함한다. V2 조직 고유키, V12 조직장 기간·RLS, V13 결재 스냅샷, V18 발령 스냅샷, 기존 고객사 쓰기 잠금과 관리자 명령을 재사용한다. Java·Spring MVC·MyBatis와 React·TypeScript를 사용한다.

## 작업 A. 조직코드

- [x] `OrganizationServiceIT`·`OrganizationWebIT`·`OrgBulkWebIT`에 코드 공란 생성, 과거 코드 재조회, 비활성 번호 충돌, 단건·시트 동시 등록, 재시도, 잘못된 코드와 배치 롤백을 먼저 재현한다. 서울 12월 31일과 1월 1일 생성 시각을 경계 사례로 사용한다.
- [x] `OrganizationService.java`·`OrganizationAdminMapper.java`·`OrgBulkService.java`에서 공통 규칙으로 발급한다. 명령 재생 판정 이후 같은 트랜잭션에서 서버 생성 시각과 당해 연번을 결정한다. 기존 코드를 조회·수정 대상 식별에 그대로 사용할 수 있게 한다.
- [x] `OrgAdminController.java`·`OrgBulkController.java`·`BulkData.java`와 프런트의 `AdminPages.tsx`·`OrgSheetPages.tsx`/관련 테스트에서 공란 CREATE, 읽기 전용 신규·기존 코드 칸, 저장 결과의 코드·조직 상세 링크를 검증한다. 배치 결과가 현재 필터 밖인 경우도 포함한다. 기존 감사 재생 자료의 응답 해석을 유지한다.
- [x] 관련 Java 테스트와 React 테스트를 다시 실행하고 실패 조건과 통과 수를 기록한다.

## 작업 B. 조직도 조직장

- [x] `OrganizationHeadWebIT`와 React 조직도 테스트에 하루 배정, 후임 시작일 경계, 기존 예약·공석, 미래 예약 수정·취소, 종료일·즉시 종료, 권한과 실패 후 문맥 보존을 먼저 재현한다.
- [x] `OrganizationHeadData.java`·`OrganizationHeadService.java`·`OrganizationHeadMapper.java`·`OrganizationHeadController.java`의 명령 경계를 정한다. UI 종료일은 포함, DB 종료 경계는 제외로 변환한다. 현재 재직과 미래 시작일·마지막 재직일, 중첩, 대상 배정과 버전을 확인한다. 후임 변경과 감사, 예약 교체는 고객사 잠금 아래 원자 처리한다. 기존 배정·종료 URL과 날짜 계약을 연결한다.
- [x] `POST /admin/organization-heads/commands`의 `operation`은 `ASSIGN`, `REPLACE`, `CHANGE_END`, `RESCHEDULE`, `CANCEL`을 받는다. 대상 조직·배정·사원은 `organizationId`, `assignmentId`, `employeeId`로 전달한다. 날짜는 `validFrom`·포함 종료일 `lastDay` 또는 `endToday`로 보내고 `expectedVersion`과 `commandId`로 충돌·재시도를 확인한다. `intent=PREVIEW`는 계산 결과만 반환한다.
- [x] `OrgSheetPages.tsx`의 조직도, `navigation.ts`와 필요 시 별도 조직장 관리 컴포넌트에 선택 조직의 현재·예정·이력과 관리 입력을 붙인다. 후보와 사유는 담당자 화면에서 조회한다. 기존 사원 조회 문맥을 유지하고 기간·공석 미리보기와 409/422 복구를 확인한다.
- [x] Java 테스트와 React 테스트를 다시 실행한다. 결재선 회귀에서 적용일 이후 새 제출과 기존 신청 스냅샷을 대조한다.

## 통합 검증

- [x] 두 작업은 `target/org-code-20261117`·`target/org-heads-20261117`의 독립 작업트리에서 작업하고 `codex/org-improvement-20261117`에 통합한다. 공유 프런트 파일의 조직 시트와 조직도 섹션을 각각 확인한다.
- [x] Vitest 전체, TypeScript, Vite 빌드와 Maven 검증을 실행한다. PostgreSQL 검증은 전용 로컬 테스트 인스턴스를 사용한다. 조직·조직장·결재선·최초 발령·인사 시트 회귀 결과를 기록한다.
- [x] `ApprovalRouteWorkflowIT`의 교차 시나리오에서 자동 코드로 만든 조직을 결재선에 연결한다. 당일 후임 교체 전의 대기 결재자·스냅샷과 교체 후 신규 결재자가 서로 영향을 받지 않는지 본다. 변경 전 공란 코드 등록이 422인 실패를 재현했다.
- [x] 합성 담당자 계정으로 PC와 390px 모바일 브라우저에서 생성 코드·기존 코드 조회, 조직 선택, 조직장 기간 저장과 후임 변경·종료, 오류 입력의 복구를 확인한다.
- [x] `git status`와 `git diff --check`를 확인하고, 실제 검증 범위와 결과를 보고한다.

## 검증 결과

- 최종 Vitest 44개 파일·549개 테스트, TypeScript 검사와 Vite 빌드가 통과했다.
- 통합 첫 Maven 검증에서 단위 테스트 163개가 통과했고 PostgreSQL 통합 테스트 614개 중 2개가 실패했다. 마이그레이션 및 RLS 검증에 남아 있던 V17 기대값을 이미 적용된 V18의 `employee_onboarding` 보호 조건과 마이그레이션 이력에 맞췄다. 최종 Maven 검증은 전체 단위 테스트 163개와 관련 PostgreSQL 통합 테스트 141개를 재실행해 모두 통과했다.
- Chrome PC 1440px와 모바일 에뮬레이션 390px에서 합성 담당자로 단건·시트 등록, 기존 코드 조회, 하루 배정, 후임 교체, 종료일 변경·즉시 종료, 예약 수정·취소를 처리했다. 기존 예약과 공석이 보존됐다. 최종 빌드에서 변경 전후 종료일과 사유 표시, 중첩 입력 오류의 초점 이동과 Tab 복귀, 수정 후 미리보기, 가로 넘침 여부를 확인했다.
- 결재 회귀에서 자동 코드로 만든 조직의 후임이 신규 신청의 결재자로 선택됐으며 기존 대기 신청의 결재자와 스냅샷은 유지됐다.
