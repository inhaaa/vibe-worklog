# 잔여 보안 수정판의 공식 공급 재확인

> 이후 [gosu 바이너리·배포 서명 분석](gosu-binary-analysis.md)에서 1.19/1.20 서명·제외 없는 binary 검사와 현재 시작 경로를 확인했다. 기존 원장의 Medium CVE-2026-61801은 1.20에서 symbol finding이 제거됐지만 현재 Compose는 유지하고 파생 이미지는 도입하지 않는 것으로 판단했다. 아래는 공급 재확인 당시 기록이다.

> OS native 입력과 Docker endpoint/프로토콜의 추가 증거는 [후속 경계 분석](native-input-docker-boundaries.md)을 따른다. 이 문서의 공급 상태와 이후 실제 입력·심볼·진단 결과를 구분한다.

## 기준과 분류

2026-10-25 사용자의 요청에 따라 [잔여 영향 보고서](residual-vulnerability-impact.md)의 공급 상태를 다시 확인했다. 공식 API·배포 목록의 수집 시각은 **2026-10-25 22:51~22:56 KST(13:51~13:56 UTC)**다. 설치 기준은 Git `2581c2c`와 이전 검증 기록이며, 이번에는 실행 중인 설치판을 새로 점검하거나 패치를 적용하지 않았다.

| 분류 | 의미 |
| --- | --- |
| 적용 가능 후보 | 공식 안정 배포물이 존재한다. 현재 프로젝트에 반영하기 전 서명·호환성·회귀 검증이 남는다. 적용 완료를 뜻하지 않는다. |
| 공급 대기 | 선택한 공급자·배포판·플랫폼 또는 내장 사본을 사용하는 도구 체인에 수정판이 공급되지 않았다. 다른 플랫폼의 배포물·상용 공급·upstream 수정과 구분한다. |
| 추가 분석 | 공급자 평가, 실제 호출 조건, 정적 바이너리 분석 또는 기반 전환 검증이 필요하다. 무영향·검사 제외 판정이 아니다. |

P1~P3는 대응 우선순위이고 위 분류는 다음 작업의 종류다. 공급을 기다리는 항목도 실제 입력 경로 분석이 남을 수 있다. 이미 반영한 수정은 별도로 보존한다.

## JDK: 현재 Windows/Noble 조합은 공급 대기

| 대상·현재 기준 | 공식 공급 재확인 | 분류·다음 조건 |
| --- | --- | --- |
| Windows 개발 Temurin 25.0.4.1+1 | Temurin 25.0.5+8의 release API 자산을 100+100+9개로 모두 조회했으며 Windows는 0개다. Windows latest API도 25.0.4.1+1이다. | **P1 공급 대기.** 공급 후 checksum·서명·내부 버전·CA·Windows 회귀 확인 |
| Noble builder JDK | `25.0.5_8-jdk-noble`은 Registry HTTP 404 `MANIFEST_UNKNOWN`이다. | **P1 공급 대기.** 공식 manifest·digest·CA·빌드 HTTPS·전체 회귀 확인 |
| Noble app/migrate JRE | `25.0.5_8-jre-noble`도 같은 404다. | **P1 공급 대기.** 공식 manifest·digest·HTTP 보안·최초/no-op 마이그레이션·SBOM 확인 |
| Linux standalone JDK/JRE | Temurin 25.0.5+8과 공식 checksum은 공개돼 있다. Windows 자산·Noble 이미지의 공급을 의미하지 않는다. | **추가 분석.** tar 직접 삽입이나 공급자 변경은 native 의존성·지원·신뢰 저장소·라이선스 검증을 포함하는 별도 전환 |

