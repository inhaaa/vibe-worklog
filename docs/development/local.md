# 로컬 개발 환경

## 새 회사에서 첫 출근을 검증하는 방법

운영자는 `/operator/login`에서 첫 담당자를 등록한다. 담당자는 초대 메일을 수락한 뒤 홈의 **회사 초기 설정**에서 조직·근무시간코드·근무조·근무일·허용망을 준비한다. 입사를 확정하고 직원과 결재자를 초대한다. 수락한 결재자에게 승인자 권한을 부여한 뒤 신청서코드의 결재선 미리보기에서 사원과 결재자를 대조한다. 초대가 만료되면 같은 주소에 재발급한다. 이미 수락한 직원은 로그인 화면의 **비밀번호 재설정**에서 코드를 요청할 수 있다. [업무 인수 기준](../superpowers/plans/2026-11-21-customer-onboarding.md)

SMTP 전달에는 `tna.mail.mode=smtp`, `tna.mail.public-origin`, `tna.mail.from`과 `spring.mail.host`를 지정한다. 복구 메일을 받지 못하면 다시 요청해 새 코드를 발급받는다. 메일 주소와 일회용 코드는 수신자 메일함에서 확인한다. [설정값과 전달 실패 처리](../adr/0007-account-mail-delivery.md)

### 직접 사용해 보기

