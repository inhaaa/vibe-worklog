# 퇴근 누락·정정 반영 구현 계획

승인 기준은 [업무 명세](../specs/2026-11-18-checkout-correction-operations-design.md)다. 공개 인사기본 통합 버전 `ad9e960`의 분리 작업트리에서 진행한다.

## 1. 담당자 날짜별 조회

- [x] 퇴근 누락 판정과 정정 상태 전이, 집계 최신성, 날짜 검증·테넌트·역할 조건을 먼저 테스트한다.
- [x] `ProfileCompanyController`에서 기존 월별 화면과 담당자 전용 날짜별 조회를 분기한다. MyBatis로 원천 기록이 후보인 사원과 당시 소속을 좁힌 뒤 기존 정정·근무 집계의 현재값을 읽고 필터·페이지를 적용한다.
- [x] `company/attendance` 페이지는 `checkoutOperations` 모델을 받는다. 모델은 `date, latestDate, organizationId, options, q, status, page, hasPrevious, hasNext, rows, error`를 제공한다. 행에는 `employeeId, employeeNo, employeeName, organizationName, workDate, scheduledEnd, sourceIn, sourceOut, effectiveIn, effectiveOut, requestId, requestState, recordState, resultState, actualWorkMillis, calculatedAt, resultReason`을 포함한다. 집계 상태는 `WORK_CONFIRMATION_REQUIRED, RECALCULATION_REQUIRED, REVIEW_REQUIRED, CURRENT`를 사용한다. 모든 시각은 기존 서울 시간 표기를 사용한다.

## 2. 회사 화면과 업무 간 이동

- [x] React 테스트에서 월별 화면과 날짜별 목록, 상태 필터, 승인 이후 원천·유효 시각 분리, 모바일 상세와 이동 링크를 확인한다.
- [x] `IdentityPages`의 회사 근무현황에서 담당자에게 두 조회 탭을 제공한다. 담당자 상태와 집계 상태를 각각 표시하고 신청 상세와 해당 사원·월의 집계 행에 연결한다.
- [x] 상세에서 브라우저 뒤로 및 화면의 돌아가기 동작으로 근무일·소속·검색어·상태·페이지를 복원한다. 반환 URL을 받는 경우 같은 앱의 해당 화면 경로와 허용 조회 파라미터로 검증한다.

## 3. 직원 신청과 결재

- [x] 직원이 본인의 퇴근 누락일에 진입할 때 출근 유지·퇴근 입력 양식과 대기 신청이 기존 일반 정정 흐름을 보존하는지 테스트한다.
- [x] 기존 신청 상세에 원천·현재 유효·제안 시각을 표시하고 지정 결재·권한·요청 버전 규칙을 유지한다.
- [x] 실제 근로와 휴게 확인은 기존 필드와 서버 검증을 사용한다. 정정 승인과 재집계는 기존 쓰기 경로를 사용한다.

## 4. 검증

- [x] Java 테스트, React 테스트·타입·빌드, 변경 범위를 확인한다.
- [x] 분리 로컬 환경에서 가상 직원·담당자·지정 결재자로 PC와 모바일을 테스트한다. 누락 발견 → 직원 제출 → 승인 → 유효 퇴근 표기 → 재집계 → 8시간 확인을 각각 확인한다.
- [x] 퇴근 누락 필터·반려/철회·대체 요청, 직접 URL 권한, 이전 집계 숨김, 화면 복귀 조건을 검증하고 실행 결과를 남긴다.

## 실행 결과

- Java 단위 3개와 PostgreSQL 통합 30개가 통과했다. 승인 종일 연차 제외 단언과 현재 담당자 권한 확인을 추가한 뒤 관련 통합 14개를 다시 실행해 통과했다.
- React 전체 48파일 576개, TypeScript 검사, Vite 빌드와 Spring Boot 패키징이 통과했다.
- 격리 Chrome PC 1440px와 모바일 390px의 서로 다른 합성 근무일에서 누락 발견, 직원의 3개월 조회·신청, 독립 결재자의 승인, 담당자의 재집계를 확인했다. 원천 퇴근은 미기록으로 유지됐고 유효 퇴근 18:00, 재집계 후 확정 실근로 8:00:00을 확인했다.
- 집계 화면에서 목록으로 돌아와 선택한 날짜·사원 및 소속 필터로 최신 상태를 다시 확인했다. 대표는 기존 월별 화면을 열 수 있었고 퇴근 누락 전용 URL은 거부됐다.
- 검증은 합성 데이터가 있는 전용 로컬 환경에서 수행했다.
