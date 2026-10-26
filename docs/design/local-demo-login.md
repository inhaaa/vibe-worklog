# 회사코드 없는 로컬 데모 로그인

2026-10-26 사용자가 현재 로그인에서 회사코드를 제거하고 아이디/비밀번호를 `admin / admin`으로 고정하도록 요청했다. 현재 승인 범위는 로컬 개발이며, 아래 계약으로 적용한다.

## 활성화와 인증 계약

- `local`과 `local-demo` 프로필을 모두 명시한 경우에만 활성화한다. `local-demo` 단독은 시작을 거부한다. 기존 `local` 단독과 기본 프로필의 회사코드·이메일 인증 및 강한 비밀번호 정책은 유지한다.
- 데모 폼은 회사코드 입력을 출력하지 않고 텍스트 아이디와 비밀번호를 받는다. 기존 `email` HTTP 필드명과 CSRF·세션 교체·속도 제한을 유지한다.
- 서버가 `TNA-LOCAL-DEMO` / `admin@local.example.test`를 선택한다. 요청의 회사코드·tenant 값으로 다른 고객사를 선택하지 않는다. 입력 자격은 `admin / admin`이며 DB의 저장 해시도 정상 검증한다.
- `AuthStore`가 반환한 실제 계정·사원·테넌트·현재 역할로 principal을 만들고 기존 세션 현재성 검증과 RLS를 사용한다. 가짜 principal·인증 우회·서비스 운영자 권한은 추가하지 않는다.
- 데모 계정은 `EMPLOYEE`·`ATTENDANCE_ADMIN`만 가진다. 자기 승인 금지와 기존 역할 경계를 유지한다. 잘못된 자격·계정 부재·`admin`과 불일치하는 저장 해시·비활성·권한 회수는 실패한다. 같은 암호의 재해시는 로그인 검증을 통과할 수 있으나 CLI 재실행은 원래 해시의 감사 지문과 다르면 거부한다.

## 명시적 로컬 자료 준비

`local-demo-bootstrap`은 웹앱 시작과 분리한 CLI다. 두 프로필과 명시적 loopback JDBC만 허용하며 마이그레이션 역할 자격은 이 프로세스에서만 사용한다. 예약한 데모 tenant/employee/account UUID와 namespace만 생성하고 감사 이력을 남긴다. 재실행은 같은 자료를 확인하며 기존 해시·비활성 상태·권한을 덮어쓰지 않는다. 다른 고객사나 운영자 계정은 변경하지 않는다. 스키마 변경은 없다.

고정 UUID는 tenant `dddddddd-dddd-4ddd-8ddd-dddddddddddd`, employee `dddddddd-dddd-4ddd-8ddd-ddddddddddd1`, account `dddddddd-dddd-4ddd-8ddd-ddddddddddd2`다. 이 공개 자격은 합성 로컬 데모용이며 외부 배포/실고객에 사용하지 않는다. 기본 Compose는 loopback 게시와 두 프로필을 사용해 현재 개발 화면에 적용한다.

## 검증

프로필 AND·실패 폐쇄, 일반 정책 유지, 실제 DB 해시·활성/현재성·역할/RLS, 회사코드 변조, CSRF·속도 제한·세션 회수를 테스트한다. CLI는 idempotence·충돌/회수 상태 미복원·감사·다른 테넌트 보존을 확인한다. 실제 브라우저 형식과 HTTP 로그인 성공/실패·보호 화면·로그아웃을 점검한다. 최종 이미지는 새 SBOM·취약점 검사 대상으로 고정한다.
