# OS native 입력·Docker 클라이언트 경계 분석

## 판정과 시점

**2026-10-25 23:30~23:43 KST(14:30~14:43 UTC)의 분석 기록**이다. [공급 재확인](security-supply-recheck.md)·[gosu 분석](gosu-binary-analysis.md)에 이어 실제 앱/DB의 native 의존과 개발용 Docker 응답 입력 경계를 좁혔다. 작성·후속 보완이 10월 26일로 이어졌어도 아래 측정 시각과 결과를 바꾸지 않는다.

**P1 JDK·공유 DB libxml2는 미해결이며, 이번 결과는 패치·배포 수용 완료가 아니다.** 직접 입력 경로 미발견, ELF의 직접 연결, 취약 함수 조건과 실제 실행을 구분한다. 원시 취약점 [1,294행 원장](vulnerability-review.csv)과 등급은 수정하지 않았다.

분석 당시 파일 업로드 핸들러는 없었지만 Boot multipart 자동설정은 기본 활성일 수 있음을 발견했다. 실제 bean·HTTP 확인과 비활성화는 [별도 보완 계획](../superpowers/plans/2026-10-25-multipart-boundary.md)의 후속 작업이다. 아래 소스 검색을 후속 수정 이후의 상태로 해석하지 않는다.

## 실제 입력과 실행물

| 대상 | 확인 범위와 보존한 차이 |
| --- | --- |
| 실행 중 app | 컨테이너의 image ID는 `547d9643…`다. 해당 ID의 로컬 image inspect는 확보하지 못했고 app/migrate의 현재 태그는 각각 `e62a…`/`6818…`로 달랐다. 태그를 실행물과 동일시하거나 retag·재시작하지 않았다. 실행 중 app에서 선택 파일만 `docker cp`로 읽었다. |
| app 선택 ELF | JRE 9개와 OS 라이브러리 7개, 총 16개를 판독했다. 처음 symlink 4개는 `docker cp -L`로 실패했고, resolved 실파일 경로에서 다시 읽어 성공했다. 실패·성공·각 SHA256을 모두 보존했다. |
| PostgreSQL | 고정 `postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc`의 기존 로컬 archive를 분석했다. 13개 layer와 whiteout 2개를 반영한 고유 ELF 943개이며 미지원 ELF는 0개다. |
| Docker 진단 classpath | 이전에 해석·기록한 프로젝트 JAR 126개와 main/test resources를 사용했다. 이번에 Maven 전체 의존성을 새로 resolve한 결과나 현재 CI의 모든 실행 설정을 증명한 결과는 아니다. |

app의 9개는 `java`·`libjava`·`libnet`·`libnio`·`libzip`·`libjimage`·`libverify`·`libjsig`·`libjvm`이다. OS 7개는 libc·zlib·OpenSSL의 libssl/libcrypto·GnuTLS·PCRE2·Expat다. migrate·builder 전체 파일시스템이나 app의 모든 ELF를 이번에 분석한 것으로 확대하지 않는다.

## 애플리케이션 소스와 native 소비

src/main 114개를 포함한 총 129개 파일을 13개 검색 범주와 관련 경로 정독으로 확인했다. 일반 JDK/MyBatis/PG 근거 7개와 multipart 근거 3개, 공식 자료 10개를 대조했다. 전이 의존성 전체 구현·실제 loaded maps·syscall의 전수 도달성 분석은 아니다.

