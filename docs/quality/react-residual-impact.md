# React 잔여 취약점의 서비스 영향과 보완

> 후속 [libxml2 경로 제거](libxml-containment.md)에서 같은 PGDG18.6의 no-XML/no-XSLT/no-LLVM 변형을 빌드·검증하고 로컬 기본 DB에 반영했다. runtime libxml 부재·SQL/binary 거부·Java496개·기존 물리/논리 복원·35테이블 보존을 확인했다. 아래 libxml2 P1·이미지/원장/493개 수치는 이전 구성의 이력이며, 다른 잔여 항목은 계속 관리한다.

기준일: 2026-10-27, Asia/Seoul. [계획](../superpowers/plans/2026-10-27-react-residual-impact.md)에 따라 [이전 이미지 검사](react-soak-security.md)의 2,035행을 전수 대조하고, 가능한 보완과 재검증을 완료했다. **현재 HTTP 입력에서 확인된 직접 악용 경로는 찾지 못했지만, DB의 미수정 XML parser와 테스트 Docker 제어 권한은 남는다.** 외부 배포·실고객 운영 수용 완료를 뜻하지 않는다.

## 이번 조치

- [PostgreSQL 이미지](../../infra/postgres/Dockerfile)에서 서버 실행에 불필요한 GPG·SQLite와 종속 도구 16개를 제거했다. PG·클라이언트·locale·OpenSSL·libxml2·APT/gpgv/keyring은 유지한다. 공식 이미지의 GPG 사용은 빌드 시 gosu 서명·PGDG 키 준비에 해당하며, SQLite의 직접 소비자는 GPG였다. [고정 공식 Dockerfile](https://github.com/docker-library/postgres/blob/e00e1bd34ec5c8a8e7ad89b273b3d42efaf6d5bc/18/bookworm/Dockerfile)
- [이미지 회귀 3개](../../src/test/java/kr/tna/PostgresImageSecurityIT.java)는 제거 전 1실패/2통과 → 제거 후 3통과다. GPG/SQLite 부재, 정상 shared-library 연결, gpgv·locale·gosu, SCRAM·정상 XML, 합성 UTF-8 자료의 실제 custom dump/restore를 검증했다. 별도 관리 컨테이너에서 GPG 없이 서명된 APT 인덱스 검증도 통과했다. 실제 DB 서비스의 비루트·cap_drop ALL·NNP는 유지한다.
- [테스트 설정](../../src/test/resources/testcontainers.properties)의 공식 `ryuk.container.privileged=false`를 적용했다. [실효 설정 회귀](../../src/test/java/kr/tna/RyukLoopbackModifierTest.java)는 RED 1실패 → 최종 6통과이며, 실제 controller inspect의 false와 JVM 강제 종료 후 컨테이너·익명 볼륨 자동 정리, 외부 sentinel·기본 서비스 보존을 확인했다.
- SPI에 false를 넣는 방법은 Ryuk 자체 modifier의 후속 기본값에 덮이는 것을 실측해 채택하지 않았다. [공식 생성 순서](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/containers/GenericContainer.java), [Ryuk 설정](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/utility/RyukContainer.java), [설정 우선순위](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/utility/TestcontainersConfiguration.java)를 따른다. 환경·홈 설정이 classpath보다 우선하므로 다른 개발 환경에서는 실효 설정과 실제 inspect를 다시 확인해야 한다. 기존 loopback SPI는 변경하지 않았다.

기본 로컬 DB에도 **일반 Compose 보완 이미지**를 반영했다. 변경 전 custom dump 202,206bytes와 archive 목록을 검증하고, 변경 전후 **35개 테이블 전체 행 SHA256·역할/RLS/Flyway·클러스터 ID·볼륨 동일**을 확인했다. 기존 DB를 재초기화하지 않았으며 V10 no-op·migrate 종료0, 기본 HTTP 보안24개 재통과다. 테스트 자동 정리 라벨이 있는 이미지를 기본 DB로 재태그하지 않았다.

## 서비스별 영향

| 경계 | 실제 확인과 판단 | 남은 조건·우선순위 |
| --- | --- | --- |
| 앱 런타임 | 실행 JAR의 production 라이브러리62개에서 npm·Testcontainers·DockerJava·Ryuk 부재. 실제 Java 프로세스에 로드된 shared object17개는 JRE·glibc 계열이며 libssl/GnuTLS/PCRE2/Expat는 당시 maps에 없었다. 현재 소스에 외부 XML/ZIP import·사용자 SQL·프로세스 실행·custom JNI·직접 outbound HTTP 기능은 찾지 못했다. | 로드 표본·소스 검색은 모든 동적 실행 경로의 부재 증명이 아니다. libc/DNS·JDK native 소비는 남는다. Java 서비스라는 이유로 OS 경고를 일괄 제외하지 않는다. |
| PostgreSQL libxml2 | **CVE-2026-6653, P1 유지.** 실제 프로세스가 libxml2를 로드하며, 일반 `tna_app` 역할의 정상 XMLPARSE/xpath 실행도 성공했다. 현재 HTTP→해당 parser 입력 경로는 찾지 못했다. | SQL 실행 권한 탈취·추후 기능 추가 시 공유 DB의 메모리 오류가 여러 테넌트에 영향을 줄 수 있다. RLS·외부 entity 차단·부분 함수 REVOKE를 해결로 보지 않는다. bookworm/trixie 수정 미공급이며 외부 배포 전에 수정판 또는 별도 안전 구성 검증이 필요하다. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653), [PG XML](https://www.postgresql.org/docs/18/functions-xml.html) |
| PostgreSQL SQLite/GPG | 이전 SQLite CVE-2025-7458 등을 포함한 불필요 컴포넌트를 현재 실행 이미지에서 제외했다. 제거 뒤에도 초기화·업무 회귀·백업/복원이 통과했다. | 상위 소프트웨어의 CVE 자체가 패치됐다는 뜻이 아니다. 향후 GPG CLI 암호화/키 관리가 필요하면 별도 도구 이미지로 검토한다. [Debian SQLite](https://security-tracker.debian.org/tracker/CVE-2025-7458) |
| glibc·기타 DB native | libc와 XML·압축·LDAP/Kerberos·GnuTLS·LLVM 등의 연결을 확인했다. glibc CVE-2026-5450의 `%mc` 폭1,024 초과 형식은 이전 PG 소스 검색에서 발견하지 못했으나 동적 format·다른 소비자는 미확인이다. | **P2 공급 대기/추가 분석.** SQL 바인딩·SCRAM·loopback·비루트를 parser 메모리 안전성 보증으로 확대하지 않는다. 현재 지원 배포판의 추가 적용 수정판 후보는0이다. [Debian glibc](https://security-tracker.debian.org/tracker/CVE-2026-5450) |
| Node/npm 빌드 | undici6.29.0 2건·ip-address10.7.2 1건이 남는다. 최신 공식 npm12.2.1에도 같은 버전이다. 현재 npm의 undici fetch 소비는 node-gyp 헤더 다운로드이며 Linux 잠금 설치에서 해당 native build script는 없다. EventSource 호출은 없고, Address6 경로에는 net.isIPv6 또는 고정16byte 입력 경계가 있다. | **P2 빌드 공급망.** 현재 긴 악성 문자열 경로 미발견을 npm 전체 무영향으로 바꾸지 않는다. 공식 번들 반영 대기이며 내부 파일 강제 교체는 하지 않았다. [fetch](https://github.com/nodejs/undici/security/advisories/GHSA-xjj6-f33q-929c), [EventSource](https://github.com/nodejs/undici/security/advisories/GHSA-4qv8-2p9j-2685), [Address6](https://github.com/beaugunderson/ip-address/security/advisories/GHSA-rxp7-6hxx-x8q5) |
| Java 빌드/테스트 | DockerJava3.7.1의 shaded Apache HTTP3행이다. 실제 로컬 named pipe·classic HTTP1을 재확인했다. H2 취약 경로는 현재 transport에서 사용하지 않으며 압축 처리의 ignoreUnknown=true도 bytecode로 대조했다. | **P2 개발 환경.** HTTP1 헤더 DoS는 Docker 응답 경계에 남는다. 일반 dependency override로 내부 shaded 코드를 교체했다고 볼 수 없다. 앱 JAR에는 해당 archive가 없다. |
| Ryuk/Go | `url.ParseQuery`가 실제 TCP 필터 입력에서 호출된다. privileged=false를 검증했으나 **root·RW Docker socket·미수정 Go 바이너리**는 남는다. 한 줄64KiB 제한은 총 필터 누적·모든 DoS의 해결책이 아니다. | **P2 테스트 호스트.** 공식 Ryuk0.14.0은 여전히 같은 Go1.23.12 빌드다. loopback·로컬 접근 제한을 유지하며 기능을 꺼서 자동 정리를 없애지 않는다. 서비스 앱 이미지에는 없지만 개발 Docker 권한에 실제 영향이 있다. |
| gosu/Go | Compose는 postgres 사용자로 시작하므로 일반 요청의 gosu 경로는 없다. 별도 Testcontainers root 초기화에서는 사용자·group 해석 후 setuid/exec에 사용한다. listener는 없다. 구체 Windows 전용 심볼 경고는 Linux 조건과 구분했다. | **P2 시작/로컬 입력.** 공식 gosu1.20은 Go1.24.6이며 현재 보안 지원 major가 아니다. 지원 Go로 공식 소스를 자체 재빌드하는 대안은 가능하나 새 공급물의 provenance·재현성·권한 강하·라이선스·자동 정리 검증과 유지가 별도 필요해 이번 즉시 교체안으로 채택하지 않았다. [gosu](https://github.com/tianon/gosu/releases/tag/1.20), [Go 지원 정책](https://go.dev/doc/devel/release) |

Ryuk의 Docker SDK5행은 daemon AuthZ/plugin/archive 구현에 대한 공지다. reaper의 client list/remove 사용을 daemon 취약 코드 실행과 동일시하지 않았다. 실제 로컬 Engine은 **29.9.2**로 해당 공지의 수정 버전29.3.1/29.5.1보다 높다. 이는 호스트 전체 취약점 검사가 아니다. [AuthZ 공지](https://github.com/moby/moby/security/advisories/GHSA-x744-4wpc-v9h2), [plugin 공지](https://github.com/moby/moby/security/advisories/GHSA-pxq6-2prw-chj9), [archive 공지](https://github.com/moby/moby/security/advisories/GHSA-x86f-5xw2-fm2r), [copy 공지1](https://github.com/moby/moby/security/advisories/GHSA-rg2x-37c3-w2rh), [copy 공지2](https://github.com/moby/moby/security/advisories/GHSA-vp62-88p7-qqf5).

## 전수 판정과 검사 차분

OS1,845행·300패키지 그룹·239개 CVE와 도구190행을 모두 원본 키/값에 연결했다. **기준2,035행 전체의 추가 판정은 [CSV](react-residual-impact-20261027.csv)**에 있으며 이전 원장은 그대로 보존했다. CSV의 removed124행은 현재 PG 이미지에 해당 패키지가 없음을 뜻한다. retained1,911행은 원시 매치 유지이며 실제 악용 가능 판정과 다르다.

OS 기준 판정은 공식 수정 적용214행·비적용3행·제품 오매치2행·수정 미공급1,626행이다. OSV의 수정판 없는 범위는 평가 대기/needed/ignored/deferred를 모두 세분할 수 없어 미공급으로 남겼다. 설치판과 대응되는 지원 배포판 수정판의 **미적용 후보0·공식 source 누락0·현재 조회 timeout0**이다. 이전 조회 실패 OpenSSL6개 CVE도 공식 수정 revision과 대조했다. Noble util-linux CVE-2026-3184의 code-not-compiled와 sudo CVE의 krb5 CPE 오매치는 raw 경고를 삭제하지 않고 별도 판정했다.

근거는 Canonical 공식 OSV 고정 commit `7cb53f602c56883d774cccb64ddfc9741b8e6f74`의134개 CVE와 Debian tracker 원문·개별 공식 CVE 자료다. URL·조회 UTC·SHA256을 보존했다. Canonical OSV 자료는 [공식 저장소](https://github.com/canonical/ubuntu-security-notices)의 CC-BY-SA-4.0 출처를 따르며 [상태 규약](https://documentation.ubuntu.com/security/security-updates/osv/)과 [Debian 데이터](https://security-tracker.debian.org/tracker/data/json)를 구분한다. 공급자 판정은 현재 서비스 도달성 검증과 별개다.

| 이미지 역할 | 현재 고정 ID 앞부분 | 원시 행 | 이번 검사 |
| --- | --- | ---: | --- |
| app | `fc03ce3a97ca` |140|이미지 변경 없음·이전 고정 검사와 현재 실행 경계 대조|
| migrate | `a2a96271ddc0` |140|동일|
| Java build | `0429dc4d0e8e` |455|저장된 이미지 변경 없음·shaded 경로 추가 분석|
| Node frontend build | `9f7e4a496d68` |240|변경 없음·npm 소비 경로 추가 분석|
| 일반 PostgreSQL | `ea49400786df` |436|새 SBOM·원시 검사|
| Ryuk | `7c1a8a9a47c7` |64|바이너리 변경 없음·실제 실행 권한/정리 재검증|
| HTTP 전용 PostgreSQL | `b25dceca96d1` |436|새 SBOM·원시 검사|

변경 PG2종은 각각 **160→144패키지·498→436행**이며 새로운 raw 매치0을 확인했다. 같은7종 역할의 합계는 **2,035→1,911행**(C38/H469/M897/L194/N310/U3)이다. 이미지 중복과 package/CPE 중복이 있으므로 **124개의 서로 다른 CVE를 수정했다는 뜻이 아니다.** 그 밖의5종 전체 스캔을 새로 실행했다고 표현하지 않는다.

비교는 이전과 같은 Syft1.55.0·Grype0.121.0·고정 DBv6.1.10(built2026-10-26T06:38:44Z)다. DB archive SHA256은 `214b59fbe13608346ee1b22583ec0531083d6088c33891f3520d0fbdedb1b308`이며 ignore/VEX0, 외부 SBOM/이미지 업로드0이다. OCI 식별 사슬·archive/SBOM/원시 결과 SHA와 변경 전후 매치 키를 검증했다.

## 실제 검증

| 범위 | 결과 |
| --- | --- |
| 최종 Java 전체 | **493개(단위97·PostgreSQL 통합396), 실패/오류/skip0**, BUILD SUCCESS. 최종 공식 Ryuk 설정·공통 보완 PG Dockerfile 사용. |
| 도구 회귀 | **Python62개 통과**. 기본 셸의 구형 javac로 실패한 시도는 보존하고, 프로젝트 전용 JDK25를 해당 프로세스에 지정한 전체 재실행을 최종 결과로 사용했다. 공급자 자료 판독 자체검증4개도 통과했다. |
| Linux 실제 HTTP | 보안24개·multipart fields6개/file6개, **60.006초 smoke189요청 예상 밖 오류0**, 3고객사 명시 재계산·출력·독립 승인·동일 Excel 재다운로드 해시 통과. 처리 용량 측정으로 사용하지 않는다. |
| 종료 DB 정합성 | 합성 과거55,800건·당일6개 고유 명령, 중복0·원장/승인 정합성·유효 snapshot 확인. 당일 명령 수는60초 smoke의 실제 수행량이며 이전33분의18개와 합산하지 않는다. fixture 정상 종료0·소유 PG 자동 제거. |
| 실제 Ryuk | 공식 설정 후 privileged=false·loopback 게시, JVM halt 후 대상 컨테이너·익명 볼륨1개 자동 제거, 외부 sentinel·기본 서비스·홈 설정 hash 보존. 최종 전체 Maven 정상 종료에서도 전용 DB 정리. |
| 기본 로컬 반영 | 백업·35개 테이블 전체 행/권한/클러스터/볼륨 보존, 비루트·NNP·cap drop·loopback·관리 자격 부재·테스트 정리 라벨 부재, 기본 HTTP24개 재통과. 작업 소유 임시 컨테이너3개·네트워크1개 정리. |

이번 변경은 PG 패키지와 테스트 전용 설정/회귀에 한정한다. 프론트·앱 업무 코드·잠금 의존성은 변경하지 않았다. 프론트95개와33분15,471요청은 [직전 검증](react-soak-security.md)의 결과이며 이번에 재실행하지 않았다. 호스트 전역 도구·신뢰 설정·외부 배포·메일·커밋·Push도 수행하지 않았다.

전체 exploit·native 동적 호출 추적·호스트/커널 침투·기본 DB 전체 복원 리허설은 수행하지 않았다. 새 이미지에서는 전용 합성 DB의 실제 dump/restore를 검증했으며 기본 DB 백업 목록 검증과 구분한다. 공급 대기 libxml2와 Go 도구 바이너리의 미수정 상태를 종료된 취약점으로 표시하지 않는다.

원시 근거는 ignored `target/react-residual-impact-20261027/`의 `runtime-boundary.json`, `os/`·`tools/`, `impact-proof.json`, `scan/`, `java-final-verify.log`, `python-jdk25-final.log`, `http-probe/consistency-verdict.json`, `ryuk-final/actual-verdict.json`, `signed-apt-proof.json`, `default-update-proof.json`, `owned-cleanup-proof.json`에 보존한다.
