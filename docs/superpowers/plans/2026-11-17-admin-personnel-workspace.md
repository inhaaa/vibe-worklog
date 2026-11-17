# 담당자 인사기본 구현 계획

> Superpowers의 계획 실행·테스트 주도 개발·완료 검증 절차를 적용한다.

담당자가 일괄 시트와 개인 인사기본을 오가며 사원을 검색하고, 탭별로 기록을 저장·재조회·다운로드한다. 공개 권한관리 통합 및 검증 기록 `40a3a21`을 기반으로 기존 공개 보호도 함께 검증한다. 기존 Java·MyBatis·PostgreSQL·React·TypeScript와 XLSX 생성기를 사용한다. [승인 명세](../specs/2026-11-17-admin-personnel-workspace-design.md)

## 공통 계약

- 탭 ID는 `basic`, `appointments`, `family`, `education`, `qualification`, `language`, `contact`. 기본 탭은 `basic`이다.
- 목록 GET은 `/admin/employees/bulk-edit`, 검색 시작 GET은 `/admin/employees`, 후보 JSON GET은 `/admin/employees/personnel/search?q=…&page=…`, 선택 상세 GET은 `/admin/employees/{id}/personnel?tab=…`을 사용한다. 후보 응답은 `rows`, `page`, `hasNext`와 행별 `employeeId`, `employeeNo`, `employeeName`, `organizationName`, `status`다.
- 저장과 파일은 각각 `POST /admin/employees/{id}/personnel/tabs/{tab}`과 같은 경로의 `/export`로 연결한다. 저장은 `commandId`, `reason`, 원본별 낙관적 버전, 탭별 명시 필드를 보낸다. 저장 결과는 같은 사원·탭을 재조회하며 409와 422에 입력을 보존한다. 파일은 한 사원과 한 탭의 저장된 값으로 생성한다.
- 새 경로의 역할·테넌트·CSRF·공개 공유 계정 경계는 기존과 동일하다. 개인 탭 읽기 모델은 화면에 필요한 값만 태우고 기존 직원 내 정보에 편집기나 관리자 요청을 연결하지 않는다.

## 1. 인사정보 일괄관리

- [x] `OrgBulkWebIT`와 내비게이션·시트 테스트에 새 GET, 필터와 기존 POST, 상세 링크를 먼저 고정하고 구현 전 실패를 확인한다.
- [x] `OrgAdminController`, `OrgBulkController`, `OrgSheetPages`, `navigation.ts`에서 기존 시트를 새 경로로 이동한다. 기존 조회 주소의 필터를 인계하고 행 저장 후 시트로 돌아간다. 입사 예정 관리와 단건 사원 링크를 보존한다.
- [x] 시트·온보딩·Excel 회귀와 담당자 메뉴 상태를 확인한 뒤 검색 단계로 이동한다.

## 2. 인사기본 헤더와 검색

- [x] 담당자 검색과 선택 상세 테스트를 추가한다. 동명이인, 휴직·퇴사, 50행 이후, 테넌트와 역할 경계, 대상 사진·헤더·7개 탭 일치를 먼저 재현한다.
- [x] `OrgAdminController`, `EmployeePersonnelController`, `EmployeeService`, `ReactPageViewResolver`, `SecurityConfiguration`에 목록과 별도 검색 시작·검색 결과·탭 문맥을 연결한다.
- [x] `PersonnelPage`, `EmployeeHeader`, `PersonalDetails`, `App`에서 공유 헤더·탭의 읽기 화면을 사용해 선택과 사원 전환을 연결한다. 기존 직접 링크와 직원 내 정보 회귀를 확인한다.

## 3. 탭별 입력과 저장

- [x] 실 PostgreSQL의 기본사항 원자성·연락처 부분 변경·이력 추가·발령 날짜·버전 충돌·공개 보호 테스트와 화면의 미저장 이동·버전 충돌 테스트를 먼저 작성한다.
- [x] `EmployeeService`, `EmployeePersonnelService`, `EmployeePersonalDetailsService`의 기존 검증과 실제 저장을 재사용한다. 기본사항은 `OrgCommands`의 하나의 현재 담당자·테넌트 잠금, 명령 및 트랜잭션 안에서 변경 원본을 처리한다. 일곱 탭의 입력·저장·취소와 같은 탭 재조회를 연결한다.
- [x] 409에는 입력과 최신값을 보존하고, 동일 명령 재시도와 기존 발령·권한·직원 내 정보 회귀를 확인한다.

## 4. 탭별 Excel

- [x] 탭별 허용 열, 문자열 셀, 마스킹, 기존 자유 입력 처리, 다른 테넌트·공개 프로필 차단, 감사와 빈 파일 계약을 테스트한다.
- [x] 기존 Excel 생성과 다운로드 응답 패턴으로 선택 사원·탭 내보내기를 구현하고 탭에 다운로드를 연결한다. 저장된 값만 조회해 파일을 반환한다.

## 5. 통합과 PC·모바일

- [x] 전체 프런트엔드, TypeScript·Vite, Java 단위와 관련 PostgreSQL 통합을 실행한다.
- [x] 격리 합성 DB·최종 앱의 Chrome PC와 모바일 390px에서 사원 검색 → 탭 입력·저장 → 같은 사원 재조회 → XLSX 파일 열 확인을 실행한다. 화면의 미저장 전환과 동명이인, 기존 일괄 2행·입사 최초 발령을 검증한다.
- [x] 결과와 검증 범위를 이 계획에 기록하고 작업트리 diff와 링크를 점검한다.

## 자동 검증 결과

- 전체 프런트엔드 48개 파일의 테스트 571개와 TypeScript 검사, Vite 프로덕션 빌드가 통과했다.
- Java 단위 184개와 관련 PostgreSQL 통합 124개가 통과했다. 마지막 비활성 Excel 표기 보완과 UI 자산 갱신을 포함한 재패키징에서 단위 184개와 탭 저장·Excel 통합 11개를 재검증했다.
- 일괄 저장·입사 확정·공개 공유 사원 쓰기·내 정보 회귀를 확인했다. 마지막 실행 JAR에 최종 React 자산과 탭 관련 Java 클래스가 포함됐는지 바이트를 대조했다.

## 브라우저 검증 결과

- 격리 합성 DB에서 Chrome PC 1440px와 모바일 에뮬레이션 390px를 사용했다. 두 화면에서 동명이인 검색, 사원 전환, 일괄 시트 2행 저장과 필터 유지, 7개 탭 입력·저장과 재조회를 확인했다.
- 각 화면에서 XLSX 7개를 실제로 받아 총 14개 파일을 열었다. 탭별 열과 대상 사원, 최초·예정 소속 발령과 종료 경계, 가족 원문 제외, 연락처 마스킹, 문자열 셀을 대조했다.
- 가족 입력 중 탭 이동 취소, 일반 직원의 타 사원 인사기본 접근 차단, 로그아웃을 확인했다. 관측 화면 19개의 브라우저 예외와 페이지 가로 넘침은 없었다.
