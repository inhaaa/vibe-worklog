# 로컬 개발 환경

Phase 4의 빌드·DB 연결·인증·준비 화면을 실행하는 안내다. 근태·휴가·신청·승인·Excel 업무 기능은 아직 구현하지 않았다. 제품 범위는 [PRD](../prd.md), 버전·라이선스 근거와 검증 이력은 [ADR-0001](../adr/0001-technology-baseline.md)을 따른다.

## 준비 사항과 실행 경계

- JDK 25. 검증에 사용한 배포물은 Eclipse Temurin `25.0.4.1+1`이다.
- Maven은 별도 전역 설치 없이 저장소의 Wrapper로 `3.9.16`을 사용한다. 첫 실행에는 배포물·의존성 다운로드가 필요하다.
- 컨테이너 경로에는 사용 가능한 Docker daemon이 필요하며, 전체 앱 구성에는 Docker Compose도 필요하다. Testcontainers는 Compose 없이 별도 PostgreSQL 컨테이너를 사용한다. Docker Desktop·WSL 설치나 호스트 기능 변경은 이 저장소의 실행 명령에 포함하지 않는다.
- 포트 `18080`, `15432`, native 테스트용 `25432`의 기존 사용 여부를 먼저 확인한다. 다른 프로젝트의 컨테이너·포트·DB를 변경하지 않는다.
- 모든 예시는 저장소 루트에서 실행한다. 아래 PowerShell의 JDK·PostgreSQL 경로는 실제 설치 경로로 바꾼다. 프로세스 환경변수만 설정하며 시스템 전역 설정은 바꾸지 않는다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
& "$env:JAVA_HOME\bin\java.exe" -version
.\mvnw.cmd -version
```

Linux/macOS에서는 같은 JDK를 선택한 뒤 `./mvnw`를 사용한다. 패치 선택은 Windows·컨테이너 배포 가용성을 맞춘 결과이며, 최신 보안 패치라고 단정하지 않는다. 공개·배포 전 패치와 보안 공지를 다시 확인한다.

## Docker Compose로 전체 실행

### 비밀값 설정

`.env`가 없을 때만 예제를 복사한다. 기존 파일을 덮어쓰지 않는다.

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

`.env`의 네 값을 각각 다른 임의 생성 로컬 전용 비밀번호로 채운다. 예제에는 빈 값만 있으며 기본 비밀번호는 제공하지 않는다.

| 변수 | 사용 주체 |
| --- | --- |
| `POSTGRES_PASSWORD` | DB 초기화용 `postgres` 관리자 |
| `TNA_MIGRATION_DB_PASSWORD` | 별도 Flyway 프로세스의 `tna_migration` |
| `TNA_APP_DB_PASSWORD` | 일반 업무 조회용 `tna_app` |
| `TNA_AUTH_DB_PASSWORD` | 인증 전용 조회용 `tna_auth` |

빈 값은 Compose 해석 단계에서 거부한다. 초기화 스크립트는 `CHANGE_ME`로 시작하는 값과 역할 간 같은 비밀번호도 거부한다. `.env`는 Git·Docker 빌드 컨텍스트에서 제외되지만 로컬 평문 파일이므로 공유·로그 출력하지 않는다. 비밀번호에 `$` 등 Compose 해석 문자가 있으면 `.env`의 작은따옴표 문법으로 원문을 보존한다. `docker compose config`의 전체 출력에는 비밀값이 들어갈 수 있으므로 결과를 공개하지 않는다.

### 구성 확인과 기동

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps -a
docker compose logs migrate
```

실행 순서는 다음과 같다.

1. `db`: PostgreSQL 18.6을 기동하고 빈 전용 볼륨의 최초 초기화 때 세 역할을 만든다.
2. `migrate`: DB 헬스체크 성공 후 `tna_migration`으로 Flyway를 실행하고 종료한다.
3. `app`: 마이그레이션 성공 후 일반·인증 조회 역할만 전달받아 실행한다. 관리자·마이그레이션 비밀번호는 앱에 전달하지 않는다.

Compose 프로젝트 이름은 `vibe-attendance-local`, DB 이름은 `tna`다. 호스트에는 앱 `127.0.0.1:18080`, DB `127.0.0.1:15432`만 게시한다. PostgreSQL 18 공식 이미지 데이터 볼륨은 `/var/lib/postgresql`에 연결한다. 이미지 태그와 digest는 [Dockerfile](../../Dockerfile)·[compose.yaml](../../compose.yaml)에 고정했다.