| 입력 경계 | 소스 근거와 판단 |
| --- | --- |
| IP·DNS | [허용 IP 처리](../../src/main/java/kr/tna/clock/AttendanceSettingsService.java)는 `ofLiteral`·`getByAddress`를 사용한다. 이 업무 IP 경로의 hostname 조회와 JDBC의 설정 hostname `db`에 대한 resolver 사용은 별개다. 후자는 libc/DNS 소비 경계로 남는다. |
| Excel·압축 | [ReviewWorkbook](../../src/main/java/kr/tna/worktime/ReviewWorkbook.java)은 서버가 만든 XML을 `ZipOutputStream`으로 출력한다. XML/ZIP import 직접 경로는 찾지 못했다. [OpenJDK Deflater 구현](https://github.com/openjdk/jdk25u/blob/jdk-25.0.4.1%2B1/src/java.base/share/native/libzip/Deflater.c)은 deflate 계열이며 OS zlib의 gzwrite/gzprintf 취약 조건과 동일시하지 않는다. |
| SQL 입력 | Mapper의 값 바인딩과 고정 SQL을 확인했고 고객 SQL/XML 본문 조합은 찾지 못했다. 정상 IP/CIDR·JSON·문자열도 PostgreSQL의 inet/jsonb/regex 등 native 입력이다. [MyBatis 바인딩](https://mybatis.org/mybatis-3/sqlmap-xml.html)·RLS는 parser의 메모리 안전성을 입증하지 않는다. |
| 외부 URL·명령 | 업무 Java에서 직접 outbound HTTP/SMTP·프로세스 실행·custom JNI 호출을 찾지 못했다. Maven/Wrapper의 공개 artifact 다운로드·압축 해제·curl/apt와 관리자 CLI는 실제 공급망 입력 경계로 남는다. |
| multipart | 업무 `MultipartFile`·`Part`·파일 input은 미발견이었다. 그러나 Boot 4.1.2의 조건은 `matchIfMissing=true`이고 기본 enabled=true다. 당시 resolver/Servlet 설정 bean·CSRF 처리 순서·tempfile 동작은 실측하지 않았다. 업로드 핸들러 부재만으로 자동 parser 비활성을 주장하지 않는다. |

multipart의 공식 [자동설정](https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-servlet/src/main/java/org/springframework/boot/servlet/autoconfigure/MultipartAutoConfiguration.java)과 [속성 안내](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)·[Framework 처리 경계](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/multipart.html)를 대조했다. 이 사실만으로 native XML/ZIP parser가 고객 입력에서 호출된다고 판정하지도 않는다.

선택 JRE 9개의 `DT_NEEDED`에 libssl/libcrypto·GnuTLS·PCRE2·Expat·OS libz의 직접 의존은 없었다. 반면 libnet은 `getaddrinfo`·`getnameinfo`를, libjava/libnet/libjvm은 `dlopen`을 import한다. [JDK 주소 해석](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html), NSS/provider·동적 로딩·JDK 내부 압축·다른 native 소비자는 남으며 직접 연결 부재를 전체 무영향으로 바꾸지 않는다.

## PostgreSQL의 남은 native 조건

공식 [PG 18.6 소스](https://ftp.postgresql.org/pub/source/v18.6/)와 checksum, 설치 Debian revision의 소스·공식 보안 기록을 실제 ELF import와 대조했다. 아래는 이번에 SQL을 실행하거나 악성 입력으로 검증한 결과가 아니다.

| 항목 | 확인·판정과 남은 조건 |
| --- | --- |
| libxml2 / CVE-2026-6653 | postgres의 libxml2 연결·`xmlCtxtReadDoc/Memory` import와 [SQL XML 경로](https://www.postgresql.org/docs/18/functions-xml.html)를 확인했다. 내부 DTD/엔티티 처리는 외부 entity 차단과 다르다. 일반 역할의 정상 XMLPARSE 접근은 기존 실행 증거이며 이번에 재실행하지 않았다. bookworm [공급 대기](https://security-tracker.debian.org/tracker/CVE-2026-6653), **P1 유지**. |
| glibc / CVE-2026-5450 | libc·scanf import는 존재한다. 취약 조건은 `%mc`의 명시 폭이 1024를 넘는 경우다. PG 소스 2,294파일·scanf 텍스트 90건에서 해당 폭 형식을 찾지 못했다. ICU data의 `%mc` 문자열은 실제 format 호출 증거가 아니다. 동적 format·다른 native 소비자는 미확인으로 [P2 공급 대기·추가 분석](https://security-tracker.debian.org/tracker/CVE-2026-5450)을 유지한다. |
| SQLite / CVE-2025-7458 | 943개 ELF 중 libsqlite3의 직접 소비자는 `usr/bin/gpg` 1개다. 고객 HTTP→SQLite SQL 경로는 찾지 못했다. GPG의 TOFU·외부 trustdb/관리 입력과 dlopen·정적 코드 전체는 별도다. [공급 대기](https://security-tracker.debian.org/tracker/CVE-2025-7458), **P2 유지**. |
| Debian zlib / CVE-2026-85091 | PG의 deflate와 libxml의 gzwrite 소비는 존재하지만 gzprintf/gzvprintf 직접 importer는 없었다. 설치 1.2.13 소스에는 CVE의 gz_vacate/nonblocking 도입 경로가 없고 upstream 도입은 1.3.1.2다. [tracker의 vulnerable 판정과 도입 범위 불일치](https://security-tracker.debian.org/tracker/CVE-2026-85091)를 보존하고 동등 경로·공급자 재분류를 **P2 추가 분석**으로 둔다. |

Python ELF 판독기의 6개 검증을 별도로 재실행했다. 독립 Go `debug/elf` 구현으로 943개 전부의 SHA256과 `DT_NEEDED`·import·export를 대조한 차이는 0개다. 이 일치는 두 정적 판독기의 결과에 한정되며 exploit 부재·모든 런타임 호출의 부재를 뜻하지 않는다.

## Docker endpoint·프로토콜과 잔여 3건

TC 2.0.5의 선택 우선순위는 tc.host → environment/user 설정 → persistable 지정 전략 → SPI 우선순위다. DockerJava 설정의 환경·JVM property·사용자 파일·classpath와 context 해석도 영향을 줄 수 있다. Docker CLI context 하나나 DOCKER_HOST의 유무만으로 실제 Testcontainers 선택을 판단하지 않는다.

[고정 전략 소스](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/dockerclient/DockerClientProviderStrategy.java)는 성공 시 사용자 설정 저장을 호출할 수 있고 DockerMachine은 VM 시작 경로가 있다. [Factory.client()](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/DockerClientFactory.java)는 ResourceReaper/Ryuk 초기화도 하므로 사용하지 않았다.

전용 Windows 프로브에서 검토한 선택 순서 부분만 재현했다. unknown SPI/전략·TCP/remote/비정상 pipe를 접속 전에 거부하고 local named pipe의 socket·ping·info만 호출했다. Npipe 실패 뒤 DockerMachine fallback이나 설정 저장은 실행하지 않도록 했다.

| 실제 진단 | 결과 |
| --- | --- |
| URI 경계 self-test | 허용 2개·거부 10개, 종료 0 |
| 선택·응답 | `NpipeSocketClientProviderStrategy`·local named pipe·Linux daemon, ping/info 성공·종료 0 |
| 전송 구현 | `httpclient5_zerodep`·classic HTTP1. 고정 구현과 선택 transport에 근거하며 패킷 캡처 결과는 아님 |
| 설정 보존 | `.testcontainers.properties`의 전후 SHA256 동일. 자격·context URI 원문·daemon info 본문은 출력하지 않음 |

이 프로브는 실제 로컬 연결을 확인했지만 Factory 전체의 자동 선택을 실행한 것이 아니며 CI/Testcontainers의 향후 실행을 강제로 제한하는 guard도 아니다. classpath·사용자 설정·endpoint/transport가 바뀌면 재확인해야 한다.

| 현재 shaded 사본 | 판단 |
| --- | --- |
| Core 5.3.6 / CVE-2026-54399 | classic HTTP1 parser의 기본 line/header 상한은 -1이다. 입력 공급자는 현재 로컬 daemon이지만 악성 응답·변조 daemon 가능성을 제거한 패치는 아니다. **P2 미해결**. |
| Client 5.5.1 / CVE-2026-64607 | classic 압축 응답 처리 체인이 기본 활성이다. malformed Content-Encoding의 연결 반환 문제를 이번에 악용 재현하지 않았다. **P2 미해결**. |
| Core-h2 5.3.6 / CVE-2026-54428 | bytecode 존재와 현재 classic HTTP1 경로를 구분한다. HTTP2/transport 변경 전에 재평가하는 **P3 조건 유지**. |

공식 Apache 기록은 [54399](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/54xxx/CVE-2026-54399.json)·[64607](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/64xxx/CVE-2026-64607.json)·[54428](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/54xxx/CVE-2026-54428.json)과 대조했다. 일반 HTTP 의존성 override는 relocated 사본을 교체하지 않는다. 새 공개 호환 공급 여부는 [공급 보고서](security-supply-recheck.md)의 별도 결과를 따른다.

## 검증 기록·재검토 조건

이번 실행은 Go 1.27.3 `debug/elf` 보조 도구의 TDD 3개 RED→GREEN, PG 판독기 6개 재검증, ELF 943개 독립 대조, app 16개 선택 파일 판독과 Docker 프로브다. **새 애플리케이션 회귀·HTTP 보안 점검·부하·SBOM/취약점 스캔은 실행하지 않았다.** 과거 396개·HTTP24개·33분 결과를 이번 검증으로 재사용하지 않는다.

외부 서버 접속·SQL/DB 작업·컨테이너 생성/시작·이미지 교체·자격/설정 변경은 하지 않았다. 공개 공식 소스 다운로드와 기존 로컬 Docker API의 읽기·파일 복사만 사용했다. live 파일 변경 여부·전체 loaded maps·syscall·악성 daemon/문서·장애 주입·호스트/엔진 취약점은 검증하지 않았다.

남은 P1은 공식 JDK·bookworm libxml2 수정 공급/검증 후 다시 판단한다. multipart는 위 후속 계획에서 실제 설정·bean 경계를 확인한다. DNS/NSS·native parser·업로드·외부 URL/명령·DB 확장·Docker endpoint/transport를 추가하거나 변경하기 전에 해당 조건을 다시 평가한다.

재현 자료는 ignored `target/native-boundary-20261025/`에 보존한다. `app-source/boundary-summary.json`·`official-sources.json`·`multipart-official-sources.json`, `app-elf/manifest.json`·`resolved-files.json`·`elf-evidence-complete.json`, `postgres/native-findings.json`·`elf-image-scan.json`·`source-*-manifest.json`, `elf/crosscheck-postgres.json`·RED/GREEN/PG 검증 로그, `docker/probe-result.json`·`self-test-result.json`·classpath/source manifest가 근거다. 원본 소스·ELF·설정·도구와 응답을 저장소에 추가하지 않는다.
