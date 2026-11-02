# 업무 메뉴·사원 인사정보·신청 UI 구현 계획

> **에이전트 실행:** subagent-driven-development와 TDD를 적용한다. 사용자 요청에 따라 독립 파일 범위를 병렬 구현하고 통합 시 명세·코드 검토를 수행한다.

**목표:** 승인된 3단계 업무 메뉴, 홈 제거/출퇴근 이전, 실제 등록 가능한 인사정보와 공통 EmployeeHeader, 현대적인 신청 모달을 완성하고 실제 테스트한다.

**구조:** 기존 React/Java 모듈형 모놀리스를 유지한다. 인사정보는 기존 employee 계약을 깨지 않는 별도 V16 테이블과 조회/명령 경계를 사용한다. 생년월일·직급·직책·직무는 선택 입력, 직급·직책·직무는 현재 적합한 표준 그룹이 없으므로 120자 이내 명칭으로 시작하고 관련 없는 공통코드 체계를 새로 만들지 않는다. 재직 표시 상태는 계정 active와 별개로 적용일 이력을 보존한다. 새 라이브러리·전역 multipart 설정 변경은 없다.

**기술:** 기존 React/TypeScript/CSS, Spring MVC/MyBatis/Flyway/PostgreSQL, Vitest/JUnit/Testcontainers.

## 선행 인터페이스

- `employeeHeader`: employeeId/employeeNo/employeeName/companyName/organizationName/joinedOn/leftOn/birthDate/gradeName/positionName/jobName/employmentStatus/photoUrl/asOf. 누락 값은 null, 상태는 WORKING/ON_LEAVE/SUSPENDED/PRE_JOINING/LEFT/UNKNOWN.
- `personnel`: `employeeId`, `form`, `statusHistory`, `version`. `form`은 birthDate/gradeName/positionName/jobName/employmentStatus/effectiveFrom/expectedVersion/reason/commandId/photoAction/photoData. 날짜 ISO, photoAction KEEP/REPLACE/CLEAR, photoData는 PNG/JPEG base64(원본 최대128KiB)다. 기본 form은 기존 값을 포함하며 충돌/오류 때 입력을 보존한다.
- GET/POST `/admin/employees/{id}/personnel` → `org/personnel`. 인사 관리자만 수정한다. 본인 헤더는 `/me/**`, 선택 사원 헤더는 `/admin/employees/{id}/**`에서 현재 권한으로 읽는다.
- 사진 GET `/me/profile/photo`, `/admin/employees/{id}/photo`: 본인 또는 인사 관리자만, no-store/nosniff, 다른 회사는404/접근 거부. 전사/결재 읽기에 생년월일·사진을 공통 노출하지 않는다.
- 사진은 이름 없는 file 입력을 읽어 숨김 문자열 photoData로 전송하여 기존 URL-encoded Form 계약을 유지한다. 서버는 크기/형식/픽셀 수 검사 후 JDK ImageIO로 재인코딩한다. 이미지 바이트·생년월일을 감사 요약에 넣지 않는다.

## 작업 1 — 업무 트리와 홈 제거

담당: 메뉴 agent. `frontend/src/navigation.ts`, `frontend/src/components/WorkspaceNavigation.tsx` 및 테스트를 새로 추가한다. 기존 App 수정은 통합 담당에게 맡긴다.

- [x] 역할별 재귀 필터·비어 있는 부모 제거·상세 활성 경로·26개 링크 보존 실패 테스트를 먼저 작성하고 `npm.cmd test -- src/navigation.test.ts src/components/WorkspaceNavigation.test.tsx`로 실패를 확인한다.
- [x] 승인된 5개 대표메뉴와 서브/하위메뉴를 구현한다. 실제 링크·aria-expanded·aria-current·모바일 닫기를 제공한다. `/me/requests` query 계약을 변경하지 않는다.
- [x] `TodayController`, `AttendanceController`, `SecurityConfiguration`, `DemoController`의 홈 기본 진입을 profile/attendance로 전환한다. 과거일에는 출퇴근 버튼을 제공하지 않고 오늘에는 기존 commandId/서버시각 기록을 사용한다.
- [x] 관련 Java 기대값과 회귀 테스트를 갱신하고 실행한다. 기존 홈 상태 fragment는 보존한다.

## 작업 2 — 인사 저장/조회/사진과 격리

담당: 인사 backend agent. 새 `EmployeePersonnelData/Mapper/Service/Controller`, V16 마이그레이션과 IT/단위 테스트를 소유한다. 공통 Workspace interceptor/React resolver의 해당 데이터 주입도 담당한다.