브라우저에서 `http://127.0.0.1:18080/login`을 연다. 상태 확인은 다음과 같다.

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:18080/actuator/health
```

정상 응답의 `status`는 `UP`이다. health 그룹 이름이 포함될 수 있으며 DB 연결정보 등 상세 구성은 노출하지 않는다. 새 DB에는 고객사·사원·계정·기본 비밀번호를 넣지 않는다. 로그인 화면 확인은 가능하지만 별도 합성 테스트 자료 없이 로그인할 계정은 없다. 운영자 가입·실사용자 초대·초기 고객 등록은 후속 작업이다.

앱은 `local` 프로필에서만 로컬 HTTP용으로 세션 쿠키의 `Secure`를 해제한다. 기본 설정은 `Secure=true`, `HttpOnly=true`, `SameSite=Lax`다. 이 Compose 구성은 로컬 개발용이며 Caddy·HTTPS·운영 배포 구성을 제공하지 않는다.

작업을 마치면 저장소 루트에서 이 프로젝트만 중지한다. 데이터 볼륨은 유지한다.

```powershell
docker compose down
```

이미 초기화한 볼륨의 비밀번호를 `.env`에서 바꾸어도 DB 역할 비밀번호가 자동 변경되지는 않는다. 초기화 실패·비밀번호 불일치를 볼륨 삭제로 임의 해결하지 않고, 해당 전용 DB의 상태를 확인한 뒤 별도 변경 절차를 정한다.

## JVM에서 앱 실행

디버깅할 때는 Compose의 앱 대신 호스트 JVM을 사용할 수 있다. 이 경로에서도 DB 역할과 스키마가 먼저 준비되어야 한다. Compose DB를 사용할 경우 다음 명령은 DB만 기동한다.

```powershell
docker compose up -d db
```

Spring Boot와 Maven은 `.env`를 자동으로 읽지 않는다. `.env`에서 정한 값과 같은 비밀번호를 현재 터미널에 입력한다. PowerShell 예시의 입력은 화면에 표시되지 않는다.

```powershell
$env:TNA_JDBC_URL = 'jdbc:postgresql://127.0.0.1:15432/tna'
$migrationSecret = Read-Host '로컬 migration 역할 비밀번호' -AsSecureString
$env:TNA_MIGRATION_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $migrationSecret).Password
.\mvnw.cmd -B flyway:migrate
```

Flyway 성공을 확인한 뒤 migration 자격증명을 현재 터미널에서 제거하고 앱에 필요한 두 조회 역할만 설정한다.

```powershell
Remove-Item Env:TNA_MIGRATION_DB_PASSWORD
$migrationSecret = $null
$appSecret = Read-Host '로컬 app 역할 비밀번호' -AsSecureString
$authSecret = Read-Host '로컬 auth 역할 비밀번호' -AsSecureString
$env:TNA_APP_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $appSecret).Password
$env:TNA_AUTH_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $authSecret).Password
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd -B '-DskipTests' package
& "$env:JAVA_HOME\bin\java.exe" -jar target/vibe-attendance-0.0.1-SNAPSHOT.jar
```

패키징 명령의 테스트 생략은 실행용 JAR 생성에 한정한다. 변경 검증은 아래 `verify`로 별도 수행한다. 앱은 `127.0.0.1:18080`에서 실행되므로 같은 포트의 Compose 앱을 동시에 실행하지 않는다. 종료는 해당 터미널에서 `Ctrl+C`를 사용하고 비밀값을 설정한 터미널을 닫는다.

Flyway는 웹앱 시작 시 실행하지 않는다. 후속 DB 변경은 새 마이그레이션 파일로 추가하고 이미 적용한 V1 이력을 수정하지 않는다. 일반 앱 역할을 관리자 역할로 대체하거나 RLS를 비활성화하여 오류를 우회하지 않는다.

## 테스트

### Docker가 있는 환경

`TNA_TEST_JDBC_URL`을 설정하지 않은 새 터미널에서 실행하면 Testcontainers가 별도 PostgreSQL 18.6 컨테이너와 합성 자료를 준비한다. Compose의 `tna` DB를 테스트 대상으로 재사용하지 않는다.

```powershell
.\mvnw.cmd -B verify
```

`test`는 단위 테스트, `verify`는 Failsafe 통합 테스트까지 실행한다. 테스트 결과는 `target/surefire-reports`, `target/failsafe-reports`에서 확인한다. Dockerfile의 빌드 단계는 테스트를 생략하므로 Docker 이미지 생성만으로 검증을 대체할 수 없다.

### Docker가 없는 환경의 전용 native PostgreSQL

테스트는 명시적으로 지정한 로컬 PostgreSQL에도 연결할 수 있다. **다른 프로젝트와 분리한 테스트 전용 인스턴스**를 준비한다. 테스트 fixture는 DB 권한·역할·스키마·합성 계정 자료를 생성하거나 변경한다. loopback·DB명 검사는 보조 장치이며 기존 공유 인스턴스를 안전하게 만드는 보장은 아니다.

새 전용 인스턴스를 만드는 예시다. `target/local-db/data`가 이미 있다면 초기화 명령을 다시 실행하지 않고 기존 상태부터 확인한다. `postgres` 관리자 비밀번호는 입력 프롬프트에서 새 로컬 전용 값으로 설정한다.

```powershell
$pgBin = 'C:\path\to\pgsql\bin'
& "$pgBin\initdb.exe" -D target/local-db/data --username=postgres --encoding=UTF8 --locale=C --auth=scram-sha-256 --pwprompt
& "$pgBin\pg_ctl.exe" -D target/local-db/data -l target/local-db/postgres.log -o '-h 127.0.0.1 -p 25432 -c timezone=Asia/Seoul' -w start
& "$pgBin\createdb.exe" -h 127.0.0.1 -p 25432 -U postgres -W tna_phase4_test
```

테스트 DB 이름은 반드시 `tna_phase4_test`, 호스트는 `localhost` 또는 `127.0.0.1`이어야 한다. URL 내 사용자정보·fragment·임의 쿼리는 거부한다. 연결할 전용 DB를 만든 관리자 자격증명을 주입한다.

```powershell
$env:TNA_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:25432/tna_phase4_test'
$env:TNA_TEST_DB_USER = 'postgres'
$testAdminSecret = Read-Host '전용 테스트 DB 관리자 비밀번호' -AsSecureString
$env:TNA_TEST_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $testAdminSecret).Password
.\mvnw.cmd -B verify
```

fixture는 알려진 합성 전용 비밀번호로 `tna_migration`·`tna_app`·`tna_auth`를 준비한다. 같은 이름의 기존 역할이 안전하지 않거나 비밀번호가 다르면 기존 비밀번호를 덮어쓰지 않고 실패한다. 테스트 중 합성 자료가 바뀌므로 이 DB로 앱 시연과 테스트를 동시에 하지 않는다. 테스트용 비밀번호와 개인정보를 실제 서비스에 재사용하지 않는다.

테스트 종료 시 자신이 만든 인스턴스만 중지한다. native 경로에서는 테스트 종료가 DB 서버를 자동 중지하지 않는다.

```powershell
& "$pgBin\pg_ctl.exe" -D target/local-db/data -w stop
```

Docker 경로로 돌아갈 때는 테스트 환경변수를 설정했던 터미널을 닫는다. 실제 고객 자료·외부 DB를 테스트에 사용하지 않는다.

## 현재 검증 결과와 남은 작업

2026-10-24의 프로젝트 실행 결과를 구분해 기록한다. 상세 버전과 재검증 근거는 [ADR-0001](../adr/0001-technology-baseline.md)을 따른다.

| 항목 | 결과 |
| --- | --- |
| Temurin 25.0.4.1+1·Maven Wrapper 3.3.4 / Maven 3.9.16 빌드·JAR 패키징 | 성공 |
| 단위 테스트 `PasswordPolicyTest` | 3개 통과 |
| native PostgreSQL 18.6의 `DatabaseFoundationIT` | 11개 통과 |
| 같은 전용 DB의 `SecurityWebIT` | 11개 통과 |
| 합계 | 25개 통과, 실패·오류·건너뜀 없음 |
| 별도 Maven `flyway:migrate` | 적용 이력 검증·최신 상태 확인 성공, 앱에 migration 자격증명 미전달 |
| 패키지 JAR의 실제 HTTP 확인 | 로그인·로그아웃·CSRF·본인 조회·htmx·격리·경로 차단 등 10개 통과 |
| 공식 Compose v5.6.1의 구성 해석 | 성공. 빈 비밀값 거부·완성된 구성 확인 |
| Docker 이미지 빌드·Compose 서비스 기동 | 미실행: 현재 호스트에 Docker daemon 없음 |
| Testcontainers 실제 컨테이너 실행 | 미실행: 현재 호스트에 Docker daemon 없음 |
| Caddy·HTTPS·외부 서버·배포·백업 복원·부하 시험 | 이번 로컬 범위 밖, 미실행 |

DB 테스트는 Flyway 적용, 일반 역할·인증 역할 권한, RLS, 명시적 SQL 테넌트 조건, 트랜잭션 성공·실패 후 커넥션 재사용, 복합 FK를 검증한다. 웹 테스트는 회사코드+이메일 로그인, CSRF, 세션 교체·취소·만료, 재직일 경계, 테넌트 변조 차단, htmx 응답·캐시 통제, 운영자·민감 Actuator 접근 차단을 포함한다. Phase 5의 근태 계산·승인 동시성·사용자 초대·부하 검증까지 완료한 것은 아니다.

현재 호스트는 Docker daemon이 없고 WSL도 비활성 상태다. 구성 해석과 native PostgreSQL 테스트 성공을 Docker 실행 성공으로 해석하지 않는다. Docker를 사용할 수 있는 환경에서 이미지 빌드·기동·health·Testcontainers 검증을 마쳐야 컨테이너 경로의 실행 확인이 완료된다.

## 의존성과 고지

해석된 프로젝트 의존성 126개의 POM 라이선스 메타데이터를 [dependency-licenses.csv](dependency-licenses.csv)에 기록했다. `scope`로 테스트 도구와 앱 의존성을 구분하고 `license_metadata_source`로 상속된 메타데이터 출처를 남겼다. 이는 실제 JAR의 LICENSE/NOTICE·빌드 플러그인·JDK·컨테이너 OS 전체에 대한 법률 감사가 아니다. 배포물에 포함할 고지와 사용 조건은 배포 전에 별도 확인한다. 직접 포함한 자산과 Wrapper 고지는 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md)를 따른다.
