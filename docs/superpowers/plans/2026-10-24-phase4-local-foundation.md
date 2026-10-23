# Phase 4 로컬 개발 기반 구현 계획

> 실행 방식: `superpowers:subagent-driven-development`와 TDD를 사용한다. 기존 PRD·설계 및 사용자의 Phase 4 착수·로컬 DB/Docker·커밋/Push 승인을 따른다.

**목표:** Java 25·Maven Wrapper·Spring Boot·실제 PostgreSQL·Flyway·MyBatis·보안 로그인·서버 렌더링의 최소 개발 기반을 검증한다.

**구조:** 기존 모듈형 모놀리스를 유지한다. 인증 전용 조회 역할과 일반 앱 역할은 같은 PostgreSQL의 별도 자격을 사용하며, 마이그레이션은 웹 프로세스 밖에서 실행한다. 업무 범위는 로그인과 본인 준비 화면까지다.

**기술:** ADR-0001의 고정 버전과 공식 자료 재검증을 우선한다. 최초 기반 구축에서는 기존 PC·서비스의 전역 Java·WSL·Docker 설정을 바꾸지 않았다. 후속 컨테이너 검증의 설치 승인 범위는 아래 별도 절을 따른다.

## 작업 1. 기술 확인과 실행 계약

- [x] 공식 릴리스·아티팩트·라이선스 및 현재 PC의 런타임을 확인한다.
- [x] ADR-0001에 Temurin Windows 실제 배포 버전, Maven/Wrapper, BOM·Thymeleaf·htmx와 라이선스 근거를 추가한다.
- [x] 공개 의존성 다운로드와 GitHub Push를 제외한 외부 서버·배포는 사용하지 않는다.
- [x] 승인된 범위를 AGENTS.md에 반영하고 문서를 검증·커밋한다.

## 작업 2. 빌드 골격

