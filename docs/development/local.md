# 로컬 개발 환경

Phase 4 기반·Phase 5 고정 근무 MVP와 Phase 6 로컬 검증을 실행하는 안내다. 아래 날짜별 결과는 당시 이력이며 최신 보안 패치·검사 범위는 [HTTP·취약점 후속 보고서](../quality/http-soak-vulnerability.md)를 따른다. 제품 범위는 [PRD](../prd.md), 업무별 구현 계약은 연결된 `design` 문서, 버전·라이선스 근거는 [ADR-0001](../adr/0001-technology-baseline.md)을 따른다.

## 준비 사항과 실행 경계

- JDK 25. 검증에 사용한 배포물은 Eclipse Temurin `25.0.4.1+1`이다.
- Maven은 별도 전역 설치 없이 저장소의 Wrapper로 `3.9.16`을 사용한다. 첫 실행에는 배포물·의존성 다운로드가 필요하다.
- 컨테이너 경로에는 사용 가능한 Docker daemon이 필요하며, 전체 앱 구성에는 Docker Compose도 필요하다. Testcontainers는 Compose 없이 별도 PostgreSQL 컨테이너를 사용한다. Windows 최초 준비는 아래 절을 따른다.
- 포트 `18080`, `15432`, native 테스트용 `25432`의 기존 사용 여부를 먼저 확인한다. 다른 프로젝트의 컨테이너·포트·DB를 변경하지 않는다.
- 모든 예시는 저장소 루트에서 실행한다. 아래 PowerShell의 JDK·PostgreSQL 경로는 실제 설치 경로로 바꾼다. JDK·DB 환경변수 예시는 현재 프로세스에만 적용한다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
& "$env:JAVA_HOME\bin\java.exe" -version
.\mvnw.cmd -version
```

Linux/macOS에서는 같은 JDK를 선택한 뒤 `./mvnw`를 사용한다. 패치 선택은 Windows·컨테이너 배포 가용성을 맞춘 결과이며, 최신 보안 패치라고 단정하지 않는다. 공개·배포 전 패치와 보안 공지를 다시 확인한다.

## Windows 최초 준비: WSL 2·Docker Desktop

호스트 도구 설치·Windows 기능 변경은 사전 승인 범위에서만 수행한다. 2026-10-24 사용자는 이 로컬 검증에 필요한 설치·환경 설정을 승인했다. 기존 WSL 배포판·Docker 설정·컨테이너·볼륨이 있으면 먼저 확인하고 보존한다. 삭제·초기화로 설치 문제를 우회하지 않는다.

[Docker 공식 Windows 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)의 WSL 2 조건은 WSL 2.1.5 이상, 가상화 지원·활성화, RAM 8GB 이상과 지원 중인 Windows 버전이다. 이번 호스트는 Windows 11 Enterprise 23H2, 빌드 22631로 OS 최소 조건에 해당한다. 다른 PC에서는 에디션·빌드·Microsoft 지원 기간을 다시 확인한다. Linux 컨테이너용 per-user 설치를 사용하며, Docker Desktop 실행에 일반 Ubuntu 배포판을 별도로 설치할 필요는 없다. [WSL 2 백엔드 안내](https://docs.docker.com/desktop/features/wsl/)를 따른다.

1. [Microsoft WSL 3.0.3 릴리스](https://github.com/microsoft/WSL/releases/tag/3.0.3)와 [Docker 공식 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)에서 x64 설치 파일을 받는다. 이번 준비 대상은 WSL 3.0.3·Docker Desktop 4.96.3이며 최신 버전이라는 뜻은 아니다.
2. 설치 전에 두 파일의 SHA-256을 공식 릴리스 공개값과 비교하고 Authenticode 서명이 `Valid`인지 확인한다. 이번에 다운로드한 두 파일은 모두 확인했다. 아래 경로는 실제 다운로드 위치로 바꾼다.

```powershell
$wslMsi = 'C:\path\to\wsl.3.0.3.0.x64.msi'
$dockerInstaller = 'C:\path\to\Docker Desktop Installer.exe'
foreach ($installer in @($wslMsi, $dockerInstaller)) {
    Get-FileHash -LiteralPath $installer -Algorithm SHA256
    if ((Get-AuthenticodeSignature -LiteralPath $installer).Status -ne 'Valid') {
        throw '설치 파일의 서명 검증에 실패했습니다.'
    }
}
```

3. [Microsoft 오프라인 설치 절차](https://learn.microsoft.com/en-us/windows/wsl/install#offline-install)에 따라 WSL MSI를 설치한다. UAC 승인은 사용자가 직접 처리한다. [msiexec의 `/norestart`](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/msiexec)는 자동 재부팅을 막는다.

```powershell
$wslInstall = Start-Process -FilePath msiexec.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList @('/i', ('"' + $wslMsi + '"'), '/qn', '/norestart')
$wslInstall.ExitCode
```

`VirtualMachinePlatform` 기능이 꺼져 있으면 관리자 PowerShell에서 다음 명령을 실행한다. 종료 코드 `3010` 등 재부팅 필요 상태가 나오면 사용자에게 알리고 자동 재부팅하지 않는다. 재부팅 전의 설치 성공을 WSL·Docker 기동 성공으로 기록하지 않는다.

```powershell
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
```

4. [Docker Desktop 라이선스](https://docs.docker.com/subscription-billing/desktop-license/)는 개인 사용을 무료 대상으로 안내한다. 조직 업무로 사용 범위가 바뀌면 적용 조건을 다시 확인한다. 다음 명령은 현재 사용자의 `%LOCALAPPDATA%\Programs\DockerDesktop`에 설치하고 라이선스에 동의하며 WSL 2를 선택한다. WSL 최초 설정에는 별도 관리자 권한이 필요할 수 있다.

```powershell
$dockerInstall = Start-Process -FilePath $dockerInstaller -WindowStyle Hidden -Wait -PassThru -ArgumentList @('install', '--user', '--quiet', '--accept-license', '--backend=wsl-2')
$dockerInstall.ExitCode
```

5. 설치·필요한 수동 재부팅 후 시작 메뉴에서 Docker Desktop을 실행한다. 새 터미널에서 다음 명령으로 WSL 버전과 Linux daemon 응답을 확인한 뒤 프로젝트 검증을 진행한다. 기존 Docker context를 임의 변경하지 않는다.

```powershell
wsl --version
docker version
docker info --format '{{.OSType}}'
docker compose version
```

`docker info`의 `linux` 응답까지 확인해야 컨테이너 실행 준비가 된 것이다. 이 설치 절차 자체는 이미지 빌드·Compose·Testcontainers 검증 결과를 대신하지 않는다.

## Docker Compose로 전체 실행

### 비밀값 설정

`.env`가 없을 때만 예제를 복사한다. 기존 파일을 덮어쓰지 않는다.

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

`.env`의 다섯 값을 각각 다른 임의 생성 로컬 전용 비밀번호로 채운다. 예제에는 빈 값만 있으며 기본 비밀번호는 제공하지 않는다.

| 변수 | 사용 주체 |
| --- | --- |
| `POSTGRES_PASSWORD` | DB 초기화용 `postgres` 관리자 |
| `TNA_MIGRATION_DB_PASSWORD` | 별도 Flyway 프로세스의 `tna_migration` |
| `TNA_APP_DB_PASSWORD` | 일반 업무의 최소 조회·등록·허용 열 갱신용 `tna_app` |
| `TNA_AUTH_DB_PASSWORD` | 인증 조회·초대 수락 전용 최소 열 변경용 `tna_auth` |

빈 값은 Compose 해석 단계에서 거부한다. 초기화 스크립트는 `CHANGE_ME`로 시작하는 값과 역할 간 같은 비밀번호도 거부한다. `.env`는 Git·Docker 빌드 컨텍스트에서 제외되지만 로컬 평문 파일이므로 공유·로그 출력하지 않는다. 비밀번호에 `$` 등 Compose 해석 문자가 있으면 `.env`의 작은따옴표 문법으로 원문을 보존한다. `docker compose config`의 전체 출력에는 비밀값이 들어갈 수 있으므로 결과를 공개하지 않는다.

### 선택 사항: 로컬 네트워크의 CA·Maven 미러

이번 이미지 빌드에서는 호스트의 TLS 중계 루트 CA를 컨테이너가 신뢰하지 않아 `curl: (60)`이 발생했다. Windows 기본 `SslStream`의 인증서 체인 검증과 기존 Root 저장소의 신뢰를 모두 확인한 **공개 CA 인증서만 PEM 형식으로** `target/local-build-ca.crt` 같은 ignored 파일에 내보낸다. 개인키를 내보내거나 조직명·인증서 지문·인증서 파일을 Git 또는 문서에 넣지 않는다. `curl -k`·Maven TLS 검증 해제로 우회하지 않는다.

[Dockerfile](../../Dockerfile)의 선택적 `local_build_ca` secret은 builder의 OS·Java 신뢰 저장소에 CA를 등록한다. CA는 build 이미지에 남으며, 독립된 JRE에서 JAR만 복사하는 runtime·migrate 이미지에는 포함되지 않는다. Maven Central의 큰 JAR 전송도 약 84초간 정체되어, 기존 로컬 설정의 Central 전용 Google 미러 `https://maven-central.storage-download.googleapis.com/maven2`를 선택적으로 사용했다. `maven_settings`는 빌드 중 `/root/.m2/settings.xml`에만 마운트되며 설정 내용을 이미지에 보존하지 않는다. 현재 migrate는 Maven 없이 완성 JAR의 Flyway API를 사용하므로 실행 시 빌드 secret을 마운트하지 않는다. [BuildKit secret 안내](https://docs.docker.com/build/building/secrets/)를 따른다.