- [x] 본인/관리자 조회, 비관리자/다른 회사 차단, nullable 등록, 미래 생년월일, 상태 적용일/이력, 계정 active 독립, 버전 충돌/동일 명령 재시도, 사진 형식/크기/픽셀·CSRF 테스트를 먼저 작성하고 실패를 확인한다.
- [x] 별도 인사정보/상태 이력과 최소 grants/FORCE RLS를 추가한다. 기존 employee/일괄 계약을 깨지 않는다. 현재 권한 재확인과 기존 OrgCommands 잠금/감사/명령 재시도를 재사용한다.
- [x] 위 선행 인터페이스에 맞춰 URL-encoded 입력을 받고409/422 입력/명령을 보존한다. 상태는 당일 이후 적용일을 등록하고 같은 날짜 후속 변경도 이력으로 남긴다.
- [x] `mvnw.cmd ... -Dtest=EmployeePhotoTest -Dit.test=EmployeePersonnelIT verify`로 단위·실제 PostgreSQL 통합 검증을 수행한다. 병렬 Maven은 공유 target을 쓰므로 실행은 통합 담당과 조정한다.

## 작업 3 — 공통 헤더와 인사 등록 화면

담당: 통합 담당. `EmployeeHeader.tsx/.test.tsx`, `PersonnelPage.tsx/.test.tsx`, `App.tsx`, `IdentityPages.tsx`, `types.ts`, 공통 CSS.

- [x] 전체 인사정보·미등록·이미지 오류·본인/관리자 탭 경로와 인사 폼 CSRF/버전/사진/입력 보존 테스트를 먼저 작성해 실패를 확인한다.
- [x] EmployeeHeader는 표시만 담당한다. 실제 대상 employeeId로 관리자 탭을 만들고 본인 경로와 혼동하지 않는다. 회사 전체 목록에는 사원 헤더를 강제로 넣지 않는다.
- [x] 인사 등록 화면에 선택 필드·상태/적용일·사진·사유·저장을 제공하고 기존 사원 상세에서 진입할 수 있게 한다. 충돌 때 최신 버전 확인을 명시적으로 제공한다.
- [x] App에서 기존 평면 메뉴를 공통 메뉴로 교체하고 헤더·탭·브레드크럼·새 인사 페이지를 연결한다. 읽기 전용 내 정보의 중복 인사표를 정리한다.
- [x] `npm.cmd test -- src/components/EmployeeHeader.test.tsx src/pages/PersonnelPage.test.tsx src/App.test.tsx`와 타입 검사를 실행한다.

## 작업 4 — 신청 게시판과 단계 모달

담당: 신청 frontend agent. `AnnualRequestPage.tsx/.test.tsx`, 신규 `RequestBreakEditor.tsx/.test.tsx`와 전용 CSS를 소유한다. 기존 dirty 구현을 보존한다.

- [x] 유형 카드·단계 이동·미리보기 후 수정·409/422/503 보존·제출/이중제출·휴게 행 직렬화·닫기/초점 테스트를 먼저 작성하고 실패를 확인한다.
- [x] 유형 선택 → 내용 → 확인의 진행 표시와 모바일 신청 카드를 구현한다. 실제 서버 지원 유형과 기존 요청/응답·수치·권한 계약은 유지한다.
- [x] 휴게 입력은 시작/종료 행으로 편집하며 기존 서버 문자열로 직렬화한다. 잘못된 기존 입력은 조용히 삭제하지 않는다.
- [x] `npm.cmd test -- src/pages/AnnualRequestPage.test.tsx src/components/RequestBreakEditor.test.tsx`를 실행한다.

## 작업 5 — 통합·실제 검증·기록

- [x] 독립 변경을 통합한 뒤 React 전체 테스트·`npm.cmd run build`를 실행한다.
- [x] 프로젝트 JDK25에서 단위 및 Testcontainers 회귀를 실행한다. V15와 V16의 빈 DB/업그레이드·grants·RLS와 기존 자료 보존도 검증한다.
- [x] 별도 로컬 앱에서 합성 계정으로 인사 등록→본인 헤더→업무 이동→신청 미리보기 흐름을 확인한다. PC/390px에서 가로 넘침·트리·사진·모달·키보드/초점을 확인한다.
- [x] 명세/코드 검토에서 발견한 문제를 관련 회귀와 함께 수정한다. git status/diff/new files를 확인하고 실제 실행 결과·미실시를 품질 보고서에 기록한다.
- [x] 기존 미커밋 연간 신청/V15를 이번 UI의 선행 기능으로 보존하고 함께 검증했다. 무관한 공통 그리드 검토 문서는 포함하지 않는다.
- [ ] 공개본의 최신 시트·공유계정 보호를 독립 통합하고 정상 관리 연결에서 백업/복원·V16 반영·서비스 보존 검증을 수행한다. 현재 서버 SSH22 연결은 시간 초과이며, 로컬 완료와 공개 미반영을 구분한다.