- [x] `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `TnaApplication.java`, `application.yml`을 생성한다.
- [x] Java 25 컴파일·JUnit 6·Boot MVC/Thymeleaf·Security·Actuator·MyBatis·Flyway PostgreSQL 모듈을 의존성 트리로 확인한다.
- [x] `mvnw.cmd test`, `mvnw.cmd package`로 빌드를 검증한다. Docker가 필요한 검증은 성공으로 간주하지 않는다.
- [x] 전이 의존성의 라이선스 목록과 실제 관리 버전을 기록하고 검증 후 커밋한다.

## 작업 3. PostgreSQL·테넌트 기반

- [x] SQL 전에 `DatabaseFoundationIT`에 역할 권한·RLS·복합 FK·풀 재사용·롤백 기대값을 작성하고 실패를 확인한다.
- [x] `V1__identity_foundation.sql`에 최소 고객사 레지스트리·사원·계정·역할과 RLS를 정의한다. 근태·휴가·승인 테이블은 만들지 않는다.
- [x] 부트스트랩은 `tna_migration`, `tna_app`, `tna_auth` 역할·스키마 권한만 준비한다. Flyway는 독립 Maven goal로 실행하고 앱에는 관리 자격을 주지 않는다.
- [x] 인증 전 조회는 회사 코드로 최소 레지스트리를 조회한 뒤 인증 전용 트랜잭션에서 tenant 컨텍스트를 설정한다. 일반 업무 저장소와 타입·호출 경로를 공유하지 않는다.
- [x] 일반 앱은 인증 principal에서 얻은 tenant만 사용한다. `set_config('app.tenant_id', ?, true)`와 명시 SQL 조건을 같은 트랜잭션에 둔다.
- [x] Docker가 없으면 별도 portable PostgreSQL로 실제 DB 검증을 진행하고, Compose·Testcontainers 미실행을 별도로 기록한다.
- [x] DB 검증 후 관련 변경만 커밋한다.

## 작업 4. 로그인·기본 화면

- [x] `SecurityWebIT`·`PasswordPolicyTest`에 미인증·CSRF·회사별 로그인·긴 암호·세션 회수·운영자 차단·htmx·캐시 기대값을 먼저 작성한다.
- [x] `/login`은 회사 코드·이메일·비밀번호 폼이며 실패 이유로 고객사·계정 존재를 노출하지 않는다.
- [x] PBKDF2 기반 해시와 12~128자 검증, 유휴 30분·절대 12시간 세션을 구현한다. 저장된 해시는 세션 principal에 포함하지 않는다.
- [x] 매 요청에서 고객사·사원·계정 상태·권한 개정을 확인하고 실패하면 세션을 폐기한다.
- [x] `/me/today`는 본인의 이름·고객사·구축 상태만 표시한다. 출퇴근·신청 기능은 구현하지 않는다.
- [x] Thymeleaf·로컬 htmx 자산으로 전체/부분 응답을 검증한다. 개인정보 응답은 no-store이며 `/operator/**`는 차단한다.
- [x] 테스트·리뷰 후 관련 변경만 커밋한다.

## 작업 5. 로컬 실행과 완료 검증

- [x] `Dockerfile`, `compose.yaml`, `.env.example`, 로컬 초기화·마이그레이션 안내를 작성한다. 포트는 loopback에만 게시하고 기존 컨테이너를 조작하지 않는다.
- [x] 고정 이미지·비밀정보 외부 주입·DB 헬스체크·독립 migration 실행을 검토한다. 실제 Docker daemon이 없으면 실행 결과를 만들어내지 않는다.
- [x] README·개발 안내·ADR에 실제 실행 명령·테스트 결과·미검증 항목을 반영한다.
- [x] `mvnw.cmd verify`, 실제 PostgreSQL 통합 검증, 패키지 기동·HTTP 확인, `git diff --check`, 비밀정보·변경 범위 검토를 수행한다.
- [x] 각 완성 단위를 일반 커밋·Push하고, 원래 작업 디렉터리에 fast-forward 반영한 뒤 로컬/원격 SHA와 깨끗한 Git 상태를 확인한다.

## 완료 경계

Phase 5 근태 계산·출퇴근·휴가·승인·Excel 기능, 실제 고객 자료·계정 초대 전송, 원격 서버 접속·배포는 이번 범위에서 실행하지 않는다. Docker 호스트 설치·WSL 기능 변경·재부팅이 필요하면 해당 환경 제약을 별도로 보고한다.

## 후속 작업: Docker 실행 검증

2026-10-24 사용자가 필요한 Docker·WSL 설치와 환경 설정, 이미지 빌드·Compose 기동·Testcontainers 테스트, 문서 반영·검증·커밋·Push를 승인했다. 재부팅은 필요 여부를 알리고 자동 실행하지 않는다. 외부 서버 접속·배포와 Phase 5 업무 구현은 제외한다.

- [x] 공식 설치 자료·서명·체크섬을 확인하고 필요한 Docker Desktop·WSL만 설치한다. 기존 배포판·컨테이너·영구 자료는 보존한다.
- [x] 로컬 Docker daemon·Linux 컨테이너·Compose 버전과 상태를 확인한다.
- [x] 고정 이미지로 앱·migration 이미지를 빌드한다.
- [x] 전용 Compose 프로젝트에서 DB 초기화·Flyway 성공·앱 HTTP 응답과 자격증명 경계를 확인한다.
- [x] native DB 우회 환경변수 없이 Testcontainers의 실제 PostgreSQL에서 전체 테스트를 실행한다.
- [x] 실행 결과·남은 제약을 문서에 반영하고 검증 후 작업 단위별 커밋·Push한다.

## 실행 결과

- Java25 Wrapper 빌드·패키징, 실제 PostgreSQL의 단위/통합 테스트25개, 패키지 앱 HTTP 확인10개, 독립 Flyway 이력 검증을 통과했다.
- 최초에는 Compose 설정·초기화 스크립트만 검증했다. 추가 승인 후 WSL·Docker 설치, 앱·migrate 이미지 빌드, Compose DB·Flyway·앱 기동·HTTP10개 확인, 실제 Testcontainers의 동일25개 테스트를 모두 통과했다. 재부팅은 필요하지 않았다.
- Docker 빌드에는 optional CA·Maven settings secret과 `unzip` 보완이 필요했다. TLS 검증·Wrapper ZIP 체크섬 검증은 유지했고 호스트별 설정·인증서는 Git에서 제외했다.
- 전이 의존성126개의 라이선스 메타데이터와 포함 자산 고지를 남겼다. 실행 안내·세부 경계는 [로컬 개발 안내](../../development/local.md)와 [ADR-0001](../../adr/0001-technology-baseline.md)을 따른다.

- Docker 검증용 컨테이너·네트워크는 해당 Compose 프로젝트만 종료했으며 DB 볼륨을 보존했다. 원래 프로젝트 폴더에 준비한 `.env`·선택적 CA/미러 설정은 Git에서 제외했다.
