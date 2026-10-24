# 계정 초대·역할·승인자 배정 구현 계약

2026-10-24 후속 요청에 따른 F02·F03의 다음 구현 단위다. [PRD](../prd.md), [보안](security.md), [승인 규칙](../rules/workflow.md), [논리 모델](data-model.md)을 따른다. 조직·사원 첫 단위는 `2ce527a`로 완료했다. 로컬 DB·설정·검증·커밋·일반 Push 승인 범위를 이어가며 외부 서버·배포·실제 메일 발송은 하지 않는다.

## 범위와 선택

기존 두 DataSource·OrgCommands·PasswordPolicy를 재사용한다. 새 인증 제품·메일 서비스 연동은 범위보다 크고, 화면에서 임시 비밀번호를 발급하는 안은 기존 보안 계약과 맞지 않는다. 초대 대기 계정과 일회성 수락, 즉시 역할 변경, 당일 승인자 관계를 구현한다. 비밀번호 재설정·최초 고객사 온보딩·신청 재할당·미래 역할/승인자 예약은 후속이다. F02·F03 전체 완료로 표시하지 않는다.

## 계정과 초대

- 고객사 담당자만 현재 재직 중인 활성 사원을 초대한다. 사원 등록과 계정 생성은 별개다. 회사 코드+고객사 내 정규화 이메일로 로그인하며, 이메일은 이번 단위에서 변경하지 않는다.
- 이메일은 바깥 공백 제거·Locale.ROOT 소문자, 최대254자, 내부 공백/제어문자 없이 `local@domain` 형태로 검증한다. 다른 고객사의 같은 이메일은 독립 계정이다.
- 최초 초대는 비활성·비밀번호 미설정 계정과 기본 EMPLOYEE 역할을 만든다. 기존 활성 계정이나 비밀번호가 설정된 비활성 계정은 재초대하지 않는다. 대기 계정의 같은 이메일 재발급은 기존 미소비 초대를 취소하고 새 초대를 발급한다.
- 토큰은 SecureRandom32바이트의 Base64url(43자)이며 SHA-256 해시만 DB에 저장한다. 목적 INVITE, 고객사·계정·이메일·발급 당시 auth_version에 결속하고24시간 후 만료한다. 만료 정각부터 거부한다. 토큰·해시·비밀번호는 명령 fingerprint·감사·일반 로그에 넣지 않는다.
- 전달은 `local` 프로필의 메모리 모의 메일함만 사용한다. 실제 수신자에게 전송하지 않고 웹 관리 화면에 원문 토큰을 노출하지 않는다. 테스트는 모의 전달 객체를 통해 수락을 검증한다. 기본 프로필에서는 전달이 준비되지 않아 발급을 거부한다. 파일럿 전 실제 전달 채널 검증이 필요하다.
- 공개 GET `/invite/{companyCode}`는 회사/계정 존재를 조회하지 않는 일반 폼이다. 수락 POST는 이메일·코드·비밀번호·확인값을 본문으로 받는다. 토큰을 URL·리퍼러에 넣지 않는다. 실패 시 비밀번호·토큰은 다시 렌더링하지 않는다.
- 수락 저장소는 서버에서 회사 코드를 해석하고 auth DataSource의 쓰기 트랜잭션 안에서 tenant 컨텍스트·같은 고객사 advisory lock을 적용한다. 현재 회사·재직·계정 대기·이메일·목적·만료·취소·미소비·auth_version을 확인하고 토큰 소비·비밀번호 설정·활성화·auth_version 증가·수락 감사를 함께 커밋한다. 인증 주체를 생성하거나 자동 로그인하지 않는다.
- 비밀번호12~128자와 기존 해시 정책을 재사용한다. 공개 실패는 일반화하고 기존 제한기를 별도 인스턴스로 재사용해 주소+회사+이메일별20회/5분을 제한한다. CSRF를 유지한다.

## 역할과 승인자