로컬 비밀번호 5개를 설정하고 아래 세 Compose 파일을 순서대로 지정한다. 이 구성은 별도 프로젝트 `vibe-attendance-opening`과 전용 DB 볼륨을 사용한다. 회사 계정 로그인 화면과 운영자 콘솔을 열고 계정 메일을 같은 환경의 Mailpit에 전달한다. [로컬 비밀번호 설정](#비밀값-설정) · [개통 환경 구성](../../compose.opening.yaml)

```powershell
docker compose -f compose.yaml -f compose.operator.yaml -f compose.opening.yaml up -d --build
```

최초 한 번 독립 콘솔에서 `TNA_JDBC_URL=jdbc:postgresql://127.0.0.1:15434/tna`와 개통 DB용 마이그레이션 자격을 지정해 운영자 계정을 만든다. 브라우저에서 `http://127.0.0.1:18082/operator/login`을 열어 가상 회사를 등록하고 `http://127.0.0.1:18025`에서 첫 초대 메일을 연다. 같은 회사에서 담당자·직원·결재자를 동시에 시험할 때는 역할마다 독립된 브라우저 프로필을 사용한다. [운영자 계정 생성](#phase-5-독립-운영자최초-고객사-연결)

### 자동 검증

아래 검증은 JDK 25, Node.js, Docker, Chrome이 있는 PC에서 실행한다. PostgreSQL과 Mailpit을 전용 컨테이너로 시작하고 운영자 한 명을 준비한다. 브라우저에서 PC와 모바일용 가상 회사를 각각 신규 등록한다. 결과와 업무 화면 캡처는 `target/customer-opening-browser`에 남는다. [테스트 메일함 공식 이미지](https://mailpit.axllent.org/docs/install/docker/)

```powershell
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
npm.cmd install --prefix target/browser-tools --no-save puppeteer-core@25.16.0
./mvnw.cmd '-Dfrontend.skip=true' test-compile dependency:build-classpath '-Dmdep.outputFile=target/customer-opening-classpath.txt'
$env:WORKLOG_PUPPETEER_MODULE = (Resolve-Path 'target/browser-tools/node_modules/puppeteer-core').Path
$testDeps = (Get-Content -Raw target/customer-opening-classpath.txt).Trim()
$browserClasspath = 'target/test-classes','target/classes',$testDeps -join [IO.Path]::PathSeparator
java -cp $browserClasspath kr.tna.CustomerOpeningBrowser
```

## 정정과 휴가 승인부터 월 마감까지 검증하는 방법

위 자동 검증의 프런트엔드 빌드, Puppeteer 설치, Maven 테스트 컴파일을 실행하고 `$browserClasspath`와 `WORKLOG_PUPPETEER_MODULE`을 준비한다. 실행할 때마다 빈 경로를 사용한다. 아래 두 명령은 각자 전용 PostgreSQL과 웹앱을 열어 동일한 회사의 기초자료·원천 근태를 준비하고 브라우저로 신청부터 마감까지 처리한다.

```powershell
$cycleRun = Join-Path 'target' ('month-cycle-' + [guid]::NewGuid().ToString('N'))
$evidence = Join-Path $cycleRun 'results'
java -cp $browserClasspath kr.tna.AttendanceMonthCycleBrowser pc (Join-Path $cycleRun 'pc') $evidence
java -cp $browserClasspath kr.tna.AttendanceMonthCycleBrowser mobile (Join-Path $cycleRun 'mobile') $evidence
python scripts/verify_month_cycle_workbooks.py --fixture (Join-Path $cycleRun 'pc/fixture.json') --evidence $evidence
```

마지막 명령은 PC와 모바일의 직원별 월 요약·일별 결과를 회사와 각 부서의 실제 Excel 숫자 셀에 대조한다. 각 실행에서 같은 마감본을 반대 화면 크기로 한 번 더 내려받아 파일 내용까지 비교한다. 합성 계정 비밀번호가 든 `fixture.json`은 로컬 실행 경로에만 둔다. [인수 사례와 검증 결과](../quality/attendance-month-cycle-20261121.md)

> 2026-11-05 UI·모달 후속: 조직장·권한·인사·근무 배정과 신청을 공통 모달로 연결하고 내정보7탭·사원 헤더·신청/결재를 정돈했다. 개발312개·공개396개/타입·빌드 후 공개 `d320dfc`·V17에 앱만 교체했다. 공개 HTTP26항목·실제 PC/390px 모달·내정보와 V17·48표446행·기존 서비스를 보존했다. 실제 업무 저장/신청 POST·새 부하/취약점 검사는 실행하지 않았다. [구현·최종 공개 결과](../quality/workspace-modal-polish-20261105.md)를 따른다.

> 2026-11-03 결재 화면·데모 승인 후속: 대기/완료 카드·승인/반려 검토와 두 계정 정상 로그인/전환을 구현했다. 개발 Java698·프론트262, 공개 Java763·프론트346/타입·빌드·Python74와 실제 신청→독립 승인·12→12→11DAY·PC/390px를 검증하고 공개 `3e6e99c`·V16에 반영했다. [로컬 결과](../quality/approval-workspace-20261103.md)·[실제 공개 결과/체험 순서](../quality/public-approval-workspace-deploy-20261103.md)를 따른다. 아래 수치는 당시 이력이다.

> 2026-10-29 공통 업무 그리드: [설계](../superpowers/specs/2026-10-29-common-sheet-design.md)·[ADR](../adr/0005-common-worklog-sheet.md)·[검증 결과](../quality/common-sheet.md)를 따른다. AG Grid Community36.2.0, React207개·Java618개·Python62개·타입/build와 PC/390px를 검증했다. 프론트는 `npm --prefix frontend ci --no-fund`, `npm --prefix frontend test`, `npm --prefix frontend run build`를 사용한다. style-src self를 유지하도록 `/react/sheet.css`와 같은 출처 글꼴/SVG를 함께 공급한다. 공개 데모 반영과 새 장시간 부하/이미지 검사는 이번 로컬 결과와 구분한다.

> 2026-10-28 업무 확장 3단계의 React 결재선 관리·사원/조직 배정·조직장 결재·신청별 스냅샷·본인 처리 결재 조회는 [상세 계약](../design/approval-routes.md)·[실행 계획](../superpowers/plans/2026-10-28-react-approval-routes.md)과 아래 [3단계 안내](#react-결재선-관리조직장-결재-2026-10-28)를 따른다. 최종 테스트·로컬 V13 적용·기존 자료 보존은 [실제 결과](../quality/approval-routes.md)로 구분한다. 아래 수치는 실행 당시 이력이다.

> 2026-10-27 후속 부하: 기존 변경을5단계로 각각 commit/push한 뒤 현재 XML·JIT 차단 구성의33분15,471요청을 새로 실행했다. 오류/업무 검증 실패0·steady7.999req/s·p95 253.014ms(이전247.396ms)·사전 조사 경고0이며 원장/Excel·기본35테이블/서비스 보존을 확인했다. 단계별 프론트95개·Python62개·최종 Java496개 검증과 상세 비교·한계는 [새 부하 보고서](../quality/no-xml-soak.md)를 따른다. 아래 검증 수치는 각 실행 당시 이력이다.

> 현재 PostgreSQL 실행 이미지는 같은 PGDG18.6 source 기반 `18.6-1.pgdg12+2+worklog1`이다. XML/XSLT/LLVM을 비활성화하고 libxml2·XSLT·LLVM/JIT·xml2 모듈을 제외했다. **현재 SQL/XML·JIT는 지원하지 않는다.** upstream SQL231개·Java496개(97+399)·Python62개·실제 HTTP/Excel·기존 물리/논리 백업 복원과 기본 데이터35테이블 보존은 [최신 보고서](../quality/libxml-containment.md)를 따른다. source/패키징 hash·빌드 profile·업데이트 조건은 [ADR-0004](../adr/0004-postgresql-without-xml.md)에 기록했다. 첫 PG 콜드 빌드는 source 컴파일·자체 회귀로 시간이 필요하며 Testcontainers INFO 출력이 간략할 수 있다. 이후에는 Docker 빌드 cache를 사용한다.

> 2026-10-27 잔여 보완 최신 결과: Java493개(단위97·PG 통합396)·Python62개·실제 HTTP 보안24개·multipart12개·60초 smoke와 합성 DB dump/restore·기본 DB35개 테이블 보존을 확인했다. PostgreSQL에서 runtime 불필요 GPG·SQLite 16개를 제외했으며 `src/test/resources/testcontainers.properties`의 공식 `ryuk.container.privileged=false`와 실제 자동 정리를 검증했다. 환경/홈 설정이 우선하므로 다른 Docker 환경은 실효 설정과 지원 여부를 확인해야 한다. libxml2와 빌드·테스트 도구의 잔여 조건 및 재검사 범위는 [서비스 영향 보고서](../quality/react-residual-impact.md)를 따른다. 아래 이전 검증의 프론트95개·33분 부하는 이번에 재실행하지 않았다.

현재 `PostgresImageSecurityIT`의 이미지 전용6개 검증은 소유 Docker DB에서 실행한다. 전용 JDBC를 직접 지정하는 native DB 방식에서는 해당6개를 건너뛰므로 no-XML 이미지 검증을 대체하지 않는다. 당시 최종496개는 Docker 경로에서 skip0이다. 아래493개도 이전 잔여 보완의 이력이다.

> 2026-10-26 최신 프론트는 React19.3.1·TypeScript6.0.3·Vite8.3.5다. Java25 외에 Node.js22.12 이상과 npm이 필요하다. Maven의 generate-resources에서 프로젝트 전용 `npm ci`와 타입 검사·프론트 빌드를 실행해 결과를 JAR에 포함한다. 기존 Thymeleaf/htmx 화면의 검증은 당시 이력이며 [현재 결과](../quality/react-migration.md)와 [ADR-0003](../adr/0003-react-frontend.md)을 따른다.

프론트 단위 검증은 `npm --prefix frontend test`다. 백엔드를18080에서 실행한 뒤 `npm --prefix frontend run dev`로127.0.0.1:5173의 Vite 개발 서버를 시작한다. 개발 HTML은 Vite가, 같은 출처의 JSON·폼·CSS와 직접 Excel 다운로드(`/admin/reports/exports/{id}/download`, query 포함)는 loopback 백엔드 프록시가 제공한다. 별도 CORS·인증 완화는 하지 않는다. JAR/컨테이너에서는 빌드된 React를 Spring이 직접 제공하므로 Node 실행 서버가 필요 없다. Linux Docker 빌드에도 별도 고정 Node builder를 사용한다.

2026-10-27 최종 보안·부하 검증은 Java489개·프론트95개·Python62개·HTTP24개·multipart12개와 실제33분15,471요청 예상 밖 오류0·p95 247.902ms다. 전체 이미지7종·보안 수정·원장·기본 DB35개 테이블 전체 행 SHA256 보존과 남은 항목은 [최종 보고서](../quality/react-soak-security.md)를 따른다. 앞선3건 수정은 [당시 기록](../quality/react-review-fixes.md)에 보존한다.

Linux이미지는 Temurin25.0.5+8·Node22.23.4/npm11.21.1·Brotli 공식 수정판을 사용한다. PostgreSQL은 같은18.6에 OpenSSL u2와 gosu1.20을 적용한 `infra/postgres/Dockerfile`에서 빌드한다. Compose의 `docker compose build db app migrate`와 Testcontainers가 이 공통 PG 정의를 사용하며 최초/캐시 확인에 공개 APT·공식 배포물 접근이 필요하다. 검증·지원 대상은 linux/amd64다. 호스트 전용 JDK/Node는 이번에 변경하지 않았다.

Phase 4 기반·Phase 5 고정 근무 MVP와 Phase 6 로컬 검증을 실행하는 안내다. 아래 날짜별 결과는 당시 이력이며 최신 보안 패치·검사 범위는 [HTTP·취약점 후속 보고서](../quality/http-soak-vulnerability.md)를 따른다. 제품 범위는 [PRD](../prd.md), 업무별 구현 계약은 연결된 `design` 문서, 버전·라이선스 근거는 [ADR-0001](../adr/0001-technology-baseline.md)을 따른다.

공식 수정판 공급의 후속 확인은 [공급 재확인 보고서](../quality/security-supply-recheck.md)를 따른다. Windows/Noble JDK·bookworm libxml2는 공급 대기이며 gosu1.20은 서명·파생 이미지·회귀 검증이 남은 적용 후보로 구분했다. 이 재확인은 기존 설치판 변경이나 테스트 재실행이 아니다.

이후 [gosu 서명·바이너리 분석](../quality/gosu-binary-analysis.md)으로 서명2건·변조 거부3건·고정 DB 검사와61행 매핑을 확인했다. 현재 비루트 Compose는 유지하고 gosu 전용 파생 이미지는 도입하지 않는다. 기본 Testcontainers는 별도 root 시작 경로이며 이 판단을 자동 적용하지 않는다. 이번에는 앱 회귀·HTTP·DB 검증을 다시 실행하지 않았다.

[native 입력·Docker 후속 분석](../quality/native-input-docker-boundaries.md)은 실제 JRE/PG ELF와 설정을 저장하지 않는 Windows local pipe 진단의 근거·한계를 기록한다. 이 진단은 기본 Testcontainers factory 전체 실행이나 CI의 원격 endpoint 차단 장치가 아니다. 미지원 multipart 자동설정 보완은 [별도 계획](../superpowers/plans/2026-10-25-multipart-boundary.md)에 따른다.

현재 후속 실행은 [데모·최종 보안·Ryuk 보고서](../quality/security-runtime-recheck.md)를 따른다. 기존 날짜별 실행과 현재 결과를 구분한다.

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

## 잔여 취약점의 안전 완화 (2026-10-25)

[영향·대응 우선순위 보고서](../quality/residual-vulnerability-impact.md)에 공급자 미수정과 실제 실행 경로를 분리했다. 같은 Noble의 zlib1g2.3 보안 패치를 build/app/migrate에 고정했다. migrate는 app과 같은 읽기 전용 root·/tmp tmpfs·cap_drop ALL·no-new-privileges를 적용하고, DB는 OS postgres 사용자로 직접 시작한다. SQL 관리자 역할과 앱의 독립 최소 권한은 바꾸지 않는다.

DB의 비루트 실행은 기존 PGDATA 소유 UID999를 요구한다. 다른 볼륨 소유권을 자동 chown하지 않는다. 이번 새 named volume 최초 init/V1~V10과 기존 빈 볼륨 no-op·RLS/권한·HTTP24개를 실제 확인했으며, 새 Linux builder/비루트 전용 DB에서 전체396개 회귀도 통과했다. 이는 libc/libxml2/gosu 패키지 전체의 보안 패치를 뜻하지 않는다.

Docker/DB에 직접 접속하지 않는 `python -B scripts/verify-container-policy.py --self-test`는 정상/거부 경계를 검증한다. 실제 판정에는 방금 Docker inspect에서 환경값을 제외해 저장한 Config.User/Labels·HostConfig·State·NetworkSettings.Ports의3개 서비스 JSON을 전달한다. 과거 snapshot을 현재 실행 상태로 재사용하지 않는다.

```powershell
python -B scripts/verify-container-policy.py target/container-policy.json
```

실제 취약 패키지의 가용 수정과 JDK 공급 대기·조건별 개발 도구 영향은 위 보고서를 따른다. 외부 서버 접속·배포·실고객 자료는 사용하지 않는다.

## JDK 정규 패치 공급 대기 중 CA 임시 대응

2026-10-25 공식 eMudhra 대응에 따라 이 프로젝트의 기본 Java cacerts에서 대상2개를 제거했다. native/JRE의118→116·builder119→117 외에 다른 인증서는 바뀌지 않았고 Windows NTFS ACL/소유자와 원본 백업도 보존했다. 기본 JSSE HTTPS와 최종 새 Linux396개 회귀·HTTP24개를 통과했다. 나머지 JDK CPU, OS/브라우저 및 별도 trustStore의 위험까지 해결한 조치는 아니다.

다른 개발 환경에서 이 조치를 적용하려면 먼저 Java25와 실제 기본 cacerts 경로를 확인한다. 도구는 표준 `changeit` 비밀번호의 trusted-certificate-only 파일에 한정하며, 심볼릭 링크 대신 검증한 실제 파일 경로를 받는다. 적용은 설정 변경이므로 해당 세션의 승인 범위에서만 실행한다. 기존 백업은 덮어쓰지 않고 JDK별 새 백업 경로를 사용한다. check 종료값은0(대상 없음)·1(대상 존재)·2(안전성/입출력 오류)이며,1일 때만 apply한다.

```powershell
New-Item -ItemType Directory -Force -Path target/security-tools | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -d target/security-tools scripts/TruststoreRemediation.java
if ($LASTEXITCODE -ne 0) { throw 'Java25 점검 도구 컴파일 실패' }
$taskStore = Join-Path $env:JAVA_HOME 'lib/security/cacerts'
& "$env:JAVA_HOME/bin/java.exe" -cp target/security-tools TruststoreRemediation --check $taskStore
$taskCheckExit = $LASTEXITCODE
if ($taskCheckExit -eq 1) {
    $taskStoreBackup = Join-Path (Resolve-Path target).Path 'cacerts.original'
    & "$env:JAVA_HOME/bin/java.exe" -cp target/security-tools TruststoreRemediation --apply $taskStore $taskStoreBackup
    if ($LASTEXITCODE -ne 0) { throw '신뢰 저장소 대응 실패: 원본 백업을 보존하고 원인 확인' }
} elseif ($taskCheckExit -ne 0) {
    throw '신뢰 저장소 점검 실패'
}
& "$env:JAVA_HOME/bin/java.exe" -cp target/security-tools TruststoreRemediation --check $taskStore
if ($LASTEXITCODE -ne 0) { throw '불신 처리 뒤 점검 실패' }
```

합성 JKS/PKCS12 회귀는 `python -B scripts/test_truststore_remediation.py`로 실행하며 모의 키·인증서는 ignored target에만 생성한다. 기존 저장소·백업이 깨끗하면 재실행은 쓰지 않는다. 이미 실행 중인 해당 JVM은 신뢰 저장소를 캐시할 수 있으므로 재시작/이미지 재생성이 필요하다. 이번 기본 Compose는 수정 이미지로 재생성했다. 다른 프로세스를 자동 종료하거나 호스트 전역 신뢰 저장소를 수정하지 않는다.

Dockerfile은 로컬 빌드 CA 반영 후 공식 대상 불신·검사를 수행한 다음 Maven HTTPS를 실행한다. runtime에는 빌드 CA·Maven 설정·신뢰 저장소 도구/임시 백업을 남기지 않는다. 정규25.0.5 Windows/Noble 공급 완료 시 버전/체크섬/digest·TLS·전체 회귀와 임시 정책 필요성을 다시 검토한다. 검출을 피하기 위해 CA를 광범위하게 제거하거나 TLS 검증을 해제하지 않는다.

명시적인 native JDBC override를 쓸 때 전체 회귀의 초기 자료 전제를 확인한다. 기본 Testcontainers verify는 매번 새 컨테이너를 사용하지만, 같은 합성 DB를 재사용하면 이전 원천 출퇴근·일정 적용일 때문에 정상 충돌이 생길 수 있다. 기존 서비스 DB를 초기화하거나 기대값을 완화하지 말고 승인된 새 전용 DB에서 검증한다.

## 미지원 multipart 자동설정 비활성화 (2026-10-26)

파일 업로드가 없는 MVP에서 `spring.servlet.multipart.enabled=false`를 공통 `application.yml`에 명시했다. local은 이를 상속한다. 새 컨트롤러·차단 필터·의존성·SQL을 추가하지 않았으며 URL-encoded 폼·CSRF·htmx·검토용 다운로드의 기존 계약을 유지한다.

`MultipartConfigurationTest`는 실제 기본/local ConfigData를 로드하고 `MultipartAutoConfiguration`만 실행한다. 기존 설정에서는 두 사례 모두 `multipartResolver` 존재 때문에 실패했다. 설정 변경 후 property=false·`MultipartResolver`/`MultipartConfigElement` 부재와 실제 profile 자산 로드를 확인하며 두 사례가 통과했다. 이 테스트는 DB·전체 앱 서버를 시작하지 않는다.

최종 오프라인 Maven3.9.16·Java25 `verify`는 **단위48·실제 PostgreSQL 통합350=398개, 실패·오류·건너뜀0**이다. 기존 로그인·폼·CSRF·권한 회수·테넌트 격리·승인·집계·Excel의 웹/서비스 회귀를 포함한다. 같은2개를 전체398개와 별도로 더하지 않는다. 독립 리뷰에서도 중요 지적 사항은 없었다.

이번 전체 실행은 새 `vibe-attendance-multipart-check-20261026-*`의 tmpfs 전용 DB `tna_phase4_test`와 임의 loopback 포트를 사용했다. OS postgres·capability 전체 제거·NNP·SCRAM을 유지했고 같은 이름의 기존 DB나 볼륨을 재사용하지 않았다. 완료 후 새 컨테이너는 정상 종료했다. 애플리케이션·인증·운영자·마이그레이션 역할은 기존 fixture가 이 전용 DB에서만 준비했다.

이 보완 단위에서는 기본 Compose 이미지의 재빌드·재생성, 실제 HTTP 보안 점검·multipart tempfile 관찰·부하 시험을 수행하지 않았다. 이후 2026-10-26의 기본 이미지 재생성과 별도 Linux HTTP·부하 검증은 [후속 보고서](../quality/runtime-image-soak.md)에 구분해 기록한다. 모든 MIME 요청의 HTTP 차단이나 P1 JDK/libxml2 해결은 이 변경의 의미가 아니다. 배포·실고객 적용은 진행하지 않았다.

계약·순서는 [보안 설계](../design/security.md#7-웹세션출력-방어)와 [보완 계획](../superpowers/plans/2026-10-25-multipart-boundary.md), 발견 당시 근거는 [native/Docker 분석](../quality/native-input-docker-boundaries.md)을 따른다. 이번 RED/GREEN·전체 로그·집계·전용 컨테이너 종료 코드는 ignored `target/multipart-boundary-20261026/`에 보존한다. 분석용 휴대 JDK를 자식 프로세스에만 지정해 호스트의 JAVA_HOME/PATH·기존 JDK와 신뢰 저장소를 바꾸지 않았다.

## 최종 이미지 HTTP·fixture DB 게시 재검증 (2026-10-26)

후속 이미지·실제 HTTP·혼합 부하의 결과와 한계는 [재검증 보고서](../quality/runtime-image-soak.md)와 [실행 계획](../superpowers/plans/2026-10-26-runtime-image-soak.md)을 따른다. 기본 Compose DB를 유지하며 앱·migrate만 새 이미지로 반영한다. 빌드 전용 CA/Maven secret은 빌드 단계에서만 사용하고 런타임 재기동은 기본 Compose 구성으로 수행한다.

실제 inspect에서 기본 Testcontainers PostgreSQL 게시가 모든 인터페이스로 열리는 것을 발견했다. `DatabaseFixture`의 owned 컨테이너 생성 콜백에서 기존 게시를 `127.0.0.1` 임의 포트→5432 하나로 교체하고 publish-all을 끈다. 기존 HostConfig와 supplied JDBC 경로는 유지한다. 이 변경은 Testcontainers DB의 게시를 한정하며 표준 이미지의 root 초기화·도구 제어 포트·호스트/엔진 전체 보안 구성을 검증한 의미가 아니다.

`Phase6HttpFixture`는 새 owned DB만 사용한다. 기본은 기존 loopback 합성 허용망이고, Linux 앱 검증에는 `-Dtna.http.fixture.gateway-address=<실제 사설 IPv4>`로 canonical 주소 하나의 `/32`만 추가한다. 기존 `127/32`와 과거 원천 참조를 보존한다. Linux 앱은 전용 bridge와 명시적인 loopback 게시를 사용하며 넓은 허용망이나 X-Forwarded-For 신뢰로 우회하지 않는다. internal 전용 네트워크에서는 이 환경의 호스트 포트 게시가 생성되지 않았으므로 no-egress 격리 검증으로 기록하지 않는다.

`scripts/verify-multipart-http.py --metadata <target 내부 합성 fixture JSON> --output target/runtime-revalidation-20261026/multipart-probe/result.json`은 별도 세션의 일반 폼 로그인·multipart CSRF 누락·정상 CSRF 헤더를 넣은 multipart 자격을 비교한다. `--with-file`은 무해한 작은 파일 part를 추가한다. 합성 표식·loopback origin·출력 경로를 검사하고 비밀번호/CSRF/본문은 출력하지 않는다. tempfile 관찰은 포함하지 않는다.

Linux 부하를 위해 metadata의 baseUrl을 검증된 새 앱의 loopback 포트로 연결하고 native JVM runtimeFile은 제외한다. Docker 앱/DB 지표는 별도 기록한다. HTTP runner가 종료하기 전에 fixture를 멈추지 않는다. 측정 종료·지표 수집 종료·Linux 앱 종료·최종 읽기 전용 정합성 확인 후 owned fixture를 정상 종료한다. 중단한 부분 실행·smoke·완료한 지속 부하의 요청 수를 서로 합산하지 않는다.

수정 후 전체 Java402개(단위52·PostgreSQL 통합350)·Python35개, 기본/합성 앱의 실제 HTTP 보안 각24개와 multipart12개가 통과했다. 최종 Linux 이미지의 별도33분은 1,980.205초·15,471요청·예상 밖 오류/논리 실패0, p95 241.014ms였다. 종료 시 재전송 중복0·과거 원천55,800건 지문 동일·승인/원장 일치·기존 DB의 컨테이너/볼륨/집계/권한 동일을 확인했고 owned fixture 종료 코드는0이다. 기본 앱은 새 이미지로 실행 중이며 종료 후 HTTP24개도 다시 통과했다. multipart tempfile·새 취약점 스캔·운영 용량을 검증한 결과는 아니다.

## 회사코드 없는 로컬 데모·최종 보안 재검사 (2026-10-26)

현재 기본 Compose는 `SPRING_PROFILES_ACTIVE=local,local-demo`다. `http://127.0.0.1:18080`의 소개에서 **데모로 입장** 버튼을 누른다. 후속 A안 적용으로 입력 없이 기존 `admin / admin` CSRF POST 인증을 수행한다. 이 합성 계정은 `EMPLOYEE`·`ATTENDANCE_ADMIN`만 가지며 운영자·승인자 권한은 없다. 현재 로컬 DB에 명시 CLI로 준비했고 첫 실행·동일 자료 재실행 모두 종료0을 확인했다. 일반 `local` 단독과 기본 프로필에서는 기존 회사코드·이메일·강한 비밀번호 정책을 사용한다. `local-demo` 단독은 시작을 거부한다.

새 로컬 DB에서 처음 데모 자료를 준비할 때는 Flyway 성공 후 별도 Java 프로세스에서 다음 명령을 실행한다. 환경은 두 프로필, `TNA_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/tna`, `TNA_MIGRATION_DB_PASSWORD`를 요구한다. 관리 비밀번호는 명령 인수·출력·Git에 넣지 않고 CLI 프로세스에만 주입하며 웹앱에 넘기지 않는다. 이 CLI는 엄격한 literal loopback 주소·명시 포트만 허용한다.

```powershell
java -jar target/vibe-attendance-0.0.1-SNAPSHOT.jar local-demo-bootstrap
docker compose up -d app
```

웹 시작이 자료를 자동 생성하거나 기존 계정의 암호·비활성·역할 회수를 복원하지 않는다. 예약 UUID·회사코드·이메일 충돌이나 변경된 자격/권한/감사 이력은 CLI가 거부한다. 스키마 변경은 없고 예약 고객사1·사원1·계정1·역할2·감사1만 추가한다. 일반 회사코드 입력이나 요청 tenant 변조로 다른 고객사를 선택할 수 없다. 상세 계약은 [로컬 데모 설계](../design/local-demo-login.md)를 따른다.

전체 `verify`는 새 Testcontainers PostgreSQL에서 Java433개(단위65·통합368), Python 도구35개를 실패·오류·건너뜀0으로 통과했다. 새 `LocalDemoBootstrapIT`는 TC 소유 전용 DB만 허용하므로 전체 데모 검증에는 Docker가 필요하며 기존 native JDBC override로 대신하지 않는다. 실제 새 앱 이미지에서 데모 HTTP16개와 공통 보안 HTTP24개를 통과했다. 이전33분 부하 성적은 이전 이미지의 측정이며 이번 로그인 이미지에서 재측정하지 않았다.

최종 app/migrate/build/PostgreSQL 이미지4종을 로컬 Syft/Grype로 검사했다. 원시 CVE×package1,289행 중 기존 동일 버전 기록 연결1,284행·새 Ubuntu 평가 대기5행(CVE3개)이며 제외0이다. 이전 전체 원장1,294행과 미검토1,054행을 보존한다. 런타임 app/migrate의 Java archive 매치는0이나 OS·JDK·libxml2 등 잔여 위험은 남는다. 실행 이미지·고정 DB·도구 checksum·새 원장과 한계는 [현재 보고서](../quality/security-runtime-recheck.md)에 기록했다.

### Ryuk 게시·정리 경계

Testcontainers2.0.5의 `testcontainers/ryuk:0.14.0`에 test-only 공개 `CreateContainerCmdModifier` SPI를 적용했다. 정확한 이미지의8080/tcp만 `127.0.0.1` 임의 포트로 게시하고 예상 밖 포트나 host network는 거부한다. 다른 이미지·향후 Ryuk 버전에는 자동 적용하지 않으므로 업그레이드 때 재검증한다. 이 SPI와 테스트 클래스는 앱 JAR에 포함하지 않는다.

현재 Windows named pipe·Linux Docker의 새 Ryuk에서 `127.0.0.1:65528` 단일 게시를 실측했다. 정상 Testcontainers 연결을 사용한 뒤 클라이언트를 shutdown hook 없이 종료했고 대상 컨테이너와 익명 볼륨1개의 자동 제거를 확인했다. Docker daemon의 정상 응답과 각 대상의 명시적 `No such container/volume` 응답도 보존했다. TC 밖 sentinel은 유지됐고 기존 Compose의 선별 inspect 필드와 `.testcontainers.properties` SHA가 동일했다. 별도 소유 sentinel은 확인 후 정상 정지했다. 임의 TCP/filter 전송이나 전역 daemon 설정 변경은 하지 않았다.

Ryuk는 여전히 privileged·root·Docker 소켓 RW 권한을 가진다. 평문 TCP filter 프로토콜과 세션 라벨이 서버 측 인증 경계인 것으로 간주하지 않는다. loopback은 네트워크 노출 보완이며 Docker 권한 격리·원격 CI endpoint 차단·독립 sandbox를 증명하지 않는다. 현재 고정 버전과 로컬 named pipe에서 확인한 결과이며 원격 daemon/CI는 별도 검증 대상이다.

## 신규 native CVE 입력 분석·builder 축소 (2026-10-26)

현재 builder는 curl 설치를 제외하고 기존 Maven Wrapper의 Java downloader·unzip·공식 ZIP SHA256 검증을 사용한다. 새 이미지에서 curl/wget·libcurl/librtmp 부재와 Java/unzip 사용 가능을 `scripts/verify-build-download-surface.sh`로 확인했다. network none·read-only·cap drop·NNP·UID10001의 일회성 builder에서 이 스크립트를 `sh`로 실행한다. 실제 cold HTTPS app/migrate/build도 성공했다. 일반 Linux 환경에서 curl이 선택되는 경우에는 wrapper 첫 옵션 `--disable`·HTTPS-only 초기/redirect 정책을 적용한다. `scripts/verify-wrapper-downloads.sh`는 wget 없는 curl 테스트 이미지의 network none·빈 임시 캐시에서9개 거부를 확인하며, curl 없는 최종 builder의 검증과 구분한다.

새 이미지4종의 같은 고정 DB 검사는1,234행·제외0이다. builder는 SBOM15항목(14개 deb·curl binary1개)·55개 원시 match 행이 줄었고 RTMP2건은 패키지 부재로 더 이상 검출되지 않는다. app/migrate/PostgreSQL match key는 이전과 같다. Brotli3행은 유지하며 CNA9.2/Critical과 Ubuntu Medium을 구분한다. 강제 폰트 의존성 제거·임의 .so 교체는 하지 않았다. 기존 [1,289행 원장](../quality/security-runtime-matches.csv)은 보존하며 현재 [5행 검토표](../quality/new-native-cve-review.csv)·[실행 보고서](../quality/new-native-cve-analysis.md)를 따른다.

최종 Java433개·Python35개·실제 HTTP16+24개를 통과했고 새 app/migrate에 반영했다. 기존 DB 집계·Flyway V10·RLS·역할/ACL 지문과 컨테이너 ID·이미지·볼륨·라벨은 전후 동일하다. 로그인은 현재 회사코드 없이 admin/admin인 로컬 데모 계약을 유지한다. 해당 native 보완 당시에는 제품 HTML/CSS·메뉴·로그인 진입을 변경하거나33분 부하를 재측정하지 않았다. 이후 A안 적용은 아래 후속 기록을 따른다.


## A안 실제 화면·입력 없는 데모 (2026-10-26)

사용자 승인 시안을 소개·홈·본인 월 달력·직원 목록에 적용하고 기존 업무 화면도 공통 메뉴·상단 본인/회사 표시로 연결했다. 기본 주소의 소개에서 **데모로 입장**을 누르면 실제 로컬 합성 계정으로 홈에 들어간다. `local,local-demo`에서만 제공하며 일반 프로필의 회사코드·이메일 인증은 유지한다. 기존 데모 자료를 자동 재생성하거나 권한을 복원하지 않는다.

전체 Java459개(단위70·통합389)·Python42개·최종 이미지 실제 HTTP35+24개를 통과했다. 브라우저에서1024px/390px의 네 화면과 메뉴/Escape·월/날짜 선택·직원 검색/활성 필터·등록 폼·로그아웃/재입장을 확인했다. 가로 넘침·콘솔 오류는0이다. 새 app/migrate는 실행 JAR의 production250개 항목 일치·V10 no-op·UID10001/RO/cap drop/NNP/loopback을 확인했다. 기본 DB 전후 집계·Flyway·RLS·역할/ACL과 컨테이너 ID·이미지·볼륨·라벨은 동일하다.

현재 합성 직원1명 외에 일정·기초 연차를 자동 추가하지 않았다. 달력과 홈은 미배정·미확인을 표시하고, 직원 통계는 현재 검색/필터/페이지의 최대50명 기준이다. 예시 인원·잔여/시간을 실제 값으로 넣지 않는다. 새 app/migrate2종의 고정 DB 검사는 원시280행·제외0·Java archive0이며 직전 각140행과 키가 같다. 기존 JDK/libxml2/Brotli 미해결 조건·이전 부하/Ryuk 검증의 범위를 유지한다. 상세 이미지 ID·SBOM SHA·실행 근거와 한계는 [워크스페이스 검증 보고](../quality/workspace-ui.md)를 따른다.

검증 명령은 프로젝트 JDK25에서 `mvnw.cmd verify`, `python -B -m unittest discover -s scripts -p 'test_*.py'`, 실행 중인 loopback 앱의 `python -B scripts/verify-local-http.py --port 18080`이다. 호스트 전역 JDK를 바꾸지 말고 기존 개발 안내의 프로젝트 전용 JDK를 사용한다. 이번 UI 이미지의33분 부하와 외부 배포는 수행하지 않았다.


## 흰색·파란 테마의 하루 흐름 홈 (2026-10-26)

사용자의 후속 결정으로 기존 공통 메뉴·소개·일정·직원 목록과 흰색/파란 업무 테마를 유지하고 홈만 큰 날짜·예정/현재 반영 타임라인·출퇴근/확인할 일·주간7일·접이식 원천 비교로 변경했다. 실제 본인 snapshot을 사용하고 시안 자료를 DB에 추가하지 않았다. 현재 기본 데모는 일정 미배정·기초 연차 미등록·기록 미기록이며, 데모 입장은 계속 입력 없는 기존 CSRF 인증이다.

프로젝트 JDK25의 전체 `mvnw.cmd verify`는 Java467개(단위76·PostgreSQL 통합391), Python 도구42개를 실패/오류/건너뜀0으로 통과했다. 새 app/migrate 빌드·V10 no-op·실행 JAR production251개 바이트 일치와 기존 DB/볼륨/권한/RLS 보존을 확인했다. 실제 HTTP38+24개, 홈1024/390/320px·일정/직원1024/390px와 비교 펼침을 포함한10개 브라우저 관측은 가로 넘침·콘솔 오류0이다. 날짜의 다음 달 이동·모바일 메뉴/Escape·기존 확인 폼·상태 새로고침도 확인했다.

실행 명령은 바로 위 A안 절과 동일하다. 새 설정·의존성·마이그레이션·전역 환경 변경은 없다. 이번 홈 이미지의 SBOM/취약점·33분 부하·Ryuk 정리 실측은 재실행하지 않았으며 이전 검사 이력과 JDK/libxml2/Brotli·Docker 소켓의 잔여 조건을 유지한다. 이미지 ID·검증 범위·미실시 항목과 원시 로그 경로는 [홈 품질 보고](../quality/dayflow-home.md)를 따른다. 외부 서버에는 배포하지 않았다.

## WorkLog 표시명 적용 (2026-10-26)

사용자가 영문 이름 **WorkLog(워크로그)**를 선택해 소개·공통 메뉴·페이지 제목·일반 로그인/초대/복구·운영자/오류 템플릿8개를 맞췄다. 소개·메뉴 로고는 W를 사용한다. 기존 흰색/파란 테마·하루 흐름 홈·입력 없는 정상 데모 인증과 한국어 메뉴를 유지한다. 저장소 `vibe-attendance`, Java/DB/설정의 `tna`, DOM/CSS 식별자와 업무 폼은 변경하지 않았다. [표시명 계약](../design/ui-redesign-proposal.md)을 따른다.

단순 표시 문구 변경으로 새 테스트를 추가하지 않고 기존 관련 회귀를 실행했다. 프로젝트 JDK25의 `verify -Dtest=DayFlowTimelineTest,WorkspaceQueriesTest -Dit.test=WorkspaceWebIT,WorkspaceShellWebIT,LocalDemoWebIT,AccountAccessWebIT,PasswordResetWebIT,OperatorWebIT`는 **62개(단위11·전용 PostgreSQL 통합51)**를 실패/오류/건너뜀0·종료0으로 통과했다. 새 로컬 이미지의 실제 HTTP38+24개도 통과했다. 브라우저는 소개1280/390/320px·홈1280/1000/390px·직원1000px의7개 관측에서 가로 넘침·로고 잘림·기존 표시명 잔여·콘솔 오류0이었다. 1000px에서195px sidebar의 WorkLog 표시와 정상 데모 입장을 확인했다.

app/migrate 새 빌드 종료0, 앱 running·migrate V10 no-op/종료0, 검증/실행 JAR production251개 항목 바이트 일치와 UID10001·읽기 전용 root·cap drop·NNP·loopback·테스트 Ryuk SPI 미포함을 확인했다. 기본 DB 집계·Flyway·RLS·역할/ACL과 DB 컨테이너 ID·이미지·볼륨·라벨은 전후 동일하다. app은 `sha256:82d9a09897f8a931530d8ad7906aa98e0559152f7c41c20851b09c9d9fcf934c`, migrate는 `sha256:d24ea3b2c9eb558af79bdd68c4b00780533bcb329b2f14f51d6d85883279843e`다.

이번 표시명 단위에서 전체 Java467개·Python42개 회귀, 새 SBOM/취약점 스캔·33분 부하·Ryuk 정리 실측·외부 배포는 재실행하지 않았다. 이전 검증 이력과 미해결 보안 조건을 유지한다. 원시 로그·DB 전후/JAR 비교·HTTP·브라우저 JSON/PNG는 Git 제외 `target/worklog-brand-20261026/`에 보존했다. 현재 로컬 주소는 `http://127.0.0.1:18080/`다.

## React 권한·메뉴·내 정보 (2026-10-27)

사용자 승인으로 확장 1단계를 구현했다. `/me/profile`은 본인 읽기, `/admin/access`는 관리자 권한 목록/기존 사원별 변경 진입, `/company/attendance`·`/company/requests`는 현재 관리자 또는 대표의 같은 회사 읽기다. 대표는 관리자 설정·재집계·감사·관리자 Excel을 사용할 수 없으며 APPROVER와 현재 배정이 없는 신청을 결재하지 못한다. 역할 변경의 세션 회수·마지막 관리자 보호를 유지한다.

새 V11은 역할 CHECK만 확장했다. 기본 DB의 업무34개+Flyway1개 총35개 테이블과 기존 행·RLS/ACL·컨테이너/이미지/볼륨을 보존했고 기존 V10에 V11 한 건만 추가됐다. 별도 migrate와 app만 새 이미지로 반영했으며 고정 데모 계정의 기존 EMPLOYEE·ATTENDANCE_ADMIN은 그대로다. 실제 대표 검증은 분리한 합성 DB의 기존 관리자 역할 POST로 수행했다.

프로젝트 JDK25의 `mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`는 단위98·전용 PostgreSQL 통합417=515개를 실패/오류/skip0으로 통과했다. React113개·타입/Vite build, Python 도구62개 기준 회귀, 실제 HTTP51개(기본 보안24+기능27), PC/390px 총9회 관측의 가로 넘침/콘솔 오류0을 확인했다. 실행 JAR의 production234개 항목은 검증 JAR과 바이트가 같다.

원본·DB 백업·전후/JAR 대조·화면 PNG는 Git 제외 `target/identity-access-20261027/`에 보존했다. 1단계 당시 실행은 [검증 보고](../quality/identity-access.md)를 따른다. 해당 이미지의33분 부하·전체 취약점 검사는 수행하지 않았으며 [이전 이미지 부하](../quality/no-xml-soak.md)와 구분한다. 당시 조직장·시트·새 결재선·코드·본인 연도별 모달·월간 그래프·운영 공개는 후속으로 남겼다.

## React 조직·사원 시트·조직장·조직도 (2026-10-27~28)

2026-10-27 착수한 업무 확장 2단계를 2026-10-28 `Asia/Seoul` 기준으로 구현·로컬 검증했다. 조직·사원 시트는 변경분1~50행을 원자적으로 저장하며 입력·비활성 예정·취소·409/422 편집/최신값 비교·명령 결과 복원을 제공한다. 기존 단건 관리·적용일 소속 이동·계정 초대·역할·지정 승인자와 원장/CALC-1은 보존한다. 조직장 적용일 이력·동일일 교체/종료·미래 예약/공백 보존, 관리자/대표 조직도와 본인 조직장 표시를 연결했다. 조직장 배정으로 관리/APPROVER 권한을 부여하거나 기존 신청의 결재자를 이동하지 않는다.

최종 전체 Java는 단위101·전용 PostgreSQL 통합447=548개를 실패/오류/skip0으로 통과했다. 최종 Maven 실행의 클래스만 집계해 과거 target XML은 제외했다. React는11파일136개와 TypeScript/Vite build를 통과했다. 기본 보안24개와 신규 기능56개를 합한 실제 HTTP80개를 확인했으며 PC/390px viewport18회 관측의 최종 가로 넘침은0이다. 콘솔 오류0은 합성 관리자와 최종 기본 실행에서 관측한 범위다. 자세한 결과는 [2단계 검증 보고서](../quality/org-sheet-heads.md)를 따른다.

실제 모바일 시트에서 절대 배치된 화면 낭독기용 레이블의 위치 기준이 없어 본문 clientWidth375px에 scrollWidth553px가 발생했다. `.t-sheet-scroll`에 `position: relative`를 적용해375/375px로 수정하고 실제 화면을 다시 확인했다. CSS 수정 뒤 기존 전체 검증의 부분집합인 관련 Java12개를 통과하고 재패키징·이미지 재빌드, migrate V12 no-op/종료0, 기본 HTTP24개·기본 모바일 화면·DB 보존을 재검증했다. Java/JS 업무 동작은 바꾸지 않았으며12개를 전체548개에 더하지 않는다.

V12는 기존 V1~V11을 수정하지 않고 조직장 이력 테이블을 추가했다. 기존 업무34개 테이블의 전체 행·RLS/ACL·컬럼 권한·정책과 DB 컨테이너/이미지/볼륨은 전후 동일하다. 추가 후 업무35개+Flyway1개 총36개 테이블이며 고정 데모 역할도 보존했다. 실행 앱은 새 이미지 `sha256:b64090b983bb1c2fbea33ea825313c9dce9e59753e5b25a0bd1f9beeaff7ab18`이고 `BOOT-INF/classes/`267개 항목은 검증 JAR과 바이트가 같다. 기존 컨테이너 보안 정책과 loopback 기본 서비스를 유지한다.

별도 합성 HTTP fixture는 정상 종료0으로 마무리하고 소유 컨테이너를 제거했다. 종료 때 원천55,800건·대기3건·승인0·재생 중복0을 확인했다. 기본 서비스 자료에 합성 조직/사원을 남기지 않았으며 기본 서비스는 유지한다. 원시 테스트·HTTP·브라우저·전후 DB/권한·백업·JAR 증거는 Git 제외 `target/org-sheet-20261027/`에 보존한다.

당시 새 결재선·권한/공통/근태코드 시트·본인 연도별 현황/모달·월간 그래프·운영 수용은 후속으로 남겼다. 2단계에서 새33분 부하·전체 취약점 전수 검사·Python 재실행·실고객/실제 메일 검증·외부 서버 접속/배포는 수행하지 않았다. 이전 Python·부하·이미지 검사 이력을 이 실행 결과로 옮기지 않는다.

## React 결재선 관리·조직장 결재 (2026-10-28)

업무 확장 3단계는 관리자 메뉴의 `/admin/approval-routes`에서 결재선 코드·이름·상태와 지정 사원/대상자의 현재 조직장 방식을 관리한다. 정의 시트는 조회·입력·삭제·저장·취소를 제공하고 변경분1~50행을 감사와 함께 원자 저장한다. 저장된 삭제는 비활성이며 내용 수정은 새 불변 버전이다. 기존 배정을 최신 버전으로 자동 바꾸지 않는다.

`/admin/approval-route-assignments`에서 사원 또는 조직을 선택해 특정 버전과 서울 오늘 이후 적용일·사유로 배정한다. 시작 포함·종료 제외 구간과 동일일 교체·종료·미래 예약 이력을 유지한다. 배정 종료는 이력 보존이며 관리자/대표/조직장 지위만으로 APPROVER를 부여하지 않는다. 409/422의 편집·명령/행 ID·사유를 보존하고 명시적으로 최신 대상·버전을 확인한 뒤 저장한다.

제출은 사원 명시 결재선→기존 지정 승인자→오늘 소속 조직 결재선 순으로 해석한다. 상위가 공석·비활성·부적격·자기/작성자 결재이면 우회하지 않고 차단한다. 신규 경로는 실제 담당자와 배정/조직/조직장 참조를 신청별 불변 스냅샷으로 저장한다. 기존 신청은 기존 참조를 유지하며 대기 담당자·완료 결정·원장/CALC-1은 자동 변경하지 않는다. 담당자 부재는 신청 상세의 기존 명시 재할당으로 처리한다.

`/approvals`는 현재 지정 대기함, `/approvals/completed`는 본인이 승인·반려한 결재 이력을 각각50행으로 제공한다. 내 정보는 현재 경로/담당/차단 사유를 표시하지 않고(서버 응답에는 남으며 결재선은 신청 모달에서 확인) 신청 상세는 최초 해석과 현재 담당/재할당 이력을 구분한다. 결재선 이름은 불변 버전명이며 조직/사원 표시는 당시 식별자의 현재 이름이다.

V13은 결재선 정의·버전·배정·스냅샷 네 테이블과 신청 참조 XOR/결속 FK를 추가한다. 기존 V1~V12를 편집하거나 기존 신청·결정·원장 행을 변환하지 않는다. 기존 신청의 legacy 배정 참조 두 열 UPDATE 권한만 회수하고 재할당용 현재 담당/상태/version 권한은 유지한다. 버전·스냅샷 UPDATE/DELETE, tenant 격리·다른 신청 snapshot 참조와 기존 자료 보존을 별도 검증한다. 새33분 부하·전체 취약점 검사·실고객/실제 메일·운영 배포는 이번 기능 구현과 구분한다.

프로젝트 JDK25의 `mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`는 완료 결재의 공통 shell 회귀를 포함한 단위103·전용 PostgreSQL 통합483=586개를 실패/오류/skip0으로 통과했다. React는12파일152개와 TypeScript/Vite 빌드를 통과했다. 새 app/migrate를 빌드하고 별도 migrate로 기본 V12→V13 한 건을 적용해 종료0, 완료 결재 표시 보완 후 재빌드·V13 no-op/종료0과 새 앱의 health200·컨테이너 정책을 다시 확인했다. 최종 실행 JAR의 `BOOT-INF/classes` 288개 항목은 검증 JAR과 바이트가 같다. DB 전후 대조·실제 HTTP/브라우저의 최종 근거와 미실시 범위는 [3단계 검증 보고서](../quality/approval-routes.md)를 따른다.

최종 새 합성 환경의 실제 업무 HTTP는 상태/응답 검증을 포함한123요청을 통과했다. 완료 결재의 현재 APPROVER·본인 이름이 공통 shell에 포함되는 검증도 추가했다. 공통 보안24항목도 최종 이미지에서 다시 통과했으며 요청 수와 보안 항목 수를 합산하지 않는다. 기본 DB 기존35개 테이블의 모든 기존 열 기준 행 해시/행수, 기존 RLS·테이블 ACL과 DB 컨테이너·이미지·볼륨이 전후 같음을 재확인했다. 새 nullable snapshot 열로 기존 행 해시가 달라지지 않도록 이전 열 목록을 고정해 대조했다. legacy 배정 참조 두 열 UPDATE 권한 회수만 의도적 차이이며 다른 기존 권한은 보존했다. 신규 네 테이블은0행이고 V13 이후 업무39개+Flyway1개 총40개 테이블이다. 합성 검증 자료를 기본 데모 DB에 추가하지 않았다.

최종 PC/390px10회 관측에서 완료 결재 메뉴·본인 이름, 상세의 최초 담당/명시 재할당, 시트 실제 저장·배정 기본값·본인의 실제 결재 경로를 확인했다. 확인한2개 탭의 콘솔 오류는0이다. 표시 보완 전7회는 별도 이력이며 최종 관측에 합산하지 않는다.

새 합성 자료의40개 테이블과 신청 스냅샷5개를 포함한 논리 복원에서 전체 행·ACL·RLS 플래그·제약 일치와 원본 불변을 확인했다. 알려진 enum CHECK4개의 varchar-array/text cast 표시 차이만 정규화했으며 다른 제약과 지연 FK는 원문으로 비교했다. runtime에서는 tenant 컨텍스트 없음0행, A의 snapshot5행·A의 타 tenant 조건0행·B의 snapshot0행을 확인했다. 기존 전역 역할을 사용하는 같은 클러스터 복원이며 서버 유실·별도 환경·운영 RPO/RTO 검증은 아니다. 실행 로그·덤프·화면·대조 근거는 Git 제외 `target/approval-routes-20261028/`에 보존하고 [검증 보고서](../quality/approval-routes.md)에 실제 수행 범위를 기록했다.

## React 공통코드·근태코드 관리 (2026-10-28)

업무 확장 4단계의 [상세 계약](../design/code-management.md)·[실행 계획](../superpowers/plans/2026-10-28-react-codes.md)에 따라 관리자 전용 `/admin/common-codes`와 `/admin/attendance-types`를 분리했다. 공통그룹/선택 그룹 상세코드와 근태정책 시트는 각각 조회·입력·삭제·저장·취소, 변경분1~50행 원자 저장과 현재 관리자·멱등성·감사를 재사용한다. 저장된 행 삭제는 비활성이며 공통 시스템 코드는 읽기 전용이다. 검색 밖 충돌 대상의 원본/정책 이력, 409/422 편집과 명령 ID, 두 시트의 미저장 손실 확인을 제공한다.

V14는 공통그룹·상세·근태유형·불변 버전 네 테이블과 신청의 NOT NULL 정책 버전 참조를 추가한다. 기존/신규 tenant의 시스템 종일 연차·정정 기초 정책을 제공하고 기존 신청 종류에 해당하는 참조만 보강한다. 대상일별 적용일·동일일 revision으로 선택하며 기간 중 정책이 바뀌면 분리 신청422다. 승인·취소는 제출/원신청의 고정 정책을 사용하므로 이후 비활성/정책 변경이 기존 대기·완료·원장에 소급되지 않는다. 정책 적용일은 서울 오늘 이후이며 관리 시트는 미래 예약을 포함한 최신 등록본이다. 이후 예약을 지우지 않는다.

새 병가·경조·외근·반차·시간차 코드는 초안만 등록할 수 있다. 시스템 핸들러 매핑이나 신청 활성화는 허용하지 않는다. 유급 구분은 메타데이터이며 DAY→시간 변환·급여/법정 계산·근무 인정시간 산정을 추가하지 않는다. 공통코드 등록으로 사원 분류나 권한/신청 상태가 자동 변경되지 않는다. 본인 연도별 현황·모달과 월간 현황 개선은 후속이다.

선택 검증에서 관리18개·V13→V14 업그레이드1개와 신청 정책11개를 통과했다. 업그레이드는 2개 합성 tenant의 신청8개·스냅샷2개와 승인/차감/복원 이력·기존 열·ACL/RLS 보존을 확인했다. React는13파일168개와 TypeScript/Vite 빌드를 통과했다. 이 선택 수치는 최종 전체 회귀 수에 중복 합산하지 않는다. 기본 V13 DB에는 신청·원장이 없어 그 보존 대조만으로 기존 신청 호환을 검증했다고 표현하지 않는다.

최종 전체 Java 회귀·실제 HTTP·기본 V14 반영·실행 JAR/이미지·PC/모바일·복원 및 공개 데모 통합의 수행 결과와 미실시 항목은 [4단계 검증 보고서](../quality/code-management.md)를 따른다. 원시 근거는 Git 제외 `target/codes-20261028/`에 보존한다. 공개 반영은 [승인된 전용 후속 절차](worklog-public-updates.md)를 따르며 로컬 코드·커밋만으로 공개 배포를 완료했다고 표시하지 않는다. 이번 단위의 이전 부하/취약점 결과를 새 이미지의 재검증으로 옮기지 않는다.

최종 전체 `verify`는 Java105+513=618개 실패/오류/skip0으로 통과했다. 실제 업무 HTTP129요청·공통 보안24항목·PC/390px6회 관측과 최종 실행 JAR305개 항목 바이트 일치도 확인했다. 기본 V13→V14 한 건 적용/종료0·health200, 기존39개 업무 자료/권한과 DB 컨테이너/이미지/볼륨 보존,44테이블 합성 논리 복원을 검증했다. 공개 작업트리에서는 별도 V13 통합·배포가 진행 중이어서 이번 작업에서 그 작업트리·서버를 변경하거나 V14를 공개 반영하지 않았다.

## 공통 시트 직접 편집 (2026-10-29)

조직·사원·결재선·공통/근태코드의 셀은 평소 텍스트로 표시하고 클릭·F2·Enter로 편집합니다. Tab/Shift+Tab은 다음/이전 편집 셀, Enter/Shift+Enter는 확정 후 아래/위 행, Esc는 현재 셀 편집 시작 상태를 복원합니다. 저장 전 변경 행 제약을 검사하며 전체 취소는 기존 조회값으로 돌아갑니다. 페이지 draft·50행·409/422/503 계약과 CSP self는 유지합니다. 셀 편집 검증은 [후속 보고서](../quality/sheet-cell-editing.md), `12f0f78`·V14 공개 반영·모바일 긴 셀 보완·실제 보존 검증은 [최종 배포 결과](../quality/public-sheet-deploy-20261029.md)를 따릅니다.

## 시트·V14 최종 부하·전체 취약점 검사 (2026-10-29)

공개 제품과 내용이 같은 Linux 이미지에 전용 합성3테넌트×300명·18세션·원천55,800건을 구성해33분15,465요청·예상 밖 실패0·p95 323.998ms·steady7.996322req/s를 확인했다. 승인함의 옛 제목을 검사하던 HTTP 부하 도구를 현재 계약으로 고치고 관련25개·전체Python74개, 새 사전 검사와33분 전체를 다시 통과했다. Java는115+541=656개, 공개 통합 React는16파일247개를 통과했다. 일반 개발본246개와 공개 AuthPages 보호1개를 구분한다.

새 시트6종 bulk는 별도 fresh DB의135항목과43개 표94,101개 원행의 SHA256 다중집합 보존·예상34행 증가를 확인했다. 이 결과를21연산 부하와 합산하지 않는다. 최종9개 이미지 역할·Java 전체 scope/plugin/Maven 배포·npm prod/dev 검사를 마쳤고 공식 공급자와 대조한 추가 패치 후보는0이다. 원시2,676행·고유 primary ID289개와 미수정/공급 대기 위험은 [취약점 상세](../quality/sheet-security-20261029.md)와 [OS 판정 CSV](../quality/sheet-security-20261029-os.csv)를 따른다.

공개 VPS에는 부하를 보내지 않았다. 기존 로컬22개/공개17개 컨테이너·기본 DB43표·공개43표407행/권한·기존 사이트 HTTP를 보존했다. 제품·runtime 변경이 없어 공개 `12f0f78`·V14를 유지했으며 재배포·DB 변경은 없었다. 실제 시각·자원·종료 정합성·공개72+4항목·증거 SHA·한계는 [최종 보고서](../quality/sheet-soak-security-20261029.md)를 따른다. 원시는 Git 제외 `target/sheet-soak-security-20261029/`에 남겼다.

공용 변경을 일반 개발본에 선택 반영한 뒤 Python67개도 재검증했다. 공개74개에는 공개 배포 전용7개가 더 포함된다. Python 검사가 테스트용 Java 도구를 컴파일하므로 프로젝트 JDK25.0.5의 JAVA_HOME/PATH를 자식 프로세스에 지정하며 시스템 기본 Java8이나 전역 설정은 변경하지 않는다.

## 시트 정렬·40px 한 줄 행 (2026-11-01~02)

공통 시트의 모든 헤더는 중앙이다. 실제 number·숫자 편집 필드·기존 수량/시간 숫자 열은 우측, 짧은 이름/날짜/상태/문자열 코드는 중앙, 문장과4어절 이상 표시 문구는 좌측으로 정렬한다. 선택 라벨·formatter·자식 컴포넌트의 실제 출력도 반영한다. 별도 align 스키마나 숫자 모양 문자열 파싱은 추가하지 않는다.

모든 현재 호출부는 기본40px를 사용하며 wrapText/autoHeight와64/80/112px 예외를 제거했다. 긴 값은 한 줄 말줄임과 기본 tooltip/title로 확인하고 원문·저장 payload는 보존한다. block 설명/경고와 상세 링크를 한 줄로 배치하며 모바일 가로 스크롤과 선택 열 고정, 클릭/F2·Tab/Enter·Esc·IME·오류/원자 저장 계약을 유지한다.

이번 일반 개발본은16파일251개·TypeScript/Vite 빌드, 실제1280px/390px 헤더16개/행4개/숫자4개/nowrap32셀과 페이지 넘침0, 사원 셀 미저장 편집/Esc 복원을 확인했다. Java/DB·의존성 변경은 없고 이전 부하/취약점 검사를 다시 실행한 것은 아니다. 공개 통합252개/빌드 검증 후2026-11-02 `3529a25` 앱만 배포했고 공개72+4항목·정적8자산·PC/390px·기존 서비스/DB 보존을 확인했다. [구현 검증](../quality/sheet-alignment.md)과 [실제 배포 결과](../quality/public-sheet-alignment-deploy-20261102.md)를 구분해 따른다.
