# 휴가 종류별 신청 현황 구현 계획

**목표** [휴가 운영 명세](../specs/2026-11-18-leave-operations-design.md)에 따라 담당자가 휴가와 취소 신청을 찾고, 같은 조건으로 처리 이력과 현재 효력을 확인한다.

**구조** 공개 검증 기록 `2649681`에서 분리했다. 기존 신청·결재 상세와 V18 데이터를 사용한다. 휴가 목록은 담당자 전용 조회 모델을 사용하고 정정 목록은 기존 경로를 사용한다.

## 1. 담당자 조회

- [x] PostgreSQL/MockMvc 테스트에서 휴가·정정 경로 분리, 종류·기간·소속·사번 필터, 상태별 건수·정렬·50행, 나중에 비활성된 코드·기존 명칭, 취소 효력과 권한 격리를 먼저 확인한다.
- [x] `RequestController`, `ReactPageViewResolver`, 요청 조회 모델·매퍼에서 담당자 휴가 목록을 연결한다. 유효하지 않은 검색은 해당 화면에서 오류를 표시한다. 기존 통합 경로는 휴가 목록으로 이동한다.
- [x] `request/list`의 `leaveOperations` JSON은 `status, leaveTypeCode, requestKind, from, to, organizationId, q, page, hasNext, asOf, leaveTypes, organizations, counts, rows, error`를 사용한다. `leaveTypes`는 `code, name`, `organizations`는 `id, code, name`, `counts`는 `PENDING, APPROVED, REJECTED, ALL` 키다. 행은 `id, employeeNo, employeeName, organizationName, leaveTypeName, requestKind, from, to, quantityDays, status, effectStatus, approverName, decidedByName, submittedAt, decidedAt, originalRequestId`다.

## 2. 목록과 상세

- [x] React 테스트에서 대기 기본값, 검색 후 상태·페이지 이동 URL, 휴가 취소 효력, 상세·원신청·복귀 링크를 확인한다.
- [x] PC와 모바일 목록에 상태·효력과 접수·처리 정보를 제공한다. 상세의 복귀 링크와 PC·모바일 브라우저 뒤로에서 검색 조건을 복원하고 목록을 다시 조회한다.
- [x] 기존 열람·승인·취소 권한과 출퇴근 정정 화면, 대표·직원·결재자의 기존 경로를 회귀 검증한다.

## 3. 격리 검증

- [x] 관련 Java 테스트, 전체 React 테스트, TypeScript·Vite 빌드를 실행한다.
- [x] 별도 합성 DB의 Chrome PC 및 390px에서 담당자의 필터 적용 → 대기·승인·반려 상세 왕복 → 지정 결재자의 실제 취소 승인 → 원신청의 승인·취소됨과 취소 신청의 승인 상태를 검증한다.
- [x] 실행한 검증과 작업트리 변경 범위를 확인한다.

## 실행 결과

- Java 단위 3개와 PostgreSQL 통합 36개를 통과했다. 신규 통합 4개는 상태별 건수와 정렬, 기간 경계, 50·51건 페이지, 취소 효력, 담당자·테넌트 경계 및 상세 복귀 주소를 검증했다. 처음 관측된 MyBatis 프록시 접근 오류와 조회 오류 처리 누락을 수정한 뒤 재실행했다.
- 프런트엔드 전체 50개 파일 588개 테스트와 TypeScript 검사, Vite 빌드, 앱 JAR 패키징이 통과했다.
- 격리 합성 DB의 Chrome PC 1440px와 모바일 390px에서 휴가·정정 분리, 휴가 종류·신청 구분·기간·소속·사번 필터, 대기·승인·반려 상세 왕복과 2페이지 복귀를 확인했다. 모바일 마지막 결과는 같은 저장 건을 다시 읽어 확인했다.
- 두 환경에서 지정 결재자가 실제 취소 승인했다. 원신청 승인 이력과 취소됨 효력, 별도 취소 신청 승인, 원신청 연결과 잔여 4→5일을 확인했다. 각 원장의 원차감에 연결된 복원 행과 취소 승인 감사 기록은 각각 1건이었다.
