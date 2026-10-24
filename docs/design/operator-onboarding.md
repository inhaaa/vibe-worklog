# 로컬 운영자 온보딩 계약

2026-10-24 MVP 연속 구현 위임 범위다. [PRD](../prd.md) F01·D11과 [보안](security.md), [승인 규칙](../rules/workflow.md)의 W05·W06을 따른다. 외부 서버·배포·실제 메일·실고객 자료는 사용하지 않는다.

## 최소 경로와 역할

UI-18 웹 계약을 유지해 별도 `/operator/login`과 `/operator/tenants`를 제공한다. 운영자 인증 주체·AuthenticationManager·SecurityContext 저장 키는 고객사 계정과 분리한다. `tna.operator.enabled`는 기본 false다. 활성화한 경우에만 별도 `tna_operator` DataSource를 사용하며 일반 앱·auth·마이그레이션 자격을 운영자 요청에 사용하지 않는다.

`tna_operator`는 NOSUPERUSER·NOBYPASSRLS·NOCREATEROLE·NOINHERIT이며 운영자 계정/운영 감사와 회사 최소 메타데이터만 조회한다. 사원·계정·출퇴근·휴가·신청·집계·보고 테이블의 SELECT/직접 CRUD는 없다. 운영자 로그인으로 고객사 세션이나 승인 권한을 만들지 않는다.

## 신규 회사와 최초 담당자

운영자는 회사 코드·명칭, 최초 사번·이름·업무 이메일, 회사 관계와 연락 경로 검토 근거·사유를 입력한다. 서버가 고객사/사원/계정/토큰 식별자와256bit 토큰을 생성한다. 신규 회사와 비밀번호 미설정·비활성 최초 계정, EMPLOYEE·ATTENDANCE_ADMIN 역할,24시간 INVITE 해시와 운영 감사를 한 트랜잭션으로 만든다. 최초 토큰의 발급 주체는 별도 operator_created_by로 연결하고 tenant 계정 발급 주체와 서로 배타적으로 보존한다. 활성화는 기존 초대 수락 경로에서만 수행한다. 공용 비밀번호·자동 로그인·공개 관리자 가입은 없다.

원문 토큰은 기존 local 모의 전달 객체에 커밋 후 한 번 전달하고 화면·로그·감사·URL에 포함하지 않는다. 같은 명령 재시도는 기존 결과를 반환하고 잘못된 새 토큰을 재전달하지 않는다. 같은 식별자의 다른 본문과 중복 회사 코드는 충돌이다. 기존 회사에 새 최초 관리자를 넣는 경로는 제공하지 않는다.

초기 초대 분실·만료·이용 재개 후에는 최초 TENANT_ONBOARD 감사에 결속된 바로 그 계정의 같은 이메일로만 재발급한다. 회사 이용 활성·현재 메타 버전·미수락/비밀번호 미설정·현재 재직을 검증한다. 계정·사원·역할을 추가하지 않으며 기존 미소비 INVITE를 취소하고 auth_version·회사 version을 증가시킨 뒤 새24시간 토큰·운영 감사를 원자적으로 남긴다. 수락한 계정과 변경된 대상은 거부하며 같은 명령 재시도는 새 토큰을 보내지 않는다.

## 이용 상태와 감사

운영자는 회사 이용 상태·현재 버전을 확인하고 사유와 낙관 버전으로 활성/중지를 변경한다. 실제 상태가 달라지면 해당 회사 계정의 auth_version도 증가시켜 중지 중 요청하지 않은 옛 세션까지 회수한다. 운영자는 고객사 비밀번호·근태 내용·승인을 읽거나 수정하지 않는다.

`platform.operator_audit_event`에는 실제 운영자 계정과 DB session_user, 회사 ID·행위·명령키·해시·관계 검토 근거·사유·최소 전후 상태만 남긴다. 이메일·사원 이름·초대 토큰/해시·비밀번호는 운영 감사 요약에 복제하지 않는다.

## 보안 부트스트랩 경계

최초 연결에는 운영자 업무 SELECT 권한을 넓히는 대신 신규 회사 생성과 상태 전이에 한정한 SECURITY DEFINER 브리지를 사용한다. 함수는 tna_migration 소유, 고정 pg_catalog search_path·완전 수식 테이블명·PUBLIC EXECUTE 철회를 적용한다. 호출자 DB 주체와 활성 운영자 계정, 명령 재시도·신규 회사/상태 버전을 검증한다. 사용자 SQL·동적 테이블·임의 tenant 권한·일반 업무 변경을 받지 않는다. 이 브리지는 보안상 최초 연결을 위한 권한 경계이며 근태 계산·휴가·승인 규칙을 DB에 구현하는 선택이 아니다. 업무 계산은 Java 서비스에 둔다.

운영자 최초 계정은 독립 로컬 CLI `operator-bootstrap <loginId>`에서 명시적으로 생성한다. JDBC는 loopback 주소만 허용하고 콘솔에서 신규 비밀번호·확인값을 직접 받는다. 자동 seed·고정 계정·기본 암호는 없고 운영자 비밀번호는 기존 PasswordPolicy로 해시한다. 초기 부트스트랩 관리 자격은 독립 CLI에서만 쓰며 웹앱에 전달하지 않는다. 실제 운영자 계정 생성·외부 고객 온보딩·메일 연결·운영 보안 개방은 별도 검토다.

## 검증

별도 로그인/세션·CSRF·속도 제한·권한/계정 회수, app/auth의 운영자 데이터/함수 접근 거부, 운영자의 HR SELECT·직접 쓰기 거부, 신규 연결·재시도·충돌·중간 실패 롤백·초대 수락, 이용 상태 변경의 세션 회수를 실제 PostgreSQL에서 검증한다. 기본 프로필의 비활성 상태도 기존 거부 정책으로 확인한다.

## 공식 근거

- [PostgreSQL18 CREATE FUNCTION](https://www.postgresql.org/docs/18/sql-createfunction.html): SECURITY DEFINER의 안전한 search_path와 PUBLIC 실행 권한 철회.
- [PostgreSQL18 Row Security](https://www.postgresql.org/docs/18/ddl-rowsecurity.html): FORCE RLS·소유자·우회 역할·정책 결합의 경계. 전용 함수 소유자도 기존 FORCE RLS 정책을 명시적으로 충족한다.