- EMPLOYEE는 기본 역할로 유지하며 APPROVER·ATTENDANCE_ADMIN만 선택적으로 부여/회수한다. 담당자가 자기 고객사의 수락 완료·활성·현재 재직 계정을 관리한다. 서비스 운영자 역할은 부여할 수 없다.
- 역할은 즉시 효력이다. 역할 행의 active를 갱신하고 변경 사유·전후 역할을 감사에 남긴다. 역할이 달라지면 auth_version을 증가시켜 기존 세션을 회수한다. 마지막 유효 담당자 제거는 기존의 다른 활성·현재 재직·퇴사 예정 없는 담당자 조건으로 차단한다.
- 승인자는 같은 고객사의 수락 완료·활성·현재 재직·APPROVER 계정이 필요하다. 대상 사원은 활성·현재 재직이면 계정 수락 전에도 배정할 수 있다. 자기 지정은 서비스·DB에서 차단한다.
- 적용일은 서울 기준 오늘만 지원하고 저장 시점부터 효력이 생긴다. 기존 날짜 구간 모델을 유지하면서 같은 날 즉시 교체·해제를 지원한다. 과거 시작 구간은 오늘을 종료일로 닫고, 오늘 생성한 이전 구간은 superseded_at으로 효력 종료를 표시해 원본을 보존한다. 현재 유효 구간은 중첩될 수 없다. 미래·과거 예약/정정은 후속이다.
- 승인자 권한·재직 회수를 막지 않는다. 기존 관계를 보존하고 조회 때 현재 적격성을 재검사해 '재지정 필요'를 표시한다. 새 배정은 신규 신청에만 사용한다. 기존 대기 신청의 자동 재할당·완료 결정 변경은 제공하지 않는다.
- 관리 명령은 기존 고객사 잠금·현재 권한·commandId 재시도·expectedVersion·감사를 재사용한다. 초대 발급·역할 변경·승인자 변경은 대상 employee.version도 증가시켜 조직/재직 변경과 오래된 화면 충돌을 검출한다.

## V3와 DB 최소 권한

V1/V2를 수정하지 않고 `V3__account_access.sql`을 추가한다.

| 대상 | 계약 |
| --- | --- |
| tenant_account | password_hash NULL 허용, 활성 계정은 해시 필수. credential_set 생성 열로 해시를 노출하지 않고 대기 여부 판단 |
| role_grant | active 기본true 추가. 과거 역할 행을 삭제하지 않고 현재 효력을 회수 |
| account_token | tenant/id·account 복합 FK, purpose·token_hash 유일·email·issued_auth_version·expires_at·used_at·revoked_at·created_at |
| approver_assignment | tenant/id·employee/approver/actor 복합 FK, 자기 지정 CHECK, valid_from/to·superseded_at·reason·version·생성/변경 메타데이터 |

새 테이블에 FORCE RLS와 명시 tenant 조건을 함께 적용한다. 앱은 계정의 식별/이메일/credential_set 최소 조회와 비밀번호를 제외한 계정 INSERT, auth_version 갱신만 허용한다. 토큰 해시는 INSERT만 허용하고 SELECT하지 않는다. 역할은 INSERT/UPDATE(active), 승인자 이력은 INSERT/허용 열 UPDATE만 허용한다. DELETE/TRUNCATE는 추가하지 않는다.

auth 역할은 기존 로그인 저장소의 read-only 트랜잭션을 유지한다. 별도 수락 저장소에 필요한 계정(password_hash,active,auth_version) 열 UPDATE·토큰 SELECT/used_at UPDATE·감사 INSERT만 추가한다. 감사 SELECT 및 조직/소속/승인자 업무 조회는 허용하지 않는다. 서로 다른 DataSource에서 계정 변경·감사를 분리 커밋하지 않는다.

## HTTP·검증

| 경로 | 처리 |
| --- | --- |
| GET `/admin/employees/{id}/access` | 계정 상태·역할·승인자 현재 적격성/이력·관리 폼 |
| POST `/admin/employees/{id}/invitations` | 최초 초대/같은 이메일 재발급 |
| POST `/admin/employees/{id}/roles` | 기본 EMPLOYEE와 선택 역할 변경 |
| POST `/admin/approver-assignments` | 오늘 승인자 지정/교체, 승인자 빈 값은 명시 해제 |
| GET `/invite/{companyCode}`, POST `/invite/{companyCode}/accept` | 일반 수락 폼, 원자적 수락 후 로그인303 |

관리 POST는 CSRF·commandId·사유·expectedVersion을 받고303/400/403/404/409/422의 기존 계약을 따른다. 공개 형식/초대 검증 실패는 안전한422, 과다 요청429, 저장소 장애503이다. 비밀번호·토큰 원문은 오류·모델·로그에 남기지 않는다.

검증은 실제 PostgreSQL에서 최소 권한·RLS·복합 FK·자기 지정/중첩 거부, 재발급·만료 정각·목적/이메일/테넌트 불일치·재사용·동시 수락·감사 실패 롤백, 역할 회수/복구 후 옛 세션·마지막 담당자 경합, 승인자 회수 후 당일 교체·해제를 포함한다. 기존81개 회귀와 Docker V2→V3·HTTP도 확인한다. 미구현 신청·계산·RESET의 검증 완료로 확대하지 않는다.
