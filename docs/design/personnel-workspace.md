# 업무별 메뉴·공통 사원 헤더·인사정보 등록

기준일: 2026-11-02, Asia/Seoul. 사용자가 [화면 개편 설계](../superpowers/specs/2026-11-01-workspace-navigation-employee-header-design.md)의 인사정보 등록까지 포함한 구현·테스트를 승인했다. [실행 계획](../superpowers/plans/2026-11-02-workspace-personnel-ui.md)과 실제 [검증 보고서](../quality/personnel-workspace.md)를 구분한다.

## 업무 메뉴와 진입

대표메뉴는 내 업무·결재·인사/조직·근태 운영·시스템 설정이다. 그 아래 업무 묶음, 실제 화면의 3단계로 제공한다. 26개 링크를 역할 합집합으로 필터링하고 접근 가능한 하위메뉴가 없는 부모는 숨긴다. 메뉴 숨김으로 서버 인가를 대체하지 않는다.

현재 화면의 조상을 펼치고 상세에서도 해당 업무를 선택한다. 신청·계산 상세는 진입 목록 문맥을 허용 경로와 현재 역할 안에서 유지한다. 브라우저 뒤로/앞으로 가기에도 진입 문맥을 복원한다. 새로고침의 신청 문맥은 서버가 제공한 ownRequest/canDecide/admin/status를 사용한다. URL에 공통 tab 파라미터를 추가하지 않는다.

홈 메뉴를 제거하고 로그인·브랜드 링크의 기본 목적지는 `/me/profile`로 바꿨다. 기존 `/me/today`는 `/me/attendance`로 안내하며 상태 fragment 경로는 보존한다. 출퇴근 POST도 출퇴근 화면으로 복귀한다. 서울 오늘 조회에만 출퇴근 명령을 제공하며 과거 날짜의 근로/휴게 확인은 선택 근무일과 당시 입력 개정에 연결한다.

## 사원 헤더와 권한

EmployeeHeader는 이름·사진 또는 이니셜·회사·소속·사번·생년월일·직급·직책·직무·입사일·인사 상태를 표시한다. 미등록 값과 미확인 상태를 추정해 채우지 않는다. 본인 `/me/**`와 관리자 `/admin/employees/{id}/**`에는 각각 권한을 확인한 대상의 employeeHeader를 제공한다. 상단 로그인 사용자와 선택 사원을 구분한다.

헤더 아래 본인 탭은 내 정보·출퇴근 기록·근무 일정·휴가/신청·근로시간이다. 관리자 사원 탭은 인사정보·기본정보/소속·근무 배정·계정/권한이다. 전체 회사 목록이나 결재자의 일반 조회에 생년월일·사진 헤더를 공통 노출하지 않는다.

EmployeeHeader의 입력은 employeeId/employeeNo/employeeName/companyName/organizationName/joinedOn/leftOn/birthDate/gradeName/positionName/jobName/employmentStatus/photoUrl/asOf다. asOf는 Instant이고 날짜 표시는 서울 기준이다. 표시 컴포넌트는 자체 조회·저장·권한 판단을 하지 않는다.

## 인사정보 저장 계약

V16은 기존 employee·계정·일괄 수정 계약을 변경하지 않고 아래 테이블을 추가한다.

| 테이블 | 계약 |
| --- | --- |
| employee_personnel | tenant/employee 복합 PK, 선택 생년월일·직급/직책/직무 명칭·사진, 별도 version·변경 주체/시각 |
| employee_employment_status | tenant/id PK, employee·revision 유일, 인사 상태·적용일·사유·처리자/시각의 추가 전용 이력 |

직급·직책·직무는 각각 선택 입력의 120자 이내 명칭이다. 이번 범위에서 공통코드 그룹·호봉/급여 체계를 새로 만들지 않는다. 생년월일은 1~9999년의 오늘 이하 날짜이며 미래 생일은 거부한다. 두 테이블에 tenant SQL·복합 FK·FORCE RLS를 적용하고 앱에 필요한 SELECT/INSERT 및 현재 인사 행의 허용 열 UPDATE만 부여한다. DELETE와 상태 이력 UPDATE는 부여하지 않는다.

상태 등록 값은 WORKING/ON_LEAVE/SUSPENDED/UNKNOWN이고 적용일은 서울 오늘 이후다. 같은 적용일의 후속 개정도 삭제하지 않고 보존하며, 조회일 이하의 가장 늦은 적용일·높은 revision이 현재 상태다. 입사 전/퇴사 후는 기존 입퇴사일로 PRE_JOINING/LEFT를 표시한다. 인사 표시 상태를 계정 active·결재 자격·근로시간 계산 변경으로 확대하지 않는다.

