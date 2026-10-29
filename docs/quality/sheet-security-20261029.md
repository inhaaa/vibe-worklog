# 공통 시트·V14 전체 취약점 검사 (2026-10-29)

기준일: 2026-10-29, Asia/Seoul. 최신 로컬 제품 이미지와 실제 Java·HTTP 검증용 PostgreSQL을 검사했다. **최종 실효9역할의 원시 매치는2,676행이며, 현재 지원 배포판에서 확인한 추가 적용 패치 후보는0이다. 미수정 OS·개발 도구 위험은 남는다.** 이 문서는 보안 검사 결과만 다룬다. 33분 부하 성적은 별도 결과를 따르며, 이 보안 보고서가 운영 수용·출시 완료를 뜻하지 않는다.

## 범위와 재현성

Syft1.55.0·Grype0.121.0 Windows amd64를 재사용했다. [공식 Syft 배포](https://github.com/anchore/syft/releases/tag/v1.55.0)와 [공식 Grype 배포](https://github.com/anchore/grype/releases/tag/v0.121.0)의 ZIP SHA256, 실제 실행 버전과 binary SHA를 대조했다. 새 전용 Grype DB를 내려받아 검증·고정했고 이전 DB는 변경하지 않았다.

- DB: v6.1.10, built `2026-10-28T06:23:30Z`.
- 공식 배포 archive SHA256: `b08902b91738f1a9bc7fd61942f331d8e5e76a5df0c0b5f8c17c18bd787ee278`.
- 실제 DB SHA256: `a9c4aadd4632853820728e07c2e345d6dde3e3ca329a101d843be381c4cf7b53`.
- 로컬 `docker image save` → Syft JSON → Grype 순서로 squashed 파일시스템을 검사했다. 각 OCI index→manifest→config 사슬, archive/SBOM/raw SHA256과 실제 image ID를 확인했다.
- 원격 패키지 보강·DB 자동 갱신은 비활성화했다. 사용자 ignore/VEX와 실제 ignoredMatches는0이다. 소스·JAR·SBOM·이미지 외부 업로드는 없었다.
- 자식 Go 프로세스에만 `GOMAXPROCS=2`, `GOMEMLIMIT=1536MiB`를 적용하고 이미지별로 순차 실행했다. GOMEMLIMIT은 Go의 soft target이며 OS 강제 메모리 상한·실측 peak가 아니다. steady 부하와 대형 scan을 겹치지 않았다. 실제 시트 probe용 PG 추가1종은33분 부하 종료 뒤 검사했다.

## 최종 이미지와 검사 이력

| 최종 실효 역할 | 로컬 image ID 앞부분 | 패키지 | 원시 행 |
| --- | --- | ---: | ---: |
| app | `b8e5545677b9` |183|139|
| migrate | `c098d8fabc68` |183|139|
| Java build | `f5316894bae7` |450|455|
| Node frontend build | `126afa4b4d86` |356|243|
| 일반 PostgreSQL | `b997fdd17e59` |137|409|
| Ryuk | `7c1a8a9a47c7` |21|64|
| Java 회귀 PostgreSQL | `417d0f0f40ff` |137|409|
| 실제 r2 HTTP PostgreSQL | `847ee5e0201b` |137|409|
| 실제 시트 HTTP PostgreSQL | `f25d8a72aa43` |137|409|

최종 합계는 **2,676행(C52/H720/M1141/L232/N526/U5)**이다. 유일 primary vulnerability ID는289개이며 CVE·GHSA 등이 섞여 있어 **고유 CVE289개로 표현하지 않는다.** 이미지·패키지·같은 취약점의 반복 매치를 포함한다.

처음7역할은1,858행이다. 실제 시트 HTTP 검증의 추가 PG `f25d8a72aa43`도 새로 export/Syft/Grype 검사해137패키지·409행을 확인했다. r2와 모든 package/source/version·원시 키/중복수가 일치해 공식 분류를 정확 키로 연결했다. 실패한 사전 smoke의 HTTP PG `f011f5a1706c`도409행을 검사했지만 최종 실효 대상에서 제외하고 이력으로 보존했다. r2는 RootFS가 달라 결과를 재사용하지 않고 새로 전체 검사했다. 따라서 **모든10개 검사 이력은3,085행**, 실패 trial을 제외한 **최종 실효9역할은2,676행**이다. 기존9종 manifest는 변경하지 않았다. r1/r2의 raw 매치 키·중복수와 SBOM package/source/version 좌표는 일치하며 새 CVE/버전 차이는0이다. 이 일치를 바이너리 바이트 동일성으로 확대하지 않는다.

모든 export/Syft/Grype는 exit0이다. r2 검사는 `2026-10-29T04:46:04Z`, 시트 HTTP PG 추가 검사는 `2026-10-29T05:26:29Z`에 끝났다. 후자는33분 실측 종료 후 별도 기능 검증을 위해 수행했다. 이전 최신 no-XML7역할1,881행과 현재 같은7역할1,858행의 차이에는 DB 갱신 효과가 섞인다. 이미 적용했던 Brotli3행·PG OpenSSL24행이 새 DB에서 사라졌고, Maven Guava1행·npm 내부 undici3행이 추가됐다. 이번에 새 제품 패치로27개 취약점을 고친 결과가 아니다.

## Java 전체 범위와 npm

Java 기본 Maven 전체 scope의120개 의존성·167개 해석 plugin JAR·Maven3.9.16 lib/boot52개 JAR을 수집했다. 누락/미지원 실체0, 라이선스 CSV 좌표·scope 일치다. Wrapper3.3.4 only-script와 고정 배포 checksum, 소스 vendored htmx2.0.11의 identity/hash도 확인했다. htmx는 실행 JAR 자원에서 제외된다. 수집 시점 소스는 `bf93fcc`이며 이후 로컬 이미지의 빌드·내용 대조 근거와 구분한다.

| 검사 | 원시 매치 |
| --- | ---: |
| 실제 프로젝트 dependency archive |3 — DockerJava 내부 shaded HTTP 사본|
| 실제 plugin archive |4 — Maven Site의 Jetty|
| 실제 Maven 배포 archive |1 — Guava|
| dependency / plugin / supplemental PURL |0 / 4 / 0|

archive8행과 PURL4행은 중복 범위이므로12개 독립 취약점으로 합산하지 않는다. 위 Java 범위와 이미지2,676행도 서로 겹쳐 합산하지 않는다. app/migrate Java archive 매치는0이며 OS·전체 이미지의 무결점을 뜻하지 않는다.

`npm --prefix frontend audit --omit=dev --json`과 `--include=dev --json`은 모두 exit0·취약점 total0이다. 최종 Node 이미지의 프로젝트 `/frontend/node_modules` 매치도0이다. 아래 global npm 내부 라이브러리6행은 이 결과와 별도다.

## 공식 공급 대기와 잔여 위험

현재 [Canonical 공식 OSV](https://github.com/canonical/ubuntu-security-notices)의 고정 commit `27e2dda41762ed2cdc861573fee492e782c8b0dd`에 있는 해당133개 CVE를 모두 HTTP200으로 수집하고, [Debian 공식 tracker](https://security-tracker.debian.org/tracker/data/json) 원문과 함께 OS2,362행을 전수 대조했다. 공식 수정 설치217행·Noble code-not-compiled 비적용3행·sudo/krb5 제품 오매치4행·미수정/수정 미공급2,138행이다. 매핑 누락0·현재 지원 배포판의 추가 적용 패치 후보0이다. [CVE-2026-3184 공식 Ubuntu tracker](https://git.launchpad.net/ubuntu-cve-tracker/plain/active/CVE-2026-3184)는 Noble `not-affected (code not compiled)`를 재확인했다. 원시 경고는 삭제하지 않았다.

OSV에 fixed 범위가 없는 항목은 평가 대기·needed·ignored·deferred를 모두 세분하지 못하므로 미공급으로 남긴다. scanner의 fixed 표기만으로 현재 배포판에 설치 가능한 수정판이라고 판단하지 않는다. glibc·native 압축/인증·CLI 등의 미수정 경고는 실제 소비자·입력 조건과 계속 구분한다. 현재 PG는 no-XML/no-XSLT/no-LLVM 구성으로 libxml2·XML parser 경로를 제거한 상태이며, upstream libxml2 자체의 패치 완료를 의미하지 않는다.

도구314행(Java4/npm6/Go304)은 다음 공식 번들 공급 및 입력 조건에 연결했다.

- **새 Maven Guava:** `CVE-2026-102554` / `GHSA-xxph-c9ww-hj94`. Maven3.9.16의 Guava33.6.0-jre에서 검출됐다. Grype는 High, [Guava 공식 공지](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94)는 Moderate5.9다. 공격자가 제어하는 ObjectInputStream 역직렬화에 따른 메모리 고갈이 조건이다. 공식 수정은33.7.2이나 [Maven 공식 안정3.10.0](https://maven.apache.org/download.cgi)도 source POM·공식 SHA512 검증 ZIP에33.7.1-jre를 포함한다. 해결 없는 minor 전환·내부 JAR 교체를 하지 않고 공식 Maven bundle 수정 공급을 기다린다. 앱의 직접 Guava 의존성은 없으며 이것이 Maven 모든 입력의 무영향 증명은 아니다.
- **npm 내부 undici:** 기존2행에 새3행이 추가돼5행이다. 새 `CVE-2026-109932`는 [WebSocket 자동 PONG 버퍼 DoS](https://github.com/nodejs/undici/security/advisories/GHSA-9fm3-97px-rjf6), `CVE-2026-87018`은 [TLS1.2 인증서 변경 재협상 DoS](https://github.com/nodejs/undici/security/advisories/GHSA-qrxm-fqhp-jwvm), `CVE-2026-109383`은 [Agent의 함수형 tls 검증 옵션 소실](https://github.com/nodejs/undici/security/advisories/GHSA-2m5v-pch6-74hx)이다. 수정판은6.29.1이다. [공식 npm registry](https://registry.npmjs.org/npm)의11.x 안정 버전 전체와 dist-tag를 확인한 최신은 현재 고정11.21.1이며 새11.x patch는 없다. 최신12.2.1도 undici6.29.0을 포함한다. 두 공식 tarball의 현재 integrity와 로컬 실체를 대조했다. 문제 해결 없는 major 전환·내부 강제 교체는 하지 않았다.
- **npm ip-address:**10.7.2의 기존1행이 남는다. 공식 최신 npm 번들도 같은 버전이라 bundle 공급 대기다. 빌드 도구 내부 문자열 입력과 서비스 HTTP 입력을 구분한다.
- **DockerJava·Site:** 공식 현재 DockerJava3.7.1/Testcontainers2.0.5의 shaded HTTP3행은 이미지 Java4행 중 일부다. Site 안정3.22.0의 Jetty9.4.58 관련4행은 별도 plugin inventory 검사 결과이며 이미지 합계에 합산하지 않는다. 독립 HTTP 라이브러리 override는 shaded 사본의 수정이 아니다. 공개 Jetty9.4 호환 수정판 공급과 Site 실행/프로토콜 조건을 계속 추적한다.
- **Ryuk·gosu/Go:** 공식 최신 Ryuk0.14.0·gosu1.20의 미수정 Go 코드 경고를 유지한다. Ryuk의 root·RW Docker socket과 실제 ParseQuery 입력, gosu의 테스트 root 초기화·권한 강하 입력은 서로 다른 경계다. loopback·privileged=false·일반 PG 비루트 시작은 모든 native/parser 위험의 해결을 뜻하지 않는다.

패키지 매칭은 악용 가능성·동적 native 호출·침투 테스트를 입증하지 않는다. 기존 입력 경계 분석을 참조했으며 새 악성 exploit이나 호스트/커널 전체 검사는 수행하지 않았다.

## 실제 공개 배포와의 내용 대조

스캔한 로컬 image ID와 공개 VPS image ID는 다르다. 공개 실제 app은 `78bbfe3e96f6`, migrate는 `5aef6b0a9bb2`, PG는 `8c6816275f29`다. 별도 읽기 inventory와 `build/deployed-comparison.json`에서 로컬/공개의 설치 패키지 목록·선별 JRE 파일5개·PG/gosu/dump/restore4개 SHA가 일치했다.

외부 JAR ZIP SHA는 다르지만 **JAR480개 entry의 raw 바이트 manifest, 전체 클래스·production62개 JAR은 동일**했다. raw entry manifest SHA256은 `3cfe2830ed51e935e412d444fc75e75e216c7e80b5fed3b92020a339a22d9542`다. 이는 제품·의존성·확인한 native 파일의 동일 범위를 대조한 근거이며, 공개 VPS 이미지 전체를 새로 export/scan한 결과는 아니다. 이번 보안 단위에서 제품 코드·버전·runtime 설정 변경은 없었다. 추가 배포를 요구하는 제품·runtime 수정은 없어 재배포하지 않고 공개 `12f0f78`·V14를 유지했다. 새33분 부하·시트135항목·공개72+4항목과 보존 검증은 별도 통합 보고서에 기록한다.

## 증거와 해시

원본 기준 경로는 ignored `target/sheet-soak-security-20261029/`다. 최종 실효 범위는 `scan/final-nine-role-*`, 모든 시도는 `scan/all-ten-scan-attempts-*`, r1/r2 대체 관계와 기존 manifest 보존은 `scan/r2-scan-verdict.json`과 `scan/sheet-probe-scan-verdict.json`을 따른다. OS·도구의 최종 분류는 `scan/os/final-nine-role-{os-assessment,tools-rows,os-summary}.*`다. 조회 URL·UTC·HTTP 상태·SHA는 `scan/sources/*manifest.json`, `scan/os/{source-manifest,ubuntu-source-manifest}.json`에 보존했다.

| 산출물 | SHA256 |
| --- | --- |
| 최종 실효9역할 raw CSV | `8463349de1017e1762714009fb6adec7b53e71351078002c180f007c8a135a8c` |
| 모든10검사 이력 raw CSV | `9b6e1af75cd933b0c23034ee8c71234a2596d8804032c3c5e7ef6bf96bfa10d6` |
| 최종 실효9역할 OS 판정 CSV | `03e8fa2d4355c2d36b955cf3387f0060017015076bd6ef076e821ad19a3d78d7` |
| 배포 내용 대조 JSON | `0aa55826bb71696510aaba43ba52e2e53fee87029388eac742ab69f6d0139cba` |

독립 Java/PURL·npm audit·공식 공급 근거와 각 실행 exit/stdout/stderr도 같은 scan 폴더에 남겼다. 최초 요약 helper의 PURL locations=null 처리 실패는 `scan-recovery.json`에 보존했고, scanner 각 exit0 결과와 null-safe 요약·누락 supplemental 실행을 구분했다. 보안 분류 문서의 작성과 실제 검사 실행·부하 성적은 구분한다.



[OS 공식 분류 CSV](sheet-security-20261029-os.csv)는 OS가 검출된8개 역할의2,362행을 보존한다. Ryuk는 별도 Go 도구 분류에 포함된다. CSV의 모든 행은 image ID·source revision·공식 URL·조회시각·원문 SHA를 포함하고 실제 도달성 미검증을 명시한다. SHA256은 `9190107d1eec0d5bccb35c16fbac7541876f7c34fd0ace88ee079937940059b2`다. 원본 OS 판정 CSV와 이전8역할 초안·실패 trial 검사 증거는 별도로 보존했다.