[Temurin 릴리스](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8), [전체 자산 API](https://api.github.com/repos/adoptium/temurin25-binaries/releases/tags/jdk-25.0.5%2B8), [Windows latest API](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows), [플랫폼별 진행표](https://github.com/adoptium/temurin/issues/317), [공식 이미지 목록](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin)을 대조했다. 웹 릴리스 화면의 자산 211개 표시는 API/expanded-assets의 209개와 달라 원본을 함께 보존했다. 플랫폼별 실제 자산을 판단 기준으로 삼았다.

기존 `25.0.4.1_1-jdk-noble`·`25.0.4.1_1-jre-noble`과 floating `25-jdk-noble`·`25-jre-noble`은 HTTP 200이며 Dockerfile의 고정 digest와 일치했다. JDK는 `ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e`, JRE는 `3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5`다. 태그만 바꾸어 패치가 적용됐다고 기록하지 않는다.

정규 CPU와 CA 대응은 별도다. [Oracle 25.0.5 릴리스](https://www.oracle.com/java/technologies/javase/25-0-5-relnotes.html)와 [CA 경고](https://www.oracle.com/security-alerts/alert-cve-2026-91403.html)를 대조했으며, Temurin의 [NSS 3.131.1 반영 PR](https://github.com/adoptium/temurin-build/pull/4587)은 merge됐지만 현재 미공급 플랫폼의 실제 cacerts까지 검증한 것은 아니다. 기존 프로젝트 전용 CA 2개 불신 조치를 유지하고 새 배포물의 인증서 집합·JSSE를 확인한다.

## PostgreSQL·OS: bookworm 수정판 대기와 gosu 신규 공급

| 대상 | 공식 상태·공급 실체 | 분류·다음 조건 |
| --- | --- | --- |
| libxml2 / CVE-2026-6653 | bookworm `2.9.14+dfsg-1.3~deb12u6`은 open/vulnerable·postponed다. trixie도 vulnerable이다. | **P1 공급 대기.** 같은 배포판 수정 공급 또는 별도 검증한 전환이 필요하다. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653) |
| glibc / CVE-2026-5450 | bookworm `2.36-9+deb12u14`은 open/no-dsa다. trixie 수정은 현재 배포판의 패치가 아니다. | **P2 공급 대기·추가 분석.** native 호출·전환 시 복원/콜레이션 검증. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-5450) |
| SQLite / CVE-2025-7458 | bookworm `3.40.1-2+deb12u2`는 open/no-dsa다. trixie에는 수정이 있다. | **P2 공급 대기·추가 분석.** GPG/SQLite 소비자를 확인한다. 패키지 제거·배포판 혼합으로 즉시 해결 처리하지 않는다. [Debian](https://security-tracker.debian.org/tracker/CVE-2025-7458) |
| PostgreSQL의 나머지 deb 원장 | deb 413행, 31개 source의 CVE×source 138쌍을 대조했으며 모두 bookworm open·fixed_version 없음이다. C/H 106행·34쌍도 같은 상태다. | **공급 대기.** no-dsa·postponed는 해결 완료가 아니다. 개별 native 도달성은 추가 분석이다. [전체 tracker](https://security-tracker.debian.org/tracker/data/json) |
| gosu 1.20 | **2026-10-25 18:45:58 KST 공개.** `moby/sys/user v0.4.1`로 사용자/그룹 파일 파싱 DoS인 GO-2026-6686/CVE-2026-61801을 수정한다. | **적용 가능 후보.** 서명·파생 이미지 시작/UID·초기화/no-op·회귀 검증 후 반영 판단. [릴리스](https://github.com/tianon/gosu/releases/tag/1.20), [공식 advisory](https://github.com/moby/sys/security/advisories/GHSA-mjcv-p78q-w5fw) |
| gosu Go stdlib 기존 경고 | 1.20도 Go 1.24.6·CGO0·linux/amd64다. 기존 stdlib C/H 35행의 수정판이 아니다. 공식 PostgreSQL bookworm/trixie Dockerfile은 여전히 gosu 1.19다. | **P2 추가 분석.** 공식 govulncheck binary 분석과 실제 호출 조건 확인. [PG 소스](https://github.com/docker-library/postgres/blob/master/18/bookworm/Dockerfile), [gosu 정책](https://github.com/tianon/gosu/blob/master/SECURITY.md) |

Debian [main](https://deb.debian.org/debian/dists/bookworm/main/binary-amd64/Packages.xz)·[security](https://security.debian.org/debian-security/dists/bookworm-security/main/binary-amd64/Packages.xz)·[updates](https://deb.debian.org/debian/dists/bookworm-updates/main/binary-amd64/Packages.xz)의 amd64 목록도 직접 수집했다. 원장의 71개 바이너리 패키지를 모두 매핑했으며 위 잔여 CVE를 해결하는 bookworm 수정판은 확인되지 않았다. tracker 응답의 Last-Modified는 2026-10-25 12:52:25 UTC다.

gosu 1.20 amd64 다운로드 SHA256은 공식 release API digest와 같은 `b1892636c281590f34bc63c1118f058cd9de66c4ea463772e3909672d8b302ac`이다. 바이너리를 실행하지 않고 buildinfo를 판독했으며 `.asc` 공급도 확인했다. **암호학적 서명 검증은 미실행**이다. 이 새 CVE·배포물의 확인은 기존 scanner 원장 1,294행을 다시 검사해 얻은 결과가 아니다.

## Ubuntu Noble: 공급 상태 전수 대조

기존 app/build/migrate 원장의 OS deb·binary **785행에 해당하는 180개 CVE JSON**을 공식 Ubuntu API에서 모두 HTTP 200으로 수집했다. 이 수치는 중복 포함 패키지 매치 행이며 악용 가능 취약점 수가 아니다. source qualifier의 `util-linux@버전`을 source 이름과 버전으로 분리해, 이전 보고서의 매핑 누락 4종도 이번에는 매핑했다. 원시 CSV는 수정하지 않는다.

| 대상 | released | not-affected | needs-triage | needed | deferred | ignored | 매핑 누락 | 합계 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| app | 53 | 1 | 46 | 15 | 14 | 11 | 0 | 140 |
| migrate | 53 | 1 | 46 | 15 | 14 | 11 | 0 | 140 |
| build의 OS | 88 | 9 | 278 | 79 | 24 | 27 | 0 | 505 |

build의 나머지 3행은 Java 테스트용 HTTP 사본이므로 위 표에 포함하지 않았다. `released`는 공식 수정판 존재를 나타내며 설치 완료 판정과 다르다. binary의 upstream 버전만으로 비교하지 않고 [이전 설치 revision 증거](residual-vulnerability-impact.md)를 함께 읽는다. 상태의 의미는 [Ubuntu 공식 설명](https://ubuntu.com/security/cves/about)을 따른다.

| 계열 | 이번 대조 결과·분류 |
| --- | --- |
| zlib | Noble 수정 기준은 계속 `1:1.3.dfsg-3.1ubuntu2.3`이다. 기존 `.3` 적용·검증 기록을 보존한다. 원장의 `.2`와 scanner 불일치를 새 미적용 패치로 세지 않는다. [공식 CVE](https://ubuntu.com/security/CVE-2026-85091) |
| OpenSSL·curl·gzip | 이번에 조회한 released 기준은 이전 검증에서 확인한 deb revision(OpenSSL `.16`, curl `10.16`, gzip `3.2`) 이하다. 새 미적용 수정판으로 분류하지 않는다. [OpenSSL 사례](https://ubuntu.com/security/CVE-2026-75806), [curl 사례](https://ubuntu.com/security/CVE-2025-10966), [gzip 사례](https://ubuntu.com/security/CVE-2026-41992) |
| util-linux | CVE-2026-76642·78408·78409·78410은 Noble needs-triage다. 이전 매핑 누락도 해결 완료로 바꾸지 않았다. **추가 분석**: 공급자 평가와 mount/nsenter·권한 조건. [76642](https://ubuntu.com/security/CVE-2026-76642), [78408](https://ubuntu.com/security/CVE-2026-78408), [78409](https://ubuntu.com/security/CVE-2026-78409), [78410](https://ubuntu.com/security/CVE-2026-78410) |
| glibc·GnuTLS·PCRE2·Expat | glibc는 needs-triage/needed/ignored, GnuTLS는 deferred, PCRE2는 needed, Expat는 needs-triage/deferred다. **공급 대기·추가 분석**: 수정 미공급과 DNS·native 소비자/입력 경로 확인을 구분한다. [glibc](https://ubuntu.com/security/CVE-2026-8674), [GnuTLS](https://ubuntu.com/security/CVE-2026-95184) |
| binutils·tar·PAM·Perl·dash·shadow·systemd/udev·기타 원장 항목 | needed/deferred/ignored는 **공급 대기**, needs-triage는 **추가 분석**으로 유지한다. builder의 파일/명령 입력과 런타임 입력 경로를 구분하며 미사용 추정으로 일괄 제외하지 않는다. [Ubuntu CVE 자료](https://ubuntu.com/security/cves) |

Debian zlib의 open 판정과 설치 소스의 도입 범위 불일치는 [기존 영향 보고서](residual-vulnerability-impact.md)의 **추가 분석**으로 유지한다. Ubuntu `.deb`를 Debian에 섞는 조치는 하지 않는다.

## 개발 도구: 독립 라이브러리와 내장 사본의 공급 구분

| 현재 도구 체인 | 공식 공개 공급·내장 사본 | 분류·다음 조건 |
| --- | --- | --- |
| DockerJava 3.7.1 / Testcontainers 2.0.5 | 새 다운로드 zerodep JAR에도 Core/Core-h2 5.3.6·Client 5.5.1이 들어 있다. 단독 안정 Core 5.4.4·Client 5.6.5의 공개는 이 사본을 교체하지 않는다. | Core CVE-2026-54399·Client CVE-2026-64607은 **P2 공급 대기**. Core-h2 CVE-2026-54428은 **P3 추가 분석**: transport/프로토콜 변경 전 재평가 |
| Maven Site 3.22.0 / Jetty 9.4.58 | 공개 Central 9.x 마지막은 9.4.58이다. 공지의 수정판 9.4.60/.61/.63은 현재 공개 호환 배포물로 확보되지 않았다. 9/10/11 후속판은 상용 지원 경로와 구분한다. | 기존 Jetty 4건은 **공개 호환판 공급 대기·P3 추가 분석**. Site:run·client/Digest·HTTP2/3·HttpURI 사용 전 재평가 |
| Site metadata의 4.0.0-M16 | latest/release 문자열은 M16이지만 milestone이다. POM도 Jetty 9.4.54를 선언한다. 안정 3.x는 3.22.0이다. | **추가 분석.** 이 변경이나 Jetty 12 강제 교체를 즉시 적용 가능한 호환 보안 패치로 보지 않는다. |

실제 [DockerJava metadata](https://repo.maven.apache.org/maven2/com/github/docker-java/docker-java-transport-zerodep/maven-metadata.xml)·[POM](https://repo.maven.apache.org/maven2/com/github/docker-java/docker-java-transport-zerodep/3.7.1/docker-java-transport-zerodep-3.7.1.pom), [Testcontainers metadata](https://repo.maven.apache.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml), [Site metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-site-plugin/maven-metadata.xml)·[POM](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-site-plugin/3.22.0/maven-site-plugin-3.22.0.pom), [Jetty metadata](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-server/maven-metadata.xml)와 [공식 공급 정책](https://webtide.com/end-of-life-changes-to-eclipse-jetty-and-cometd/)을 대조했다. 개별 CVE의 수정 기준·호출 조건과 공식 advisory 링크는 [기존 개발 도구 표](residual-vulnerability-impact.md#개발-도구의-잔여-cve-7개)를 유지한다. 일반 HTTP dependency override로 relocated 사본을 수정했다고 주장하지 않는다.

## 다음 실행 순서와 검증 범위

1. **즉시 가능한 분석:** gosu 1.19/1.20의 공식 govulncheck binary 분석, OS native 입력·Docker endpoint/프로토콜 확인. 필요한 분석 도구도 공식 출처·버전·라이선스를 확인한 뒤 프로젝트 전용 경로에서 사용한다.
2. **가용 후보 검증:** gosu 1.20의 서명과 PG 파생 이미지 필요성을 검토한다. 기존 비루트 시작의 효과, 실제 수정 CVE, 남은 Go stdlib 경고를 분리한 뒤 UID·최초/no-op·복원·회귀를 검증한다.
3. **공급 후 적용:** Windows/Noble JDK와 bookworm libxml2는 공식 수정판이 실제 공급되면 checksum/digest·설치판·CA·전체 회귀·HTTP·SBOM을 확인한다. 공급자/배포판 전환은 별도 호환성·복원·콜레이션 검증 단위다.
4. **P1 종료 판단:** 현재 JDK·libxml2는 미해결로 유지한다. 이번 공급 재확인을 배포·파일럿 보안 수용 완료로 확대하지 않는다.

이번 작업은 공식 자료 조회·다운로드·정적 판독과 문서 변경이다. 코드·설정·DB·컨테이너·설치판은 변경하지 않았으며 Java/Python 회귀·HTTP 점검·부하·새 SBOM/취약점 스캔은 실행하지 않았다. 이전 396개·HTTP 24개·CA 6개·33분 결과를 이번 실행 결과로 재사용하지 않는다.

원본 응답·URL·조회 UTC·HTTP 상태·해시와 대조 자료는 ignored `target/security-recheck-20261025/{jdk,postgres,devtools,ubuntu-dotnet}/`에 보존한다. 주요 요약은 각각 `summary.json`, `debian-source-cve-recheck.csv`/`debian-index-manifest.json`, `summary.json`, `manifest.json`/`comparison-normalized.json`이다. 초기 Python TLS 오류와 중단한 curl 수집 기록은 별도 `ubuntu/`에 남기고 성공 증거와 합산하지 않는다. 정상 인증서 검증을 유지한 Windows HTTP 클라이언트로 Ubuntu 180개를 다시 수집했다. TLS 검증 완화나 전역 신뢰 변경은 하지 않았다.
