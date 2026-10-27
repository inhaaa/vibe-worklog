# WorkLog Hostinger 공개 데모 배포 계획

> **실행:** subagent-driven-development로 독립 파일 작업을 병렬 진행하고 배포 전 통합 검증한다.

**목표:** 기존 PriceSys·PriceSys 데모·포트폴리오를 보존하면서 `https://worklog.inhaaa.com`에 가상 자료 전용 WorkLog를 배포한다.

**구조:** 확정 커밋 `8fe09e5`에서 별도 작업트리를 사용한다. `/srv/worklog`의 독립 Compose 프로젝트 `worklog-demo`, 전용 DB·볼륨·네트워크와 loopback 앱 포트를 사용하며 기존 Caddy에는 새 호스트 블록만 추가한다. 웹앱은 app/auth 최소 DB 자격만 받고 마이그레이션·자료 준비는 별도 일회성 프로세스에서 실행한다.

**기술:** 기존 Java25·Spring Boot·React·no-XML PostgreSQL 이미지, Docker Compose, 호스트 Caddy. 새 라이브러리는 추가하지 않는다.

## 승인·데모 계약

2026-10-27 사용자가 서버 접속, DNS·설정 변경, 전용 DB 생성·배포를 명시 승인했다. 모든 방문자는 같은 합성 데모 자료를 체험한다. 실제 개인정보를 입력하지 않도록 소개에 안내하고 기존 로컬 DB·다른 서비스 DB를 복사하지 않는다. 공개 데모 인증은 예약한 합성 tenant/account에만 연결하며 정상 DB 해시·역할·RLS·CSRF·세션 검증을 보존한다. `public-demo`는 `local`·`local-demo`·운영자 활성화와 함께 사용하지 않는다. 초대·복구의 실제 전달은 제공하지 않는다.

## 작업과 검증

- [x] README·kickoff·PRD·현재 보안/데모 계약과 기존 PriceSys 배포 문서를 읽고 메뉴 작업을 별도 작업트리로 보존한다.
- [x] Hostinger에서 현재 CPU/메모리/디스크·포트·Caddy bind와 기존 사이트/컨테이너 상태를 기록한다.
- [x] 공개 전용 프로필 테스트를 먼저 실패시킨 뒤 정확한 합성 인증·혼용 거부·Secure 쿠키·운영자 차단을 구현한다. 로컬 프로필의 기존 동작은 유지한다.
- [x] Compose 계약 테스트를 먼저 작성하고 전용 project/volume/network, loopback 앱·비공개 DB, 최소 권한·자원 제한·로그 제한·일회성 migrate/bootstrap을 구현한다.
- [x] 합성 자료 준비는 빈 전용 DB에서만 수행하고 기본 데모 계정의 무효화 가능성을 차단한다. 공유 데모 및 초기화 가능성을 소개에 표시한다.
- [ ] Caddy가 외부 전달 헤더를 정규화하고 앱은 지정한 프록시만 신뢰하도록 구성한다. HTTPS·쿠키·위조 XFF·직접 백엔드 경계를 실제 HTTP로 검증한다.
- [ ] 관련 Java·프론트·Python 테스트와 새 이미지 빌드를 통과시키고 별도 로컬 데모 DB에서 로그인·조직·근태·Excel을 점검한다.
- [ ] `worklog` DNS를 기존 VPS로 연결하고 후보 Caddy 설정 validate/diff 후 새 블록만 reload한다. 기존 서비스의 상태·HTTP 기준을 전후 대조한다.
- [ ] 공개 HTTPS 로그인·로그아웃·기본 화면·보호 경로·DB 비공개를 검증하고 실제 배포 커밋/이미지·미수행 검증을 보고한다.

## 보존 및 실패 처리

`/srv/pricesys`, `/srv/pricesys-demo`, `/srv/inhaa-portfolio`, 기존 컨테이너·볼륨·포트를 변경하지 않는다. 기존 Caddy 전체 백업과 후보 diff를 확인한다. 실패하면 WorkLog 앱과 새 호스트 블록만 이전 상태로 되돌리고 전용 DB 볼륨은 보존한다. 강제 Push·reset·rebase·전체 Docker 정리·기존 데이터 삭제는 실행하지 않는다. 서버 접근 방법은 Hostinger 관리 화면이며 비밀번호·OTP·DB 비밀값을 채팅·문서·Git에 기록하지 않는다.