필요할 때만 ignored 파일 `target/compose.network.yaml`에 아래 override를 작성한다. 둘 중 필요하지 않은 secret의 선언·참조는 제외한다. 기본 Compose 파일과 `.env`는 그대로 사용하고 설정 파일·인증서를 커밋하거나 출력하지 않는다.

```yaml
services:
  app:
    build:
      secrets:
        - local_build_ca
        - maven_settings
  migrate:
    build:
      secrets:
        - local_build_ca
        - maven_settings
secrets:
  local_build_ca:
    file: ./target/local-build-ca.crt
  maven_settings:
    file: ./target/local-maven-settings.xml
```

소스 경로는 첫 Compose 파일이 있는 저장소 루트를 기준으로 한다. 이 경로에서는 기동·로그·중지 명령에도 동일한 두 `-f` 옵션을 사용한다. 이번 호스트의 원래 프로젝트 폴더에는 `.env`와 위 세 ignored 파일을 준비했으며, `.env`는 검증한 DB 볼륨과 같은 로컬 전용 비밀번호를 사용한다. 기존 설정은 덮어쓰지 않았다.

```powershell
docker compose -f compose.yaml -f target/compose.network.yaml config --quiet
docker compose -f compose.yaml -f target/compose.network.yaml build
docker compose -f compose.yaml -f target/compose.network.yaml up -d
```

