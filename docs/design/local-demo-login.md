# 회사코드 없는 로컬 데모 로그인

2026-10-26 사용자가 회사코드를 제거하고 아이디/비밀번호를 `admin / admin`으로 고정하도록 요청해 로컬 데모 인증을 적용했다. 이후 A안 소개·홈·일정·직원 목록과 입력 없는 데모 진입을 승인하여 소개 버튼을 기존 정상 로그인에 연결했다. 승인 범위는 로컬 개발이며, 화면 구현과 실제 검증 결과는 구분한다. 후속 검증 기록은 [워크스페이스 UI 품질 보고](../quality/workspace-ui.md)를 따른다.

## 활성화와 인증 계약

- `local`과 `local-demo` 프로필을 모두 명시한 경우에만 활성화한다. `local-demo` 단독은 시작을 거부한다. 기존 `local` 단독과 기본 프로필의 회사코드·이메일 인증 및 강한 비밀번호 정책은 유지한다.
- 데모 소개의 **데모로 입장** 버튼은 입력 화면 없이 기존 POST `/login`을 제출한다. 공개 합성 자격 `admin / admin`을 `email`·`password` hidden 필드로 전달하고 Thymeleaf의 CSRF 토큰을 포함한다. GET 소개 열람만으로 인증하지 않으며 기존 세션 교체·속도 제한을 유지한다.
- 서버가 `TNA-LOCAL-DEMO` / `admin@local.example.test`를 선택한다. 요청의 회사코드·tenant 값으로 다른 고객사를 선택하지 않는다. 입력 자격은 `admin / admin`이며 DB의 저장 해시도 정상 검증한다.
- `AuthStore`가 반환한 실제 계정·사원·테넌트·현재 역할로 principal을 만들고 기존 세션 현재성 검증과 RLS를 사용한다. 가짜 principal·인증 우회·서비스 운영자 권한은 추가하지 않는다.
- 데모 계정은 아래 고정 tenant/account/employee UUID와 일치하고 역할 집합이 정확히 `EMPLOYEE`·`ATTENDANCE_ADMIN`이어야 한다. 자기 승인 금지와 기존 역할 경계를 유지한다. 잘못된 자격·계정 부재·`admin`과 불일치하는 저장 해시·비활성·부분 역할 회수·예상 밖 역할 추가는 실패한다. 같은 암호의 재해시는 로그인 검증을 통과할 수 있으나 CLI 재실행은 원래 해시의 감사 지문과 다르면 거부한다.

## 소개와 화면 연결

| 경로 | 두 프로필의 로컬 데모 | 기본·`local` 단독 |
| --- | --- | --- |
| GET `/` | 공개 소개 `/demo`로 안내 | 기존 인증된 본인 화면 연결 유지 |
| GET `/login` | `/demo`로 안내, `error`·`logout` 존재 플래그만 전달 | 기존 회사코드·이메일·강한 비밀번호 로그인 폼 |
| GET `/demo` | 소개·합성 데모 표시·입장 POST 폼 | 소개 경로 비공개 |
| POST `/login` | hidden 합성 자격을 기존 provider·DB 해시·현재 권한으로 검증, 성공 `/me/today` | 기존 일반 인증 |

인증 entrypoint·실패·로그아웃 목적지는 기존 `/login`을 유지하고 소개로 안내할 때 일반화된 실패/로그아웃 알림을 보존한다. 요청의 tenant·임의 redirect 값은 소개 URL에 전달하지 않는다. 공통 shell은 실제 principal 역할로 메뉴를 표시하고 서버 권한 검증을 함께 유지한다. 개인 달력과 직원 목록의 조회·필터 계약은 [화면 설계](screens.md)를 따른다. 최초 텍스트 `admin / admin` 입력 폼은 후속 소개 버튼 흐름으로 전환한 이력으로 남긴다.

## 명시적 로컬 자료 준비

`local-demo-bootstrap`은 웹앱 시작과 분리한 CLI다. 두 프로필과 명시적 loopback JDBC만 허용하며 마이그레이션 역할 자격은 이 프로세스에서만 사용한다. 예약한 데모 tenant/employee/account UUID와 namespace만 생성하고 감사 이력을 남긴다. 재실행은 같은 자료를 확인하며 기존 해시·비활성 상태·권한을 덮어쓰지 않는다. 다른 고객사나 운영자 계정은 변경하지 않는다. 스키마 변경은 없다.

고정 UUID는 tenant `dddddddd-dddd-4ddd-8ddd-dddddddddddd`, employee `dddddddd-dddd-4ddd-8ddd-ddddddddddd1`, account `dddddddd-dddd-4ddd-8ddd-ddddddddddd2`다. 이 공개 자격은 합성 로컬 데모용이며 외부 배포/실고객에 사용하지 않는다. 기본 Compose는 loopback 게시와 두 프로필을 사용해 현재 개발 화면에 적용한다.

## 검증

프로필 AND·실패 폐쇄, 일반 정책 유지, 소개 GET의 미인증 상태·기존 CSRF POST, 실제 DB 해시·활성/현재성·역할/RLS, 회사코드 변조, 속도 제한·세션 회수를 테스트한다. CLI는 idempotence·충돌/회수 상태 미복원·감사·다른 테넌트 보존을 확인한다. 실제 브라우저·HTTP 진입 성공/실패·보호 화면·로그아웃과 최종 이미지의 실행 범위는 [품질 보고](../quality/workspace-ui.md)에 실제 결과대로 기록한다. 이 설계 문서에 아직 확정되지 않은 통과 수·스크린샷을 기록하지 않는다. 새 이미지의 보안 상태를 이전 이미지의 스캔만으로 확정하지 않는다.
