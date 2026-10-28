# 공통코드·근태코드 관리 — 4단계 계약

> 2026-10-29 화면 공통화: 그룹·상세·근태코드 편집과 정책 이력은 [WorklogSheet](../superpowers/specs/2026-10-29-common-sheet-design.md)를 사용한다. 정렬·필터는 불러온 현재 페이지에만 적용하며 필터로 숨은 선택·변경 행도 일괄 저장·취소 범위에 포함한다. 시스템 보호·불변 코드·적용일·버전·명령·409/422/503 계약은 아래와 같다. [기술 결정](../adr/0005-common-worklog-sheet.md)에 따라 Community만 사용한다.

기준일: 2026-10-28, Asia/Seoul. 승인된 [업무 확장 설계](../superpowers/specs/2026-10-26-worklog-service-design.md#7-공통코드와-근태코드)의 React 구현 계약이다. 기존 신청·결재·원장·CALC-1은 유지한다.

## 경계와 화면

- 관리자 전용 `/admin/common-codes`와 `/admin/attendance-types`. 대표·공통·운영자 권한만으로 접근하지 못한다. tenant는 principal에서 결정한다.
- 공통 화면은 코드그룹 시트와 선택 그룹의 상세코드 시트를 분리한다. 각각 검색·50행 페이지와 조회/입력/삭제/저장/취소를 제공한다. 서로 다른 시트 저장은 별도 원자 명령이다.
- 근태코드 시트는 코드·이름·시스템유형·단위·유급구분·차감·근무인정·적용일·사용/신청 가능 여부·근거와 버전 이력을 표시한다. 코드와 시스템유형은 저장 뒤 불변이다. 기존 행 삭제는 물리 삭제가 아닌 비활성 후속 버전이다.
- 미저장 변경 보호, 변경분 1~50행, 오류 행 초점, 409/422 편집·삭제 예정·명령/행 ID 보존, 최신 원본 비교·명시 버전 확인은 기존 조직/결재선 시트와 같다. 미지원 신청의 사유를 화면에 표시한다.

## 스키마와 처리 계약

- 후속 Flyway V14: `common_code_group`, `common_code`, `attendance_type`, `attendance_type_version`. tenant 복합 PK/FK, 유일한 불변 코드, version, 주체/시각, FORCE RLS와 최소 권한을 둔다. 정책 버전 UPDATE/DELETE 권한은 부여하지 않는다.
- 공통 그룹: code/name/description/sortOrder/active/systemOwned/version. 상세코드는 groupId와 같은 필드이며 그룹 내 code 유일. 시스템 소유 그룹/코드는 편집·비활성 금지. 그룹 비활성은 상세 참조를 지우지 않고 그룹의 선택 가능 상태에만 영향을 준다. 이 코드는 표시·분류 자료이며 아직 소비하는 업무를 자동 변경하지 않는다.
- 근태 마스터: code/systemKind(NULL 또는 `ANNUAL_LEAVE`, `ATTENDANCE_CORRECTION`)/active/version. 버전: typeId/revision/effectiveFrom/enabled/name/unit/payKind/deductionRule/recognitionRule/basis/reason/createdBy/createdAt. 대상일 이하 가장 늦은 적용일, 같은 적용일은 가장 높은 revision을 선택한다. 종료일은 다음 적용일 전날이다.
- 신규 사용자 코드는 systemKind=NULL로만 생성하며 enabled=false 초안이다. 시스템 유형에 사용자 코드를 임의 매핑하지 못한다. 지원 capability는 서버 고정이다. 종일 연차는 DAY·정수·승인 차감, 정정은 기존 별도 정정 흐름만 지원한다. 유급 구분은 정책 메타데이터이며 급여/법정 계산이나 DAY→시간 변환을 추가하지 않는다. 근무인정은 산정 미지원으로 표시한다.
- 최초 시스템 정책은 기존 모든 tenant와 신규 온보딩에서 제공한다. 기존 신청은 확인 가능한 고정 종류에 해당하는 기초 버전 참조만 보강한다. 기존 업무 열·결정·효력·원장 값은 바꾸지 않는다. 취소는 원신청의 버전을 이어받는다.
- 새 제출은 대상일 기준 버전을 고정한다. 한 연차 기간의 시작·끝 사이에 다른 버전이 있으면 분리 신청422다. disabled 또는 미지원이면 새 신청422. 승인은 저장된 버전의 capability·입력 규칙을 재검증하고 이후 비활성/새 버전은 소급 적용하지 않는다. 기존 취소와 명시 재할당은 유지한다.
- 일반 편집 적용일은 서울 오늘 이후, 시스템 기초 버전만 과거 전체를 포괄한다. 미래 비활성은 해당 적용일의 대상 신청부터 효력을 가진다. 마스터 active를 현재/미래 버전 선택을 우회하는 전역 차단값으로 사용하지 않는다.

## HTTP·명령

- GET `/admin/common-codes?q&page&groupId&codeQ&codePage`: view `org/common-codes`, model `commonCodes`.
- POST `/admin/common-code-groups/bulk`, `/admin/common-codes/bulk`: CSRF form commandId/reason/rows와 위 조회 문맥.
- GET `/admin/attendance-types?q&page`: view `org/attendance-types`, model `attendanceTypes`.
- POST `/admin/attendance-types/bulk`: CSRF form commandId/reason/rows/q/page.
- 각 행은 clientRowId(UUID)/id?/expectedVersion?/operation(CREATE,UPDATE,DEACTIVATE)와 화면별 허용 필드다. JSON 중복 키·알 수 없는 필드/폼 파라미터·중복 행·부적합 타입은 거부한다. 전용 Row DTO의 필드명이 서버/React 계약이다.
- 기존 `OrgCommands.executeBatch`의 tenant 독점 잠금·현재 관리자·멱등성·행/루트 감사·전체 rollback을 재사용한다. 읽기 공급자는 공유 잠금과 반환 전 현재 관리자 확인을 유지하며 저장하지 않는다. 감사에는 안전한 ID/버전/상태/enum만 추가한다.

## 수용 검증

실제 PostgreSQL에서 관리자/대표/다른 tenant/RLS·컨텍스트 재사용, 50/51행·중복·전체 rollback·버전409·멱등성·권한 회수·시스템 코드 보호·불변 정책을 검증한다. 연차/정정/취소, 기존 이력, 미래/동일일 revision·기간 경계·비활성 후 기존 대기 승인도 검증한다. React 편집/취소/비활성/충돌/접근성·타입·빌드, 실제 HTTP·PC/모바일을 확인한다. 로컬 V14 전후 기존 자료/권한 보존·백업 복원을 기록한다. 공개 데모는 [승인된 후속 절차](../development/worklog-public-updates.md)를 따르고 실행 사실과 미실시 범위를 구분한다.
