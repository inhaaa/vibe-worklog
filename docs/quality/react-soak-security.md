# React 최종 이미지 장시간 부하·취약점 검사

> 후속으로 [잔여 취약점의 실제 서비스 영향](react-residual-impact.md)을 전수 대조하고, PG GPG/SQLite 제외·Ryuk 비특권 설정과 Java493개·Python62개·실제 HTTP/백업 복원/자동 정리를 검증했다. 아래 이미지·33분 부하·당시 미확인 조건은 이력으로 보존한다.

기준일: 2026-10-27, Asia/Seoul. 사용자가 승인한 [실행 계획](../superpowers/plans/2026-10-27-react-soak-security.md)에 따라 최종33분 부하·전체 이미지7종 검사와 적용 가능한 보안 수정·재검증을 완료했다. 공급 대기와 미확인 항목을 아래에 남기며 운영 출시 완료로 확대하지 않는다.

## 수정

| 대상 | 적용 내용·근거 |
| --- | --- |
| HTTP 부하·multipart 도구 | 이전 SSR 폼 파싱을 현재 React JSON/boot 계약으로 전환했다.18세션·21연산·속도/권한 경계를 유지하고 CSRF·명령·버전·월 전체 날짜·선택 사원/기간·승인 전후 날짜·Excel XML 메타데이터와 불변 해시를 검증한다. 단순200이나 일부 날짜 누락을 정상으로 통과시키지 않는다. |
| Node/npm builder | Node22.23.4 공식 고정 digest와 npm11.21.1을 Docker 빌드 안에서 적용했다. [Node 보안 릴리스](https://nodejs.org/en/blog/release/v22.23.4), [npm 릴리스](https://github.com/npm/cli/releases/tag/v11.21.1). 프로젝트 프론트 잠금 의존성은 변경하지 않았다. |
| Java builder/runtime | Temurin25.0.5+8의 공식 full JDK/JRE를 SHA256 고정으로 설치했다. 조회 당시 일치하는 공식 Noble Docker tag가 없어서 기존 고정 Noble 베이스의 `JAVA_HOME`을 전체 교체하고 `JAVA_VERSION`도 갱신했다. [공식 배포](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8)의 서명·체크섬·라이선스(GPLv2+Classpath Exception)와 Linux x64 ELF118개를 확인했다. 기존 CA apply/check는 유지한다. |
| Noble Brotli | `libbrotli1=1.1.0-2ubuntu0.1`을 build/app/migrate에 적용했다. 이전 평가 대기에서 공식 수정판이 공급된 [CVE-2026-111497](https://ubuntu.com/security/CVE-2026-111497)을 반영했다. 실제 설치 버전과 실행을 확인했다. |
| PostgreSQL | 같은18.6 고정 베이스의 작은 [공통 Dockerfile](../../infra/postgres/Dockerfile)에서 OpenSSL/libssl3 `3.0.22-1~deb12u2`와 공식 gosu1.20(Apache-2.0)을 반영했다. APT 서명/해시 검증과 gosu SHA256 검증을 유지했다. [Debian tracker 예](https://security-tracker.debian.org/tracker/CVE-2026-84782), [gosu 수정판](https://github.com/tianon/gosu/releases/tag/1.20), [라이선스](https://github.com/tianon/gosu/blob/1.20/LICENSE). |
| 전용 테스트 DB | `DatabaseFixture`도 공통 PostgreSQL Dockerfile 한 파일만 build context로 전달한다. 사전 로컬 태그 없이 자동 빌드하며 기존 loopback·전용 JDBC·자동 정리 경계를 유지한다. 새 회귀 RED1실패→GREEN3개로 기존 경계를 포함해 검증했다. |

이번 컨테이너 수정은 **linux/amd64**를 지원·검증한다. 호스트 전역 Node/npm/JDK·신뢰 저장소를 변경하지 않았다. 호스트 Java 회귀는 기존 전용 Windows25.0.4.1+1에서, 최종 앱 HTTP/부하는 실제 Linux25.0.5+8에서 수행한다.

## 이미지 검사

Syft1.55.0·Grype0.121.0, 공식 최신 조회로 받은 고정 DB `v6.1.10`(built `2026-10-26T06:38:44Z`, 배포 archive SHA256 `214b59fbe13608346ee1b22583ec0531083d6088c33891f3520d0fbdedb1b308`)를 사용했다. squashed 파일시스템이며 ignore/VEX0이다. OCI index→manifest→config·archive/SBOM/원시 결과 SHA와 실제 실행 이미지 식별자를 검증했다. 원본은 `target/react-soak-security-20261027/scan/`에 보존한다.

| 이미지 역할 | 최종 ID 앞부분 | 패키지 | 원시 매치 |
| --- | --- | ---: | ---: |
| app | `fc03ce3a97ca` |183|140|
| migrate | `a2a96271ddc0` |183|140|
| Java build | `0429dc4d0e8e` |450|455|
| Node frontend build | `9f7e4a496d68` |339|240|
| 일반 실행 PostgreSQL | `d8c022b48d64` |160|498|
| Ryuk | `7c1a8a9a47c7` |21|64|
| 실제 Testcontainers PostgreSQL | `b819f2c1e53d` |160|498|

동일6개 역할은 **1,640→1,537행**이다. 별도 테스트 PG까지7개 이미지의 합계는 **2,035행**(Critical40/High475/Medium947/Low234/Negligible336/Unknown3)이다. **행 수는 유일 CVE 수나 수정 완료 수가 아니다.** 이미지 간 같은 패키지/CVE가 반복된다. 같은6개 역할에서 프론트102행·gosu1행이 줄었으며 버전/경로 교체와 신규 편입도 원장에 남겼다.

일반 실행 PG와 테스트 PG는 동일 Dockerfile·패키지160개·선택 파일532개의 SHA가 일치하지만 config·RootFS 식별자는 다르다. 테스트 빌드에는 `org.testcontainers.sessionId` 등 자동 정리 라벨이 있다. 기본 DB는 **Compose로 빌드한 일반 이미지**를 사용하고 테스트 이미지를 재태그해 배포하지 않는다. 부하 결과는 실제 테스트 PG 식별자에 연결한다.

전체7종 원장은 [CSV](react-image-vulnerabilities-20261027.csv)로 보존한다. 원시 `fixed` 표기를 배포판의 실제 수정 가능 여부로 해석하지 않는다. OpenSSL 등 공식 backport, 적용한 Brotli/PG 수정판, 상위 번들 공급 대기, 미확인 도달성을 구분했다. 고정 scanner DB에 최신 공급자 수정 정보가 반영되지 않아 패치 후에도 남는 원시 매치가 있다.

## 남은 항목

- npm11.21.1 내부 `undici6.29.0` 2건과 `ip-address10.7.2` 1건은 공식 npm12.2.1에도 같은 버전이다. 수정판6.29.1/10.7.4가 상위 공식 번들에 반영되지 않았다. undici2건은 새 npm 번들 편입으로 추가된 매치이며, 런타임 앱에는 npm이 없다. 프로젝트 `/frontend/node_modules` 매치는0이고 별도 npm audit도0이다.
- Ryuk0.14.0의 Go1.23.12·root/privileged·RW Docker socket 조건은 남는다. 소스의 `ParseQuery` 호출에 연결되는 High 경고도 확인했으며 loopback·기본64KiB 입력 한계를 무영향으로 해석하지 않는다. 공식 새 Ryuk 공급은 확인되지 않았다. UPX 바이너리에 대한 govulncheck 분석 실패도 통과로 처리하지 않는다.
- Debian libxml2·일부 util-linux와 Go 표준 라이브러리 등 공급 대기/검토 항목이 남는다. Java build의 shaded HTTP3행은 최신 상위 공식 번들도 같으며 직접 의존성 교체로 내부 shaded 코드를 고쳤다고 주장하지 않는다. app/migrate Java archive 매치는0이다.
- 7종의 fix 상태 미확인37행·등급 미확인3행·버전 미확인4개를 명시했다. 공급자 현재 조회가 timeout인6개 CVE/18행은 과거 정확 키의 공식 근거와 현재 조회 실패를 구분한다. 원시 매치 전체의 악용 가능성을 확인한 침투 테스트가 아니다.

## 실행 검증

| 범위 | 실제 결과 |
| --- | --- |
| Java·패치 PostgreSQL 전체 | **489개(단위96·통합393), 실패/오류/skip0**. `java-postgres-patched-verify.log`. |
| Linux 프론트 | 보안 Node/npm 이미지에서 외부 네트워크 없이 **95개 통과**. 타입·production 빌드도 성공. |
| Python 도구 | **62개 통과**. JSON·명령·기간/대상·multipart 검증의 RED/GREEN과 독립 리뷰를 포함한다. |
| 최종 Linux 사전 HTTP | 보안24개·multipart fields6개/file6개 통과.60초 smoke와3고객사 명시 재계산·출력·독립 승인·Excel 재다운로드 해시도 통과했다. |
| 최종33분 부하 | **1,980.189초·15,471요청·예상 밖 오류/업무 검증 실패0**, 예상 거부1,049개. 전체 p50 29.378ms·p95 **247.902ms**·max560.434ms. 준비·smoke·중단 표본은 합산하지 않는다. |
| 종료 정합성 | 과거 원천55,800건 해시 유지·당일18명령 각1건·중복0, 승인/연차 차감·잔여 정합성 통과. 초기 Excel3개 해시 불변·최종23개 snapshot 정상. fixture 종료0·소유 PG 자동 정리 확인. |
| 기본 데이터 보존·로컬 반영 | 기본 DB dump202,206bytes와 archive 목록을 검증했다. **업무/이력35개 테이블 전체 행 SHA256·RLS/역할/Flyway·클러스터 ID·볼륨 동일**을 확인하며 일반 보안 PG/app/migrate로 갱신했다. DB 컨테이너 ID는 보안 이미지 적용으로 의도적으로 바뀌었으며 유지됐다고 표시하지 않는다. 재초기화 없이 V10 no-op·migrate 종료0. |
| 최종 실행 확인 | 갱신한 기본 app/DB의 loopback·비루트·cap drop/NNP·앱 관리 자격 부재·테스트 정리 라벨 부재 확인. 기본 HTTP24개 재통과, 검증 JAR/실행 JAR production218개·React HTTP bytes 일치·MIT 포함·이전 Thymeleaf/htmx 실행 자원 부재. |

## 최종 측정 상세

측정 구간은 **2026-10-27 11:02:43.393519~11:35:43.582519 KST**(UTC02:02:43.393519~02:35:43.582519)다.60초 ramp 뒤32분 steady에서15,360요청, 실측 **7.9992요청/초**를 처리했다. steady p95는247.396ms이며 완전한 분 구간마다478~482요청·분 p95 최댓값286.311ms다.21개 연산 모두 실행·실패0이다.

| 자원 | 실제 표본 범위 |
| --- | --- |
| Docker 표본 |132개·최대 간격15.028339초·수집 오류0·OOM/restart0|
| Linux app | CLI 메모리470.1~509.6MiB·PIDs54~58·CPU 표본 최대30.82% |
| 전용 PostgreSQL | CLI 메모리86.78~111.1MiB·PIDs12~15·CPU 표본 최대83.49% |
| 동일 전용 DB SQL |131개 표본·연결 최대7·advisory 대기 최대0·DB 크기 최대53,491,391bytes |

Docker CPU100%는 한 CPU 기준이며 위 값은 순간 표본이다. JVM heap/GC·Linux 파일 디스크립터·풀 대기 지표의 직접 검증은 수행하지 않았다. 이 메모리 범위로 누수 부재를 확정하지 않는다.

최종 원본은 `target/react-soak-security-20261027/patched-db-run/`의 `verified-measurement.json`, `linux-soak.json`, `*.requests.jsonl`, `linux-docker-stats.jsonl`, `fixture-before-soak.json`, `fixture-after-soak.json`, `consistency-verdict.json`이다. 기본 갱신·백업·전체 행/권한 대조는 상위 폴더의 `default-update-proof.json`, `default-backup-proof.json`, `default-maintenance-before.json`, `default-maintenance-after.json`, `default-package-proof.json`에 연결한다. 임시 앱3개/네트워크2개는 소유 라벨·ID 확인 후 정리했고 기본 서비스는 유지했다. 기존 사용자 파일을 삭제하지 않았다.

## 변경 파일과 재실행

- 컨테이너: [Dockerfile](../../Dockerfile), [compose.yaml](../../compose.yaml), [PostgreSQL Dockerfile](../../infra/postgres/Dockerfile).
- 테스트 DB: [DatabaseFixture.java](../../src/test/java/kr/tna/DatabaseFixture.java), [DatabaseFixtureImageTest.java](../../src/test/java/kr/tna/DatabaseFixtureImageTest.java).
- HTTP 도구/회귀: [http-mixed-soak.py](../../scripts/http-mixed-soak.py), [test_http_mixed_soak.py](../../scripts/test_http_mixed_soak.py), [verify-multipart-http.py](../../scripts/verify-multipart-http.py), [test_multipart_http.py](../../scripts/test_multipart_http.py).
- 버전·실행 현황은 README·PRD·개발 안내·ADR-0003·이번 계획/보고서에 연결했다. 기존 React 전환의 미커밋 변경을 보존했다.

전체 Java는 기존 전용 JDK25와 `mvn -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`, Python은 `python -B -m unittest discover -s scripts -p 'test_*.py'`로 실행했다. 프론트95개는 고정 보안 Node 이미지에서 `npm test`를 실제 실행했다. Testcontainers는 공통 PG Dockerfile을 자동 빌드하므로 Docker와 서명된 APT·공식 gosu 다운로드 접근이 필요하다. 원시 fixture CLI·불변 이미지·부하 실행 인자는 ignored 증거의 helper/로그에 보존했다.

OpenSSL 수정 공급을 추가 확인한 첫 측정은 약7분/마지막 관측2,994요청에서 중단했다. 해당 결과는 `final-run/interrupted-trial.json`에 성공한 최종 부하가 아님을 기록했다. 이전 fixture는 정상 종료0이며 새 패치 DB와 새 합성 자료로 재측정했다.

측정은 동일 로컬 호스트의18세션·3고객사×300명·과거 원천55,800건에 대한 HTTP 부하다. 요청 지연은 pacing·클라이언트 잠금/대기열을 제외하고 응답 본문/의미 검증을 포함하며 브라우저 렌더링은 측정하지 않는다. Docker CLI 메모리/PID를 JVM heap/Java thread 수로 해석하지 않으며 기존 native fixture JVM 수치는 Linux 앱 지표에서 제외한다. 운영 용량·SLA·실고객/법적 수용·외부 배포·실메일 검증을 대신하지 않는다.

백업의 실제 복원 리허설·새 침투 테스트·호스트 전체 도구 검사는 수행하지 않았다. 커밋·Git Push는 하지 않았다.