| HTTP | 계약 |
| --- | --- |
| GET/POST `/admin/employees/{id}/personnel` | 현재 인사 관리자만 조회/등록. `org/personnel` 페이지 |
| GET `/me/profile/photo` | 로그인 본인 사진만 |
| GET `/admin/employees/{id}/photo` | 현재 인사 관리자만 같은 회사 대상 사진 |

수정 입력은 birthDate/gradeName/positionName/jobName/employmentStatus/effectiveFrom/expectedVersion/reason/commandId/photoAction/photoData다. 최초 version은 0, 저장할 때마다 별도 인사 version을 증가시키며 employee.version과 계정 auth_version은 건드리지 않는다. 기존 OrgCommands의 고객사 독점 잠금·현재 관리자·명령 해시/멱등성·감사를 재사용한다. 읽기는 공유 잠금·반환 전 현재 권한 재확인을 유지한다.

성공303으로 재조회한다. 409/422는 제출한 personnel.form과 최신 personnel.currentForm/version을 함께 보내며 입력·사진 문자열·명령을 보존한다. currentForm에는 사진 원문을 넣지 않는다. 충돌 때 최신 값을 확인한 후 명시적으로 버전을 갱신해 재시도한다. 503 등 결과 미확정에는 기존 동일 명령/입력을 유지한다. 사유는 1~500자다. 일반 감사 JSON에는 생년월일·이미지 원문을 기록하지 않고 등록 유무·버전·상태만 남긴다.

## 사진

전역 multipart 비활성화와 공통 Form의 파일 거부는 유지한다. 이름 없는 file 입력에서 선택한 PNG/JPEG를 읽어 URL-encoded photoData 문자열로 보낸다. PNG/JPEG data URI 또는 순수 base64만 받고 photoAction은 KEEP/REPLACE/CLEAR다. 원본과 재인코딩 결과는 각각 최대128KiB이며 가로/세로2048px·400만 픽셀을 넘지 않는다. 서버가 형식·크기·이미지 헤더를 검사하고 실제 디코딩 후 JDK ImageIO로 다시 인코딩해 전용 인사 행의 bytea에 저장한다.

사진은 인증된 경로에서 no-store/nosniff와 정규화된 Content-Type으로 제공한다. photoUrl에 `?v=인사버전`을 붙여 저장 직후 같은 사원의 사진 교체와 과거 로드 실패 이후 재시도를 반영한다. 사진이 없거나 조회에 실패하면 이니셜을 표시한다. 외부 이미지 URL·SVG·파일시스템 경로 입력은 받지 않는다.

## 신청 화면

사원 헤더·업무 탭 아래 연간 요약·유형/상태 조회·신청 버튼·신청 게시판을 표시한다. 현재 페이지 검색은 불러온 최대50건에만 적용되며 서버 필터·연간 수치를 변경하지 않는다. 모바일은 신청 한 건을 항목명이 있는 카드로 표시한다. 기존 DTO에 없는 신청일을 임의로 생성하지 않는다.

모달은 유형 선택 → 내용 작성 → 제출 전 확인이다. 연차·출퇴근/휴게 정정만 지원한다. 유형별 조회 날짜를 보존하되 미래 연차에서 정정으로 바꿀 때 최초 정정 날짜는 오늘 이하로 제안한다. 새 유형의 근거 조회가 끝나면 내용 단계로 진입한다. 실제 휴게는 시작/종료 행을 기존 서버 문자열로 직렬화하며 잘못된 기존 원문은 조용히 버리지 않고 수정 안내로 보존한다.

기존 미리보기 무쓰기·최종 재검증·명령 중복 방지·409/422/503 보존·미저장 확인·Escape·초점 복귀·일반 PAGE 작성 경로를 유지한다. 휴가 일수·현재 잔여·선택 연도의 귀속 잔액·승인/취소/정정의 효력 규칙은 [연간 신청 계약](annual-requests.md)을 따른다. 새 휴가 유형·자동 연차 발생은 포함하지 않는다.

공개 반영은 개발본 전체 덮어쓰기로 수행하지 않으며 [후속 배포 절차](../development/worklog-public-updates.md)의 최신 공개 그리드·공유 계정 보호·DB 보존을 통합한 별도 검증이 필요하다.