secret 내용 변경만으로는 빌드 캐시가 무효화되지 않는다. CA·미러 설정을 변경했을 때는 `build --no-cache`로 다시 빌드한다. [Docker 캐시 기준](https://docs.docker.com/build/cache/invalidation/#build-secrets)을 따른다. CA가 필요 없는 네트워크에서는 해당 secret 없이 다시 빌드해 로컬 신뢰 설정을 제거한다. 이 override는 로컬 다운로드 환경에만 적용하며 운영 TLS 정책을 변경하지 않는다.

### 구성 확인과 기동

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps -a
docker compose logs migrate
```

실행 순서는 다음과 같다.

1. `db`: PostgreSQL 18.6을 기동하고 빈 전용 볼륨의 최초 초기화 때 migration·app·auth·operator 네 역할을 만든다.
2. `migrate`: DB 헬스체크 성공 후 독립 JRE의 `java -jar /app/application.jar migrate`로 Flyway를 실행하고 종료한다. 웹 Spring 컨텍스트 없이 `TNA_JDBC_URL`·`TNA_MIGRATION_DB_PASSWORD`와 고정 `tna_migration` 역할만 사용한다. JDBC URL의 사용자 정보·query/fragment·추가 CLI 인수는 거부한다. 연결 추가 옵션이 필요하면 별도 검증 후 계약을 변경한다.
3. `app`: 마이그레이션 성공 후 일반 업무·인증 역할만 전달받아 실행한다. 인증 역할은 자격 조회와 V3의 제한된 초대 수락에 사용한다. 관리자·마이그레이션 비밀번호는 앱에 전달하지 않는다.

Compose 프로젝트 이름은 `vibe-attendance-local`, DB 이름은 `tna`다. 호스트에는 앱 `127.0.0.1:18080`, DB `127.0.0.1:15432`만 게시한다. PostgreSQL 18 공식 이미지 데이터 볼륨은 `/var/lib/postgresql`에 연결한다. 이미지 태그와 digest는 [Dockerfile](../../Dockerfile)·[compose.yaml](../../compose.yaml)에 고정했다.

브라우저에서 `http://127.0.0.1:18080/login`을 연다. 상태 확인은 다음과 같다.

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:18080/actuator/health
```

정상 응답의 `status`는 `UP`이다. health 그룹 이름이 포함될 수 있으며 DB 연결정보 등 상세 구성은 노출하지 않는다. 새 DB에는 고객사·사원·계정·기본 비밀번호를 넣지 않는다. 로그인 화면 확인은 가능하지만 별도 합성 테스트 자료 없이 로그인할 계정은 없다. 최초 고객사·담당자 온보딩과 실제 메일을 통한 사용자 초대는 후속 작업이다. 로컬 모의 초대의 경계는 아래 계정 접근 절을 따른다.

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

Flyway 성공을 확인한 뒤 migration 자격증명을 현재 터미널에서 제거하고 앱에 필요한 일반 업무·인증 역할만 설정한다.

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

fixture는 알려진 합성 전용 비밀번호로 `tna_migration`·`tna_app`·`tna_auth`·`tna_operator`를 준비한다. 같은 이름의 기존 역할이 안전하지 않거나 비밀번호가 다르면 기존 비밀번호를 덮어쓰지 않고 실패한다. 테스트 중 합성 자료가 바뀌므로 이 DB로 앱 시연과 테스트를 동시에 하지 않는다. 테스트용 비밀번호와 개인정보를 실제 서비스에 재사용하지 않는다.

테스트 종료 시 자신이 만든 인스턴스만 중지한다. native 경로에서는 테스트 종료가 DB 서버를 자동 중지하지 않는다.

```powershell
& "$pgBin\pg_ctl.exe" -D target/local-db/data -w stop
```

Docker 경로로 돌아갈 때는 테스트 환경변수를 설정했던 터미널을 닫는다. 실제 고객 자료·외부 DB를 테스트에 사용하지 않는다.

## 현재 검증 결과와 남은 작업

아래는 2026-10-24 Phase 4 당시의 최초 native DB 검증과 추가 승인 후 Docker 검증 기록이다. 이후 조직·계정 단위의 구현 범위와 구분한다. 상세 버전과 재검증 근거는 [ADR-0001](../adr/0001-technology-baseline.md)을 따른다.

| 항목 | 결과 |
| --- | --- |
| Temurin 25.0.4.1+1·Maven Wrapper 3.3.4 / Maven 3.9.16 빌드·JAR 패키징 | 성공 |
| 단위 테스트 `PasswordPolicyTest` | 3개 통과 |
| native PostgreSQL 18.6의 `DatabaseFoundationIT` | 11개 통과 |
| 같은 전용 DB의 `SecurityWebIT` | 11개 통과 |
| 최초 native 경로 합계 | 25개 통과, 실패·오류·건너뜀 없음 |
| 별도 Maven `flyway:migrate` | 적용 이력 검증·최신 상태 확인 성공, 앱에 migration 자격증명 미전달 |
| 패키지 JAR의 실제 HTTP 확인 | 로그인·로그아웃·CSRF·본인 조회·htmx·격리·경로 차단 등 10개 통과 |
| 공식 Compose v5.6.1의 구성 해석 | 성공. 빈 비밀값 거부·완성된 구성 확인 |
| WSL·Docker Desktop 설치·엔진 기동 | WSL3.0.3·Desktop4.96.3·Engine29.9.2·Compose5.6.1, 재부팅 없이 Linux 엔진 기동 |
| Docker 앱·migrate 이미지 빌드 | 성공. 위 선택적 CA·Maven 미러 secret 사용, Wrapper ZIP SHA256 검증 유지 |
| Compose DB·Flyway·앱 기동 | PostgreSQL18.6 healthy, V1 적용·migrate 종료0, 앱 기동 성공 |
| Compose 실제 HTTP 확인 | health·로그인 폼·CSRF·미인증 차단·htmx·고정 자산 등10개 통과. 기본 계정 생성 없음 |
| Compose DB·실행 경계 | 3개 최소 역할·4개 FORCE RLS·빈 고객/계정·loopback·uid10001·읽기 전용·관리 자격 미전달 확인 |
| Testcontainers 실제 컨테이너 실행 | native URL 없이 실제 PostgreSQL 컨테이너에서 같은25개 통과(단위3+DB11+웹11), 실패·오류·건너뜀0 |
| 이미지의 설정 분리 | 앱 runtime에 빌드 CA 없음, migrate 이미지에 Maven 설정 내용 없음 확인 |
| Caddy·HTTPS·외부 서버·배포·백업 복원·부하 시험 | 이번 로컬 범위 밖, 미실행 |

DB 테스트는 Flyway 적용, 일반 역할·인증 역할 권한, RLS, 명시적 SQL 테넌트 조건, 트랜잭션 성공·실패 후 커넥션 재사용, 복합 FK를 검증한다. 웹 테스트는 회사코드+이메일 로그인, CSRF, 세션 교체·취소·만료, 재직일 경계, 테넌트 변조 차단, htmx 응답·캐시 통제, 운영자·민감 Actuator 접근 차단을 포함한다. Phase 5의 근태 계산·승인 동시성·사용자 초대·부하 검증까지 완료한 것은 아니다.

최초에는 Docker daemon이 없어 native PostgreSQL을 사용했다. 추가 승인 후 WSL·Docker를 설치하고 실제 컨테이너 경로 검증까지 마쳤다. 성공 로그인·재직·세션·테넌트 격리는 Testcontainers의 합성 계정으로 검증했고 Compose DB의 고객사·계정 수는0으로 유지했다. 검증용 컨테이너는 이 프로젝트만 종료하고 DB 볼륨과 ignored 로컬 설정은 보존한다. 호스트별 네트워크 조건과 고정 이미지의 실행 검증이며 운영 보안·성능·법률 적합성 보증으로 확대하지 않는다.

## Phase 5 조직·사원 관리

담당자(`ATTENDANCE_ADMIN`)는 내 화면의 관리 링크 또는 `/admin/organizations`, `/admin/employees`에서 조직·사원 등록/조회/검색/수정과 적용일별 소속 이동을 사용한다. 일반 직원·승인자·서비스 운영자에게 이 권한을 자동 부여하지 않는다. 등록·변경 사유를 남기며, 조직 코드·사번은 불변이고 사원·조직을 삭제하는 기능은 없다. 목록은50개씩 표시한다.

이 절은 조직·사원 첫 구현 단위의 범위와 검증 기록이다. 당시 계정 초대·역할/승인자 배정은 후속으로 분리했으며 현재의 V3 범위는 다음 절을 따른다. 사원 등록만으로 로그인 계정은 생성하지 않는다. 새 Compose DB에는 여전히 고객사·계정·기본 비밀번호를 넣지 않는다. 합성 계정은 별도 Testcontainers DB에서만 사용했다. 수동 운영용 계정 생성 SQL이나 인증 우회 경로를 제공하지 않는다.

`V2__organization_management.sql`은 조직·소속 이력·추가 전용 감사와 사원 version/메타데이터를 추가한다. 기존 V1 직원의 알려지지 않은 생성 시각·주체는 NULL로 유지한다. PostgreSQL에 기본 제공되는 trusted `btree_gist` 확장을 migration 역할로 설치한다. V2 당시에는 앱의 비밀번호 조회·계정 생성·역할 쓰기를 금지하고, 재직 변경의 세션 회수에 필요한 `auth_version` 열 갱신만 추가했다. 현재 V3에서 허용한 계정·역할 작업은 아래 최소 권한 설명과 구분한다. 기존 V1/V2 이력을 수정하지 않는다.

소속 적용일은 서울 기준 오늘부터, 입사일~마지막 재직일 안에서 마지막 배정 시작일보다 늦게 지정한다. 기존 열린 구간을 종료하고 새 구간을 추가한다. 과거 정정·같은 날 재배정·예약 취소는 이번 단위에서 제공하지 않는다. 마지막 담당자 제거, 활성 자식·현재/미래 배정이 있는 조직 비활성화는 차단한다. 세부 경계는 첫 구현 계약을 따른다.

### 2026-10-24 검증 결과

| 검증 | 결과 |
| --- | --- |
| TDD 최초 실패 | V1의 조직 테이블 없음·미구현 담당자 경로403을 각각 확인 |
| Java25 Maven `verify` | 단위3 + PostgreSQL 통합78 = 전체81개 통과, 실패·오류·건너뜀0 |
| 통합 테스트 구성 | DB 기반11·신규 스키마11·조직12·사원14·명령6·조직 웹13·기존 보안 웹11 |
| 격리·정합성 | FORCE RLS·복합 FK·소속 중첩 거부·열 권한·명령 재시도·감사 실패 롤백·동시 상호 부모/담당자 제거 통과 |
| 리뷰 회귀 | 비활성 기존 부모 선택/관계 보존·재활성화 차단, 잘못된 Unicode·감사 직렬화 실패 검증 |
| Docker 이미지·Compose | 새 앱/migrate 이미지 빌드 성공. 보존한 V1 볼륨에 V2 적용·migrate 종료0·앱 health UP |
| Compose 실행 경계·HTTP | 최소3역할·FORCE RLS7개·고객/계정0·loopback·uid10001·읽기 전용·관리 자격 미전달, 기존 HTTP10개 통과 |
| 실제 브라우저 | 별도 합성 DB의 로그인→조직 등록→사원 등록→소속 등록/이력 확인. 데스크톱과390px 모바일 확인, 페이지 전체 가로 넘침 없음 |
| 독립 리뷰 | 명세·보안 읽기 리뷰 완료. 발견한 부모 선택값 문제 수정 후 전체 회귀 통과 |

운영 부하·침투 테스트·법률 적합성·F03 전체·나머지 MVP 완료를 의미하지 않는다. 출퇴근·근태 계산은 이 검증에서 실행하지 않았다. 외부 서버·배포는 수행하지 않는다.

## Phase 5 계정 초대·역할·당일 승인자 배정

담당자는 사원 상세의 계정·승인자 관리 링크 또는 `/admin/employees/{id}/access`에서 계정 상태·역할·승인자 현재 적격성과 이력을 확인한다. 현재 재직 중인 활성 사원에게 별도로 초대를 발급하며, 최초 발급은 비활성·비밀번호 미설정 계정과 기본 `EMPLOYEE` 역할을 만든다. 같은 이메일로 재발급하면 기존 미소비 초대를 취소한다. 수락한 계정의 이메일 변경·재초대는 제공하지 않는다.

전달 경로는 `local` 프로필의 프로세스 메모리 모의 메일함이다. 외부 메일은 보내지 않으며 최대256개를 보관하고 초과하면 가장 오래된 메시지를 제외한다. 재시작하면 메시지는 사라진다. 원문 초대코드는 웹 관리 화면·로그에 노출하지 않고 테스트에서 `InvitationDelivery.messages()`의 전달 객체로만 확인한다. 기본 프로필에서는 전달이 준비되지 않아 발급을 거부한다. 실제 발송·파일럿 전달 채널의 준비를 뜻하지 않는다.

`GET /invite/{companyCode}`는 회사·계정 존재를 조회하지 않는 일반 수락 폼이다. 이메일·초대코드·새 비밀번호·확인값을 `POST /invite/{companyCode}/accept` 본문으로 보내며, 토큰을 URL·쿼리·리퍼러에 넣지 않는다. 초대는24시간 유효하고 만료 정각·재사용·취소·결속 불일치를 거부한다. CSRF를 유지하고 검증 실패를 일반화하며 비밀번호·토큰을 다시 렌더링하지 않는다. 수락은 토큰 소비·비밀번호 설정·활성화·세션 개정·감사를 함께 저장한 뒤 로그인으로 연결하며 자동 로그인하지 않는다.

기본 `EMPLOYEE` 역할을 유지하면서 `APPROVER`·`ATTENDANCE_ADMIN`을 즉시 부여·회수한다. 역할이 달라지면 기존 세션을 회수하고 마지막 유효 담당자 제거를 차단한다. 승인자는 같은 고객사의 수락 완료·활성·현재 재직·`APPROVER` 계정이어야 한다. 대상 사원은 초대 수락 전에도 배정할 수 있지만 자기 지정은 허용하지 않는다. 서울 기준 오늘의 지정·당일 교체·명시 해제만 지원하며 이전 관계는 보존한다. 승인자 권한·재직을 회수하면 기존 관계를 삭제하지 않고 `재지정 필요`로 표시한다. 배정은 새 신청을 위한 관계 설정이며 실제 신청 승인이나 기존 대기 건 자동 재할당을 수행하지 않는다.

`V3__account_access.sql`은 기존 이력을 유지하면서 계정의 비밀번호 미설정 상태와 `credential_set` 생성 열, `role_grant.active`, 초대 토큰과 승인자 이력을 추가한다. 새 테이블에도 FORCE RLS·명시 tenant 조건·복합 FK를 적용하고 승인자 자기 지정·유효 구간 중첩을 DB에서 차단한다. 일반 앱은 비밀번호를 제외한 대기 계정 생성, 역할 등록·`active` 변경, 토큰 메타데이터 조회·발급·취소, 승인자 이력 등록·허용 열 변경만 추가로 허용한다. 토큰 해시는 발급 때 저장하지만 일반 앱이 조회할 수 없고 계정 비밀번호 조회·변경, 역할명 변경, DELETE/TRUNCATE 권한은 추가하지 않는다.

인증 역할의 기존 로그인 조회 트랜잭션은 읽기 전용을 유지한다. 별도 수락 트랜잭션에만 계정의 `password_hash`·`active`·`auth_version` 변경, 토큰 조회·`used_at` 변경과 본인 계정 수락 감사 INSERT를 허용한다. 감사 조회·조직·소속·승인자 업무 조회는 허용하지 않는다. 웹앱에 관리자·마이그레이션 자격증명을 전달하지 않는다.

비밀번호 재설정(RESET), 최초 고객사·담당자 온보딩, 이메일 변경, 미래 역할·승인자 예약, 과거 배정 정정, 신청 재할당·실제 신청 승인은 후속이다. F02·F03 전체 또는 MVP 전체 완료로 표시하지 않는다. 이 절은 사용·권한 계약이며 최종 실행 검증 결과와 구분한다.


### 2026-10-24 계정 단위 검증 결과

| 검증 | 결과 |
| --- | --- |
| TDD 최초 실패 | V2의 account_token 부재·미구현 GET404·서비스 동작 누락 확인 후 구현 |
| Java25 Maven `verify` | 단위3 + PostgreSQL 통합127 = 전체130개 통과, 실패·오류·건너뜀0 |
| 신규 통합 테스트 | 계정 스키마9·계정 서비스14·승인자12·계정 웹14, 기존 통합78개 회귀 유지 |
| 초대·역할·정합성 | 재발급·만료 정각·목적/이메일/tenant/auth_version 불일치·동시 수락·감사 실패 롤백·역할 세션 회수/복구·마지막 담당자 경합·명령 재시도 검증 |
| 승인자·보안 회귀 | 자기 지정·중첩 차단·당일 교체/해제·권한 회수 후 재지정 표시·선택값 보존·CSRF·민감값 없는 실패 로그 검증 |
| Docker 이미지·Compose | 최종 앱/migrate 이미지 빌드·보존 V2 볼륨의 V3 적용·migrate 종료0·앱 health UP |
| Compose 경계·HTTP | 최소3역할·FORCE RLS9·고객/계정0·loopback·uid10001·읽기 전용·관리 자격 미전달, HTTP14개 통과 |
| 실제 브라우저 | 별도 합성 DB에서 초대 발급→수락→수동 로그인→역할 변경→당일 승인자 저장/이력 확인. 계정 관리390px에서 페이지 전체 가로 넘침 없음 |
| 독립 리뷰 | 계약·보안·문서 읽기 리뷰 완료. 지적된 폼 선택값·실패 로그 수정 후 전체 회귀 통과 |

실제 메일 전송·운영 부하·침투 시험·법률 적합성·전체 신청/승인·MVP 완료를 의미하지 않는다. 다음 후보인 고정 근무 템플릿·사원별 적용일 배정은 이번 작업에서 시작하지 않았다.


## Phase 5 고정 근무 템플릿·적용일 배정

`/admin/schedules`에서 담당자가7요일·근무시각·복수 예정 휴게를 등록하고 새 불변 버전을 추가한다. 사원 상세의 근무 배정 링크에서 해당 버전과 오늘 이후 적용일을 배정한다. 과거 버전·이력은 보존한다. 예정 휴게는 실제 휴게 확인이 아니며 캘린더·근로 확인 없이 실제 근로를 확정하지 않는다. 상세는 [계약](../design/fixed-schedule.md)을 따른다.

2026-10-24 V4의5개 업무표와 FORCE RLS·복합FK·휴게/배정 중첩·최소 권한을 실제 PostgreSQL에서 검증했다. 기존130개와 신규 스키마8·서비스14·웹5를 합해157개가 통과했다. 별도로 준비한 계산 엔진 테스트27개도 같은 실행에서 통과했으며 독립 계산 단위로 전달한다. 실패·오류·건너뜀0이다. 실제 최초 테이블 부재·미구현403·서비스 부재 실패를 확인한 뒤 구현했다. 독립 명세·보안 읽기 리뷰도 마쳤다. 이번 연속 작업의 Docker 이미지·Compose 및 브라우저 전체 연결은 마지막 통합 검증으로 남긴다.

## Phase 5 허용 IP·휴일·원천 출퇴근

담당자는 `/admin/networks`에서 CIDR 추가·종료, `/admin/calendar`에서 기간 전체 검토 근거와 예외 날짜를 등록한다. 일반 날짜 기본값도 명시 검토 후에만 저장한다. 직원은 `/me/today`에서 서버 시각 출퇴근, `/me/attendance`에서 날짜별 원천과 실제 근로·예정 휴게 일치 확인을 사용한다. 직접 기록은 연결 주소만 검증하고 전달 헤더·요청의 tenant/employee/시각을 채택하지 않는다. 근로·휴게가 다르면 정정 신청을 사용한다.

2026-10-24 전체226개(단위30+통합196), 실패·오류·건너뜀0을 확인했다. 신규42개는 스키마7·설정9·원천/확인16·웹5·직원 명령 경계5다. 실제 PostgreSQL V5·6개 새 FORCE RLS·복합FK·IP열 조회 거부·불변 원천/확인·개정 분리·현재 권한·재시도·위조 XFF·서버밀리초·확인 무효화와 민감값 없는 실패 로그를 포함한다. 실제 실패 재현 뒤 숫자 단일 IPv4 거부·MyBatis UUID 스칼라 매핑·메서드 이름 중복·체크 해제 폼 처리를 수정했다. 독립 보안 리뷰를 마쳤으며 Docker·브라우저 최종 연결 검증은 연속 작업 끝에 수행한다. 실제 Caddy 신뢰 프록시·외부 배포를 검증한 것은 아니다.

## Phase 5 종일 연차·독립 승인·정정

직원은 `/me/requests`에서 단일 기초 묶음의 종일 연차, `/me/requests/correction`에서 출퇴근/실제 휴게 정정을 신청한다. 지정 승인자는 `/approvals`에서 대상자·작성자와 다른 독립 주체로 심사한다. 담당자는 `/admin/leave-balances`의 근거 있는 기초·증감 원장, `/admin/requests`의 명시 재할당·예외 경로를 사용한다. 본인의 기초 조정은 다른 담당자가 처리한다. PENDING은 차감·예약하지 않으며 승인 때 날짜별 현재 일정/잔여를 다시 검증한다. 취소 심사 중 원승인은 유효하고 취소 승인만 원USE를 한 번 복원한다.

V6의7개 FORCE RLS·복합FK·원문/원장/결정 추가 전용·부분 유일 제약을 검증했다. 2026-10-24 전체268개(단위30+통합238), 실패·오류·건너뜀0이다. 신규42개는 서비스20·스키마7·웹5·정정 투영10개다. 동시 잔여 소비·감사 실패 롤백·자기 승인·재할당·테넌트·취소 복원·승인 후 취소 시작 경계와 원천/정정 별도 표시를 포함한다. 일정·캘린더가 바뀌면 이전 정정의 실제 확인을 재사용하지 않으며 과거 효력/원천은 보존한다. 반차·시간 휴가·자동 발생·월 마감·급여 계산은 제공하지 않는다.

## 의존성과 고지

해석된 프로젝트 의존성 126개의 POM 라이선스 메타데이터를 [dependency-licenses.csv](dependency-licenses.csv)에 기록했다. `scope`로 테스트 도구와 앱 의존성을 구분하고 `license_metadata_source`로 상속된 메타데이터 출처를 남겼다. 이는 실제 JAR의 LICENSE/NOTICE·빌드 플러그인·JDK·컨테이너 OS 전체에 대한 법률 감사가 아니다. 배포물에 포함할 고지와 사용 조건은 배포 전에 별도 확인한다. 직접 포함한 자산과 Wrapper 고지는 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md)를 따른다.

## Phase 5 일·주·월 결과와 검토용 Excel

V7은 법 적용 검토·주 기산점 근거·계산 실행·일 결과·보고 스냅샷을 추가한다. GET은 계산하지 않으며 최대31일의 조회와 경계 주 전체의 확인된 참고값을 제공한다. 재집계는 최대42일을 고정하고 입력/검토 개정·설정·유효 정정·재직기간의 변경을 오래됨으로 표시한다. 오래됨·미확정 결과를0시간으로 합산하지 않는다. Excel은 준비 시점의 필터·회사·결과·규칙·전체 경계 주 근거를 고정하며 문자열 수식을 생성하지 않는다. [계약](../design/worktime-results.md)과 [ADR-0002](../adr/0002-review-xlsx.md)를 따른다. 실제 Excel 데스크톱 전체 버전·성능·실고객 법적 적용 검증은 남아 있다.

검증: 전체287개(단위33·통합254) 통과 후 재직기간 입력 JSON 보존 회귀를 포함한 WorktimeServiceIT11개를 다시 통과했다. 실패·오류·건너뜀0. 결과 최신성·동시 재직 변경·불변 근거·Excel 메타데이터를 실제 RED로 확인한 뒤 수정했다. Docker 재빌드·Compose 통합 검증은 다음 최종 단위에서 진행한다.

## Phase 5 로컬 비밀번호 재설정

로그인 화면에서 `/password-reset`으로 이동한다. 요청은 회사·이메일 존재 여부와 무관하게 같은 접수 응답을 제공한다. `local` 메모리 전달 객체로만 RESET 코드를 전달하며 외부 메일·운영 전달 채널은 제공하지 않는다. 기본 프로필은 전달 불가를 동일503으로 반환한다. 30분 정각 만료·한 번 소비·재발급 취소·목적/계정/이메일/세션 개정 결속·완료 시 기존 세션 회수와 감사를 검증한다. V8은 기존 INVITE 수락을 보존하면서 인증 역할의 RESET 발급·취소·본인 감사만 확장한다. 목적별 UPDATE 열 권한을 RLS가 구분한다고 주장하지 않는다. [계약](../design/password-reset.md)을 따른다.

RESET 단위 검증: 전체304개(단위33·통합271), 실패·오류·건너뜀0. 신규17개는 실제 PostgreSQL의 요청/발급/소비/동시성/감사 롤백11개와 CSRF·웹 입력·재설정·세션 회수6개를 포함한다.

## Phase 5 독립 운영자·최초 고객사 연결

V9은 일반 고객사 계정과 별도의 운영자 계정·감사·인증 세션을 제공한다. 운영자 DB 역할은 업무 테이블 직접 조회/수정을 할 수 없고 신규 고객사 연결·이용 상태·같은 최초 미수락 계정의 초대 재발급만 제한된 브리지로 수행한다. 외부 메일은 보내지 않는다. [계약](../design/operator-onboarding.md)을 따른다.

기본 Compose는 운영자 UI를 비활성화하고 운영자 비밀번호도 웹앱에 주입하지 않는다. 독립 로컬 콘솔에서 마이그레이션 역할을 잠시 사용해 아래 CLI로 운영자 계정을 명시적으로 만든다. TNA_JDBC_URL은 `jdbc:postgresql://127.0.0.1:15432/tna`와 같이 loopback만 허용한다. 비밀번호는 콘솔에서 숨김 입력하며 기본 계정은 없다. CLI용 관리 환경변수는 웹 실행 전에 제거한다.

```powershell
java -jar target/vibe-attendance-0.0.1-SNAPSHOT.jar operator-bootstrap local-operator
```

명시적으로 로컬 운영자 화면을 확인할 때만 다음 선택 파일을 추가한다. `/operator/login`으로 접근하며 별도 역할 비밀번호만 추가 주입한다. 이 파일도 포트는 loopback을 유지하고 관리·마이그레이션 비밀번호는 앱에 넘기지 않는다.

```powershell
docker compose -f compose.yaml -f compose.operator.yaml up -d --no-build
```

기존 V3 보존 볼륨은 init 스크립트를 재실행하지 않으므로 전용 DB에서 tna_operator 역할의 부재·안전 플래그를 확인한 뒤 새 역할만 추가했다. 기존 고객·계정은0이고 기존 비밀번호나 다른 서비스는 수정하지 않았다. 신규 볼륨은 다섯 비밀번호를 검증한 LF 초기화 스크립트가 네 최소 역할을 생성한다.

운영자 검증: 신규22개(서비스/DB14·웹8), 기존 Foundation11개와 화면 경계12개를 포함한 통합45개·단위33개 통과, 실패·오류·건너뜀0. 최초 초대 재발급은 실제 NULL stub RED 후 구현했다. 독립 경계 리뷰와 기본/운영자 선택 Compose 구문 검사, 컨테이너 내부 Bash 초기화 구문 검사를 통과했다. 전체 회귀·이미지·보존 볼륨은 최종 통합 검증에서 확인한다.

## Phase 5 핵심 흐름 전체 회귀

2026-10-24 전체330개(단위33·실제 PostgreSQL 통합297), 실패·오류·건너뜀0. 별도 RED 회귀로 주간 포함 종료일, 타인/시작 후 일반 취소 버튼, XLSX 근무일 소속·불변 입력 근거·정밀도·민감값 제외를 확인한 뒤 수정했다. 소정 비근로일의 원천도 보존하며 변경된 입력은 재집계 필요로 표시한다.

남은 수용 조건은 결과의 입력·정책 상세 화면과 감사 전후 요약·요청 ID 상세·페이지 탐색이다. 실제 메일, 실고객 법 적용·성능·백업 복구·침투 검증은 수행하지 않았고 운영 출시 단계가 아니다. 정정 상세의 시각은 UTC ISO로 표시되는 부분이 있어 서울 시간의 통일된 표기를 후속 개선한다.

## 초기 MVP 연속 작업 종료 시점의 검증 (2026-10-24)

| 항목 | 실제 결과 |
| --- | --- |
| 전체 회귀 | 단위33·PostgreSQL Testcontainers 통합298, 총331개. 실패·오류·건너뜀0 |
| 마지막 상태 문구 | 문구 변경 후 SecurityWebIT11개 추가 통과. 과거 준비 문구 대신 상태 영역의 존재를 검사 |
| 이미지·기동 | 최종 소스의 Linux app/migrate 이미지 빌드·기동. 보존 V3 볼륨에 V4~V9 적용 성공 |
| DB·실행 경계 | FORCE RLS34개·최소4역할·고객/계정/운영자 계정0·loopback·uid10001·읽기 전용·관리 자격 미주입 |
| 신규 초기화 | 별도 폐기 가능한 tmpfs PostgreSQL18.6에서 LF init 스크립트 실제 실행, 최소4역할·서로 다른4개 TCP 비밀번호 인증 통과 |
| 기본 HTTP | 건강 상태·로그인·CSRF·민감 경로·RESET·운영자 기본 차단 등20개 통과 |
| 선택 운영자 Compose | 명시 활성화로 별도 DataSource 로그인 폼·미등록 운영자 거부·CSRF 유지 확인. 검증 뒤 기본 비활성 상태로 복원 |
| 합성 브라우저 | 담당자 사원별 조회·월/경계 주 재집계·XLSX 실제 다운로드, 승인자의 연차/정정 독립 승인, 직원 잔여10→9·원천 출퇴근·관리 접근 거부 확인 |
| 모바일·세션 | 390px 신청 화면에서 문서 가로 넘침 없음. 운영자 로그아웃 후 고객 세션 유지·별도 권한 확인 |
| 다운로드 재검사 | 실제 브라우저 파일의 OOXML/XML·메타데이터·버전·정밀도·수식/매크로/이메일/IP 제외 확인. Excel 데스크톱 전체 버전 검증은 미실시 |

브라우저 자료는 별도 Testcontainers의 합성 회사·계정만 사용했다. 해당 임시 미리보기는 종료한다. 기본 Compose 앱·DB는 `127.0.0.1:18080`·`127.0.0.1:15432`에서 유지하고 운영자 기능은 비활성화했다. 이 빈 DB에는 실제 사용 계정이나 공용 비밀번호가 없다. 최초 연결은 앞 절의 독립 콘솔·명시 운영자 절차를 따른다. 기본 체크아웃의 ignored `.env`는 기존 네 비밀번호를 보존하고 같은 새 운영자 역할 비밀번호만 추가했다. 비밀값은 커밋하지 않았다.

이 결과는 핵심 업무를 연결한 로컬 초기판의 검증이다. 계산 입력·정책 상세 UI, 감사 상세·페이지 탐색, 서울 시각 표시 통일을 보완하고 실제 메일 전달·법 적용·부하·보안·백업 복원·파일럿을 별도 검증해야 한다. 외부 서버 접속·배포·실제 메일·실고객 자료 사용은 수행하지 않았다.

종료 전 추가 경계: 휴가 범위 앞부분이0DAY이면 그 시작일 대신 첫 실제 차감일의 예정 시작으로 취소 버튼을 판단하도록 수정했다. 이 회귀의 실제 RED 후 마지막 전체331개를 다시 통과했고 해당 최종 소스로 이미지를 재빌드·기동하여 DB 경계·HTTP20개도 재확인했다.

## 계산 근거 상세 후속 단위

2026-10-24 후속 요청으로 `/worktime/results/{id}`를 추가했다. 월 결과의 상세 링크에서 계산 당시 원천/유효 사건·휴게·일정/휴일·정책/적용 검토·재직·실행·신청 참조를 확인한다. 현재 입력 일치 여부와 이후 계산 버전 여부를 별도로 표시하고 누락/손상 근거는 현재 값으로 대신 채우지 않는다. 자유 정책 근거·이메일·IP·JSON 원문은 노출하지 않는다. 본인·같은 고객사 현재 담당자만 허용하며 타인·타 고객사404와 역할 회수를 검증했다.

전체344개(단위39·통합305) 통과 후 누락 사건 목록·정책 객체 회귀2개를 실제 RED로 확인해 보완하고, 관련76개(단위39·통합37)를 다시 통과했다. 실패·오류·건너뜀0. 공통 서울 표시 함수의 자정·연말·밀리초·로케일/서버 시간대 독립·안전 구간 해석6개도 포함한다. 새 테이블·라이브러리·계산 규칙 변경은 없다. Docker/브라우저는 나머지 조회·표시 단위를 합친 최종 검증에서 실행한다.

## 취소 시각 경계 후속 회귀

서울 시각 표시 점검에서 취소 버튼은 첫 차감일 기준인데 서버 신청/승인 경계는 범위 시작일을 먼저 사용하던 불일치를 발견했다. 기존 명세대로 서버도 첫1 DAY 날짜의 예정 시작으로 판정한다. 비차감 앞부분이 지난 경우의 일반 취소 신청·독립 승인·원장 복원 회귀를 실제 RED 후 수정했다. 이미 시작한 연차의 예외 취소 검증은 실제 차감일도 지난 fixture로 보완했다. 관련68개(단위39·통합29), 실패·오류·건너뜀0을 확인했다. 새 정책·스키마·라이브러리를 추가하지 않았다.

## 감사 상세·커서 페이지 후속 단위

`/admin/audit`는50건 단위로 이전/다음/최신 목록을 제공하고 각 행에서 `/admin/audit/{id}`로 이동한다. 같은 고객사의 현재 담당자만 감사·명령·추적 ID와 타입 검증 전후 요약을 볼 수 있다. 민감값·원문 JSON·미등록 필드는 숨기며 명시적 nullable 참조 해제는 없음으로 표시한다. 열람 감사까지 같은 트랜잭션에 저장하고 감사 저장 실패 시 정상 조회 응답을 하지 않는다.

동일 시각150건 양방향·새 기록/자체 열람 감사 삽입·타 고객사/회수 권한·요약 민감값/형식·이스케이프·조회 감사 롤백과 웹 경계를 검증했다. 초기 관련79개 뒤 의미 보완2개의 실제 RED를 확인하고 관련69개(단위39·통합30), 실패·오류·건너뜀0을 다시 확인했다. V10은 tenant/time/id 정렬 인덱스만 추가하며 새 테이블·권한 확대는 없다. 전체 원장을 고정한 페이지 스냅샷은 아니며 조회 시점 원장 기준이다.

## 상세 조회·서울 표시 최종 검증 (2026-10-24)

이번 후속 요청의 계산 근거 상세·감사 상세/페이지·서울 시각 표시를 마무리했다. [실행 계획](../superpowers/plans/2026-10-24-mvp-detail-completion.md)과 [조회 계약](../design/worktime-results.md)을 따른다. 이전 절의 미완료 표기는 각 작업 당시의 기록이다.

| 검증 | 결과 |
| --- | --- |
| 전체 Java 회귀 | 단위39·실제 PostgreSQL 통합329, 총368개. 실패·오류·건너뜀0 |
| 계산 상세 | 당시 원천/유효 사건·실제/예정 휴게·일정/휴일·정책/적용 검토·재직·실행·신청 참조, 현재성/최신 버전 분리·누락/손상 표시·본인/담당자 경계 |
| 감사 | 동일 시각150건·새 기록/열람 감사 삽입·양방향 커서·담당자/테넌트·민감값/형식/해제 상태·명령/추적 ID·감사 저장 실패의 결과 반환 차단 |
| 시각 표시 | 자정·연말·밀리초·서버 시간대/로케일 독립, 실제 정정·휴게·이력·운영자 감사·허용망·표시 날짜, 새 XLSX 생성/계산 시각+09:00·기존 바이트 불변 |
| 이미지/DB | 최종 Linux app/migrate 이미지 빌드·기동, 보존 V9→V10 성공. V10은 정렬 인덱스만 추가 |
| 로컬 경계 | FORCE RLS34개·최소4역할·고객/계정/운영자0·loopback·uid10001·읽기 전용·관리 자격 미주입·운영자 기본 비활성 유지 |
| HTTP | 기본 건강/로그인·CSRF·민감 경로·RESET/운영자 차단20개 통과 |
| 실제 브라우저 | 계산 상세의8시간·당시 사건/휴게/정책·서울 시각·본인 허용과 감사 차단, 감사50건씩 두 페이지 중복0/이전 이동·전후 버전/활성·추적 ID 확인 |
| 반응형·다운로드 | PC1280×900·모바일390×844 상세 화면 가로 넘침 없음. 실제 XLSX의 XML·메타데이터·서울+09:00·밀리초·수식/이메일/IP 제외 확인 |

기존18개 템플릿의 폼·hidden·입력값·권한/예외 조건·링크·HTMX 속성을 독립 검토했고, 변경은 표시 형식에 한정했다. 정정 휴게는 허용된 구간만 표시하며 잘못된 원문은 반사하지 않는다. 시각 변경은 산정 규칙·저장 Instant·과거 결과·이미 만든 파일을 바꾸지 않는다. 별도로 발견한 취소 시작 경계 불일치는 앞 절의 회귀로 바로잡았다.

모든 브라우저 자료는 별도 Testcontainers 합성 데이터이며 임시 미리보기를 종료했다. 기본 Compose 앱/DB만 기존 loopback 포트에서 유지하고 실제 계정·공용 비밀번호·실고객 자료는 넣지 않았다. 이번 요청의 세 항목은 완료했으며 실제 메일·실고객 법 적용·운영 부하/보안·백업 복구·배포는 별도 단계다. 외부 서버 접속·배포는 하지 않았다.

## Phase 6 조회 중 권한 회수 회귀 (2026-10-25)

잠금에서 대기 중 계정·역할 회수가 커밋되어도 본인 출퇴근/관리자 예외 정정 자료를 반환하던 문제를 확인했다. 공통 업무·관리 read 경계는 같은 트랜잭션에서 조회 전과 결과 반환 직전에 현재 권한을 확인한다. 쓰기 잠금을 새로 늘리거나 테넌트/RLS를 완화하지 않았다.

실제 PostgreSQL에서 정확한 writer PID의 잠금 대기를 확인하는 회귀4개가 수정 전 모두403 대신200으로 실패했고, 수정 후 관련 통합56개·단위39개(총95개), 실패·오류·건너뜀0을 확인했다. 초대부터 집계/불변 출력까지 연결한 신규 MVP 시나리오1개도 이 실행에 포함했다. 공통 경계를 독립 보안 검토했다. 전체 회귀·부하·최종 이미지는 Phase 6 최종 단위에서 다시 검증한다.

## Phase 6 300명 사원 선택 회귀 (2026-10-25)

기초 잔여 관리의 사원 선택기에 목록 페이지(첫51명)를 사용해 나머지 사원으로 이동할 수 없던 문제를 별도 전체 후보 조회로 수정했다. 사원 목록 검색·페이지는 유지한다. 합성300명 마지막 사원 선택·실제 기초 등록·422 입력 보존·타 고객사 차단/직원403의4개 회귀 중3개가 수정 전 실패했고, 수정 후 관련 통합39개·단위39개(총78개), 실패·오류·건너뜀0을 확인했다. SQL tenant 조건·현재 담당자·RLS와 기존 자기 처리 제한은 유지한다.

## Phase 6 읽기 병행과 로컬 부하 (2026-10-25)

동일 고객사의 월 조회를 독점 잠금으로 직렬 처리하던 병목을 확인했다. `ClockMapper`·`WorktimeMapper`의 읽기만 같은 키의 transaction shared advisory lock으로 바꿨다. 변경·승인·재집계·출력은 기존 exclusive 경계를 유지한다. 두 실제 서비스 조회가 동시에 완료되고, 실제 사원 변경은 두 조회가 모두 끝날 때까지 대기하는 회귀2개가 수정 전 실패한 뒤 수정 후 통과했다. 권한 회수·컨텍스트 수명·원장·정정·집계를 포함한 관련 통합70개·단위39개(총109개), 실패·오류·건너뜀0이며 독립 보안 검토를 마쳤다.

별도 PostgreSQL18.6 Testcontainers, Windows Java25, 앱 풀5개, 합성3고객사×300명·2026-08의31일 원천55,800건으로 측정했다. 8개 worker·48개 샘플의 동일 고객사 월 조회 p95는2286.20→847.23ms였다. 같은 최종 실행에서3고객사 월 조회761.63ms,3개 worker·9샘플 재집계616.41ms, Excel 준비+다운로드634.20ms, 전체498개 측정 오류0이다. 동일 출퇴근 명령16개를 동시에 재시도해 원천·감사·반환 ID가 각각1개임을 확인했다. 원천/감사 SQL은 기존 인덱스를 사용했다.

`Phase6LoadProbe`는 새 합성 DB에서만 실행한다. 각 고객사에서 실제 근로 확인 및 계산 저장 대상은1명이고 법 적용은 미확정이다. 900명 전체 동시 집계·SLA·운영 VPS 용량을 입증하지 않는다. 워밍업3회·데이터 준비·worker 큐 대기는 제외하며 lock/pool 대기는 포함한다.9샘플의 nearest-rank p95는 max와 같으며 단일 호스트2회 비교의 변동성이 있다. 실행·재현 안내와 전체 품질 결과는 최종 Phase 6 보고서에 정리한다.

## Phase 6 최종 로컬 검증 (2026-10-25)

[품질 보고서](../quality/phase6-report.md)에 PRD S01~S10·SEC-01~12의 자동 검증 증거·발견3건·실제 성능 수치·재실행 방법·운영 전 남은 조건을 정리했다. 보고서의 범위를 넘어 출시/법적 적정성/운영 용량을 선언하지 않는다.

- 전체383개(단위39·실제 PostgreSQL 통합344), 실패·오류·건너뜀0. 초대부터 불변 출력/감사를 연결한 시나리오1개와 권한 회수/300명 선택/공유 잠금/컨텍스트 생명주기14개가 신규다.
- 최종 Linux app/migrate 이미지 빌드·Compose 기동 성공, V10 유지. FORCE RLS34개·최소4역할·고객/계정/운영자0·loopback·uid10001·read-only·관리 자격 미주입·운영자 비활성 재확인.
- 체크인한 `scripts/verify-local-http.py`로 HTTP24개·CSRF/CSP/비저장/미인증/htmx/Actuator·운영자 기본 차단, 프록시 환경 우회와 parser self-check 통과. 같은 체크를 반복 실행한 수를 합산하지 않는다.
- 별도 부하498개 오류0, 동일 명령16회 동시 재시도는 원천/감사/반환 ID 각각1개. 월 조회 p95 개선은 앞 절과 보고서의 로컬 측정 범위다.
- 새 Testcontainers의 같은 클러스터 새 DB에 논리 복원 성공. 원본 불변·행 수·SHA14개·34 FORCE RLS·역할/테넌트 격리·인증 목적 유지. dump262ms·restore299ms는 소규모 로컬 결과이며 운영 RPO/RTO가 아니다.

부하/복원 도구는 기본 `verify`에서 자동 실행하지 않는다. 재실행은 보고서의 명령을 따른다. 실행 로그·JSON·합성 덤프·raw manifest는 ignored `target/phase6-*`에만 둔다. 실제 메일·실고객·외부 서버 접속·배포는 하지 않았다. 기본 Compose의 빈 DB와 loopback 앱만 유지하고 임시 Testcontainers는 종료했다.

## 전체 의존성·이미지 보안 후속 검증 (2026-10-25)

[후속 보고서](../quality/http-soak-vulnerability.md)에 검사 도구/DB 고정값·대상별 실체·전후 수치·잔여 취약점과 [원시 검토 원장](../quality/vulnerability-review.csv)을 기록했다. MyBatis3.6.0의 내부 OGNL 패치·UUID TypeHandler와 빌드 plugin realm 패치를 적용하고 전체396개(단위46·실제 PostgreSQL 통합350), 실패·오류·건너뜀0을 확인했다. 수집 도구의 stdlib 테스트12개도 통과했다.

migrate는 build 상속 대신 완성 JAR·독립 JRE·uid10001을 사용한다. 새 전용 tmpfs DB의 V1~V10 최초 적용과 보존 V10의 no-op, 앱/DB 경계·기본 HTTP24개를 실제 확인했다. 기존 CA·Maven 미러를 쓰던 runtime secret 마운트는 새 override에서 제외했으며 기존 ignored 설정 파일을 삭제하지 않았다. 기본 앱은 빈 Compose DB와 loopback 포트를 유지한다.

전체126개 프로젝트 JAR·160개 플러그인 JAR·52개 Maven JAR과 이미지4종을 로컬 검사했다. 앱/migrate의 Java archive 검출0은 OS·개발 도구까지 취약점이 없다는 의미가 아니다. 공급자 평가 대기·미수정 항목과 도달성 미검증은 그대로 남긴다.

후속 실제 HTTP는 새 합성 DB·18개 로그인 세션으로33분·15,471요청·오류0·예상 거부1,043건, 전체p95 619.469ms를 확인했다. ramp60초 뒤32분 steady는15,360요청=8.000요청/초였다. 출퇴근 실제18개 명령은 각 원천1건·중복0이고 연차 승인340·대기6건은 초기/smoke/마감 중단 수와 일치했다. 15초 자원132표본·작업별 지연과 한계는 후속 보고서/분 단위 CSV를 따른다. HTTP7·수집12개 Python 도구 테스트도 통과했다.

임시 합성 앱/DB는 정상 종료했고 기본 빈 Compose 앱/DB만 loopback에서 유지한다. 종료 뒤 DB 경계·HTTP24개를 다시 확인했다. 부하 도구는 기본 verify에 자동 추가하지 않으며 [재현 명령](../quality/http-soak-vulnerability.md#재실행)을 따른다. 운영 TLS·900명 동시 집계·로그인 폭주·수일 부하·침투·오프사이트 복원은 이 결과가 아니다.
