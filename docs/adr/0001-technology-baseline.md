# ADR-0001: 기술 기준선과 검증 경계

## 상태·맥락

- 결정일·공식 자료 조회일: 2026-10-24, Asia/Seoul.
- 상태: Phase3 문서 설계 기준 채택 당시의 검토 이력을 보존한다. Phase4의 재검증과 실행 범위는 이 문서 마지막 절에 추가한다.
- 근거: [README](../../README.md)의 Java25·Spring Boot4.1.2·MyBatis4.1.0 후보, [kickoff](../kickoff.md)의 4.1.x·4.x 계획, [PRD](../prd.md)의 MVP와 1인 운영 제약.
- 원래 표기의 MyBatis4.1.0은 본체 버전으로 단정할 수 없어 아티팩트를 구분한다. JUnit5 후보도 Boot 관리 버전에 맞춰 재검토한다.

## 대안과 결정

| 조합 | 판단 |
| --- | --- |
| Java25 + Boot4.1.2 + MyBatis starter4.1.0 + 본체3.5.19 + Boot 관리 JUnit6 | 공식 릴리스·요구사항·동일 minor 연동 근거가 있고 후보 변경 최소. 채택 |
| Java21/25 + Boot3.5 계열 + MyBatis starter3.0 계열 + JUnit5 | 공식 starter 지원표의 대안. 현 기준선의 실제 통합 문제 발생 시 별도 ADR로 판단 |
| Boot4.1.2에 MyBatis본체4.1.0 또는 JUnit5를 임의 고정 | 본체 아티팩트와 연동 모듈 혼동, BOM 이탈. 채택하지 않음 |

채택은 공식 문서상 실행 가능한 조합을 정한 설계 결정이다. 본 프로젝트에서 실행해 호환성을 증명했다는 뜻은 아니다. 존재가 확인되지 않은 버전을 미출시라고 단정하지 않으며, 아래 확인 범위를 넘어서는 최신성·장기 지원을 주장하지 않는다.

## 공식 확인 사실과 남은 실행 검증

| 기술 | 2026-10-24 공식 확인 | 채택·남은 검증 |
| --- | --- | --- |
| Java25 | Oracle 지원 로드맵은25를 LTS로 명시. LTS 정책은 배포판 공급자별 조건 | Java25 채택. 실제 JDK 공급자·패치·배포 라이선스는 실행 환경 결정 시 확인 |
| Spring Boot4.1.2 | 공식 문서 stable 목록에4.1.2, Java17~27, Maven3.6.3 이상 |4.1.2 채택. JDK25 빌드·패키징·테스트·보안 구동 검증 필요 |
| MyBatis starter4.1.0 | 공식 릴리스와 태그 POM: Boot4.1.0, MyBatis-Spring4.1.0, MyBatis본체3.5.19 | starter4.1.0·MyBatis-Spring4.1.0·본체3.5.19를 구분 채택. Boot4.1.2와 실제 통합 테스트 필요 |
| MyBatis 본체 | 공식 본체 좌표 `org.mybatis:mybatis:3.5.19` 확인 | README의4.1.0을 본체 버전으로 사용하지 않음.4.x 본체 출시·호환 사실은 이번 확인 범위에서 입증하지 못함 |
| JUnit | Boot4.1.2 관리 좌표의 Jupiter/Platform은6.0.3. 현재 JUnit 가이드는6계열 | JUnit5 후보에서 Boot 관리 JUnit6.0.3으로 변경. 독립 상향·Vintage 불필요 |
| PostgreSQL18 | 공식18 문서·지원 버전·RLS 기능 확인 |18 계열 채택. 실제 패치·이미지 digest·JDBC/RLS·복구 검증 필요 |
| Flyway | Boot BOM의 core·PostgreSQL 모듈12.4.0, Flyway 공식 DB 문서의18 검증 및 별도 모듈 확인 | OSS core+PostgreSQL 모듈 같은 버전. migration 역할로만 실행, 실제 마이그레이션 미수행 |
| JDBC·Testcontainers | Boot BOM의 PostgreSQL JDBC42.7.14, Testcontainers2.0.5 확인 | BOM 관리 채택. 실제 드라이버·이미지·컨테이너 실행 환경 검증 필요 |
| Spring Security·Actuator | Boot 계열 연동·보안 기능 제공 | Boot starter·BOM 사용. 별도 버전 강제 없음, 세션/CSRF·노출 범위 검증 |
| Thymeleaf | 공식 Spring MVC 폼·fragment 통합 문서 확인. 열람한3.1 문서는 Spring5/6 모듈 설명 | SSR 선택 유지. Boot4.1.2 starter가 해석하는 실제 엔진·Spring 어댑터 조합은 착수 조건으로 검증 |
| htmx | 공식 문서에서2.x·HTML 부분 응답·별도 빌드 없이 사용 가능 확인 |2.x 방향, 실제 patch·고정 자산·CSRF·오류 처리·history 캐시 검증 후 확정 |
| Apache POI | XLSX 검토 출력에 필요한 Java 라이브러리 후보 | 도입 채택, 정확한 버전·Java25·메모리·수식 문자열 안전성·전이 의존성은 착수 시 검증 |
| Maven Wrapper·Docker·Caddy·Linux | 계획 유지, 이번 작업에서 설치·실행하지 않음 | 실제 버전·배포판·포트·이미지·백업·지원 정책은 별도 환경 단계에서 검증 |

Boot 관리 버전은 [공식 의존성 좌표](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)에서 확인했다. 이 표는 여러 항목을 같은 BOM으로 묶는 결정이며 전체 의존성 트리를 실제 해석한 결과는 아니다. 특히 Thymeleaf 어댑터와 POI의 정확한 버전은 현재 입증한 호환성 범위 밖이다.

## 확인에 사용한 공식 자료

| 근거 | 연결 |
| --- | --- |
| JDK LTS·공급자 정책 | [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) |
| Boot stable·Java·빌드 요구사항 | [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) |
| starter 지원 계열 표 | [MyBatis Spring Boot Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/) |
| starter4.1.0 공식 릴리스 | [MyBatis starter release](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.1.0) |
| starter가 사용하는 본체·Spring·Boot | [릴리스 태그 POM](https://raw.githubusercontent.com/mybatis/spring-boot-starter/mybatis-spring-boot-4.1.0/pom.xml) |
| 본체 좌표 | [MyBatis Maven Coordinates](https://mybatis.org/mybatis-3/dependency-info.html) |
| JUnit 기본 연동 | [Boot Testing](https://docs.spring.io/spring-boot/reference/testing/index.html), [JUnit6 User Guide](https://docs.junit.org/6.1.3/overview.html) |
| PostgreSQL18 RLS | [PostgreSQL18 Row Security](https://www.postgresql.org/docs/18/ddl-rowsecurity.html) |
| Flyway18·별도 PostgreSQL 모듈 | [Flyway PostgreSQL Database](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database) |
| SSR·부분 응답 | [Thymeleaf Spring tutorial](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html), [htmx docs](https://htmx.org/docs/) |

참조한 current 문서·지원 표는 변경될 수 있으므로 Phase4 착수 직전에 같은 고정 릴리스 태그·좌표와 함께 다시 확인한다. 표의 starter 일반 안내가4.0 예시를 보이더라도4.1.0 존재는 공식 릴리스·고정 태그 POM으로 확인한 사실이다.

## 라이선스 확인 범위

| 대상 | 직접 확인한 범위 | 남은 확인 |
| --- | --- | --- |
| Spring Boot4.1.2 | [고정 태그 LICENSE](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/LICENSE.txt): Apache2.0 | starter와 전체 전이 의존성·배포 고지 |
| MyBatis본체3.5.19·starter4.1.0 | [본체 LICENSE](https://raw.githubusercontent.com/mybatis/mybatis-3/mybatis-3.5.19/LICENSE), [starter 태그 POM](https://raw.githubusercontent.com/mybatis/spring-boot-starter/mybatis-spring-boot-4.1.0/pom.xml): Apache2.0 | MyBatis-Spring 포함 해석된 전체 트리·NOTICE |
| JUnit6.0.3 | [고정 태그 LICENSE](https://raw.githubusercontent.com/junit-team/junit-framework/r6.0.3/LICENSE.md): EPL2.0 | 테스트 도구의 실제 재배포 범위·전이 의존성 |
| PostgreSQL | [공식 라이선스](https://www.postgresql.org/about/licence/): PostgreSQL License | 실제 컨테이너·OS·JDBC 배포물의 별도 조건 |
| Flyway12.4.0 OSS | [고정 태그 LICENSE](https://raw.githubusercontent.com/flyway/flyway/flyway-12.4.0/LICENSE.txt): Apache2.0 | OSS/유료 기능 구분·모듈별 실제 배포 조건 |
| Java·나머지 주변 도구 | 공급자·정확 버전 또는 전체 라이선스를 이번에 고정하지 않음 | JDK·Thymeleaf·htmx·POI·Testcontainers·Docker 배포물·Caddy의 고정 버전별 LICENSE/NOTICE 검증 |

이 확인은 법률 의견이나 전체 공급망·라이선스 감사가 아니다. 상용 기능·글로벌 설치·유료 외부 서비스는 선택하지 않았다. 운영할 배포물의 라이선스·고지 목록은 의존성 해석 후 확정한다.

## 결과와 Phase4 착수 조건

선택한 모듈형 모놀리스·Java 서비스 계산·MyBatis·RLS·Thymeleaf/htmx 구조는 유지한다. ORM 추가, 별도 SPA, 메시지 브로커·MSA·Kubernetes는 도입하지 않는다. 검토용 XLSX만 POI 도입 범위이며 업로드·급여 전용 매핑은 후속이다.

Phase4는 사용자의 별도 요청 후에만 시작한다. 착수 전에 다음을 검증하고 결과를 남긴다.

1. JDK 공급자·정확 패치·Maven Wrapper 버전과 공식 지원·라이선스를 확정한다.
2. Boot4.1.2 의존성 트리를 해석해 starter4.1.0·MyBatis본체3.5.19·JUnit6·Thymeleaf 어댑터의 실제 조합과 충돌을 확인한다.
3. 기본 MVC/폼·htmx 오류/CSRF·세션을 테스트 우선으로 검증하고 POI 버전·텍스트 셀·메모리 한계를 확인한다.
4. 별도 승인을 받은 DB·Docker 작업에서 PostgreSQL18·Flyway·일반 역할 RLS·커넥션 풀 실패 경로·복합 FK·동시 승인 테스트를 실행한다.
5. 실제 패치·이미지 digest·전이 라이선스·보안 공지·백업 복원·초대 전달 수단·성능 수용 기준을 확인한다.

위 내용은 Phase3 완료 시점의 착수 조건이다. 당시에는 이를 실제 수행하지 않았으며 문서 작성 완료를 구현·실행 승인으로 해석하지 않았다.

## Phase4 공식 재검증과 로컬 환경 결정

- 확인일: 2026-10-24, Asia/Seoul.
- 승인 범위: 프로젝트·설정 파일 생성, 로컬 DB·Docker 작업, 작업 단위별 검증·커밋·일반 Push. 외부 서버 접속·배포는 제외한다.
- 아래 표는 공식 릴리스·라이선스와 채택 좌표를 확인한 결과다. 전체 조합의 실행 성공이나 전이 의존성 전체 감사를 뜻하지 않는다.
- Phase4는 빌드·DB 연결·인증·기본 화면의 기반을 검증한다. 근태 계산·신청·승인·휴가·Excel 업무 기능은 Phase5에 구현한다.

### 고정 기준과 공식 근거

| 대상 | Phase4 기준 | 확인한 근거·제약 |
| --- | --- | --- |
| JDK 공급자·패치 | Eclipse Temurin 25.0.4.1+1, Windows x64 ZIP | [공식 릴리스](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1)의 실제 Windows 자산을 채택. 25.0.5+8 공개와 OS별 배포 완료는 구분 |
| Maven·Wrapper | Maven 3.9.16, Maven Wrapper 3.3.4 | [지원 이력](https://maven.apache.org/docs/history.html)은 3.10.0·3.9.16을 지원 계열로 표시. 기존 안정 계열의 3.9.16을 고정하고 [Wrapper 3.3.4](https://maven.apache.org/tools/wrapper/download.cgi)를 사용. 전역 설치 불필요 |
| Spring Boot | 4.1.2 | [요구사항](https://docs.spring.io/spring-boot/system-requirements.html): Java17~27·Maven3.6.3 이상. 지원 범위에 Java25 포함 |
| MyBatis | starter4.1.0, MyBatis-Spring4.1.0, 본체3.5.19 | [고정 태그 POM](https://raw.githubusercontent.com/mybatis/spring-boot-starter/mybatis-spring-boot-4.1.0/pom.xml)은 Boot4.1.0 기준. Boot4.1.2와의 실제 연동은 프로젝트 테스트로 확인 |
| Thymeleaf | 엔진·`thymeleaf-spring6` 3.1.5.RELEASE | [Boot BOM](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)과 [Boot4.1.2 모듈](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-thymeleaf/build.gradle)이 `thymeleaf-spring6`을 사용. 이름을 근거로 미확인 `spring7` 어댑터로 대체하지 않음 |
| JUnit·Testcontainers | Boot BOM 관리: JUnit6.0.3, Testcontainers2.0.5 | 테스트 범위만 도입. TC2 모듈 좌표는 `testcontainers-junit-jupiter`·`testcontainers-postgresql`. 컨테이너 실행 여부는 별도 기록 |
| Flyway·PostgreSQL JDBC | Boot BOM 관리: Flyway12.4.0, JDBC42.7.14 | OSS `org.flywaydb:flyway-core`와 `flyway-database-postgresql`을 같은 버전으로 사용. [공식 DB 문서](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)는 PostgreSQL18과 별도 모듈을 명시 |
| PostgreSQL | 18.6 | [지원 버전](https://www.postgresql.org/support/versioning/)에서 18계열 현재 minor 확인. Windows는 [PostgreSQL 공식 안내](https://www.postgresql.org/download/windows/)가 연결한 [EDB ZIP](https://www.enterprisedb.com/download-postgresql-binaries)의 18.6-5 배포물로 로컬 검증 |
| Spring Security·Actuator | Boot4.1.2 starter·BOM | 별도 버전 강제 없음. 로그인·세션·CSRF·헬스 노출 범위를 실행 테스트 대상으로 유지 |
| htmx | 2.0.11 | [공식 설치 문서](https://htmx.org/docs/)의 버전 확인. 고정 자산을 애플리케이션에서 제공하고 런타임 CDN 의존을 추가하지 않음 |
| Apache POI | 공식 릴리스6.0.0 확인, Phase4 의존성 추가 보류 | [공식 다운로드](https://poi.apache.org/download.html): 2026-10-22 릴리스. 이번에 Excel 기능을 구현하지 않으므로 라이브러리를 미리 추가하지 않음. 도입 시 Java25 실행·전이 의존성·문자열 셀·메모리 한계를 다시 검증 |

Spring 계열·JUnit·Flyway·JDBC·Testcontainers·Thymeleaf는 Boot BOM을 우선한다. 관리되는 버전을 각각 임의 상향하거나 JUnit5를 함께 강제하지 않는다. 실제 해석된 트리가 위 공식 좌표와 다르면 원인을 확인하고 실행 근거를 남긴다.

### JDK 패치와 다운로드 무결성

25.0.5+8은 [공식 릴리스 목록](https://github.com/adoptium/temurin25-binaries/releases)에 공개됐지만, 조회 당시 해당 릴리스의 공식 API 자산에는 Windows 패키지가 없었다. [공식 Docker 이미지 목록](https://raw.githubusercontent.com/docker-library/official-images/master/library/eclipse-temurin)도 아직 25.0.4.1_1 태그를 제공했다. 로컬 Windows와 컨테이너 기준을 실제 공급되는 동일 패치로 맞추기 위해 25.0.4.1+1을 선택한다. 이를 Java25의 최신 보안 패치라고 표현하지 않는다.

- 다운로드: [OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip).
- 공식 체크섬: [ZIP SHA256](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip.sha256.txt).
- 공식 SHA256 값: `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`.
- portable 도구를 사용하고 시스템 전역 설치·영구 환경변수 변경을 전제로 하지 않는다. JDK 배포물의 `legal` 고지는 보존한다.
- 25.0.5 이상 Windows·컨테이너 공급이 완료되면 패치·체크섬·이미지 digest를 함께 갱신하고 빌드·통합 테스트를 다시 실행한다. 외부 공개·배포 전에 보안 공지와 패치 적정성을 재검토한다.

### 직접 라이선스 재확인

| 대상 | 직접 확인한 라이선스·공식 근거 | 남은 경계 |
| --- | --- | --- |
| Eclipse Temurin | GPLv2 + Classpath Exception: [Adoptium FAQ](https://adoptium.net/docs/faq/) | 실제 JDK 배포물의 추가 고지·제3자 구성요소 보존 |
| Maven·Wrapper | Apache2.0: [Maven 라이선스](https://www.apache.org/licenses/), [Wrapper 고정 태그](https://raw.githubusercontent.com/apache/maven-wrapper/maven-wrapper-3.3.4/LICENSE) | 배포되는 스크립트·NOTICE 고지 보존 |
| Spring Boot·MyBatis | Apache2.0: 기존 고정 태그 근거와 [MyBatis-Spring4.1.0 LICENSE](https://raw.githubusercontent.com/mybatis/spring/mybatis-spring-4.1.0/LICENSE) | 해석된 전체 starter·전이 트리의 개별 라이선스 |
| Thymeleaf3.1.5.RELEASE | Apache2.0: [고정 태그 LICENSE](https://raw.githubusercontent.com/thymeleaf/thymeleaf/thymeleaf-3.1.5.RELEASE/LICENSE.txt) | 어댑터 포함 실제 JAR 고지와 전이 라이브러리 |
| JUnit6.0.3·Testcontainers2.0.5 | EPL2.0·MIT: [JUnit 고정 태그](https://raw.githubusercontent.com/junit-team/junit-framework/r6.0.3/LICENSE.md), [Testcontainers 고정 태그](https://raw.githubusercontent.com/testcontainers/testcontainers-java/2.0.5/LICENSE) | 테스트 의존성과 런타임 배포물을 분리 |
| Flyway12.4.0 OSS | Apache2.0: [고정 태그 LICENSE](https://raw.githubusercontent.com/flyway/flyway/flyway-12.4.0/LICENSE.txt) | OSS 좌표만 사용. 상용 기능·별도 Redgate 배포물 조건을 일반화하지 않음 |
| PostgreSQL·JDBC42.7.14 | PostgreSQL License·BSD2: [서버 라이선스](https://www.postgresql.org/about/licence/), [JDBC 고정 태그 LICENSE](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.14/LICENSE) | EDB ZIP·컨테이너 OS의 포함 구성요소는 별도 고지 |
| htmx2.0.11 | 0BSD: [고정 태그 LICENSE](https://github.com/bigskysoftware/htmx/blob/v2.0.11/LICENSE) | 자산 버전과 출처를 유지 |
| Apache POI6.0.0 | Apache2.0: [고정 태그 LICENSE](https://raw.githubusercontent.com/apache/poi/REL_6_0_0/legal/LICENSE) | 미도입. 향후 OOXML·전이 의존성과 NOTICE까지 재검토 |
| 검증 도구 Docker Compose5.6.1 | Apache2.0: [고정 태그 LICENSE](https://raw.githubusercontent.com/docker/compose/v5.6.1/LICENSE) | portable CLI로 구성만 해석. Docker Desktop 라이선스·daemon 설치와 별개 |

직접 구성요소의 라이선스를 확인한 것이 전체 의존성·컨테이너 OS의 라이선스 감사 완료를 뜻하지 않는다. Docker Desktop은 별도 사용 조건이 있는 제품이므로 PostgreSQL·Testcontainers·Docker Engine의 라이선스와 혼동하지 않는다. 최초 기반 구축에서는 Docker Desktop·WSL을 설치하지 않았다. 후속 설치·검증은 문서 마지막 절을 따른다.

### 최초 로컬 실행 검증 범위: Docker 설치 전

Temurin25.0.4.1+1·Maven Wrapper3.3.4/Maven3.9.16·portable PostgreSQL18.6에서 다음을 실행했다. 공식 문서의 지원 범위와 실제 프로젝트 실행 결과를 구분한다.

| 검증 | 2026-10-24 실행 결과 |
| --- | --- |
| 의존성 트리 해석 | Boot4.1.2, MyBatis starter4.1.0/Spring4.1.0/본체3.5.19, JUnit6.0.3, Thymeleaf 엔진·spring6 어댑터3.1.5.RELEASE, Flyway12.4.0, JDBC42.7.14, Testcontainers2.0.5가 기준선과 일치 |
| Wrapper 빌드·패키징 | Java25에서 `verify` 성공, 실행 가능한 Boot JAR 생성 |
| 단위·실제 DB 테스트 | 암호·요청 제한3개, DB/RLS11개, 인증·웹11개: 총25개 통과, 실패·오류·건너뜀0 |
| Flyway 독립 Maven goal | migration 역할의 `flyway:migrate`로 V1 checksum·적용 이력 검증 및 최신 상태 확인 성공 |
| 패키지 앱 HTTP | 루프백18080에서 health·로그인·CSRF·본인 조회·htmx·테넌트 변조 차단·운영자/민감 Actuator 차단·로그아웃 등10개 확인 통과 |
| 전이 라이선스 메타데이터 | 실제 프로젝트 의존성126개 POM·상속 POM의 좌표/범위/라이선스 출처 기록, 메타데이터 누락0. JAR 고지·빌드 플러그인·컨테이너 OS 전체 감사는 아님 |
| Compose 설정·초기화 스크립트 | 공식 standalone Compose5.6.1의 `config` 해석, loopback 게시·비밀값 분리·앱 읽기 전용 설정 확인, Bash 문법 검증 통과 |
| Docker 이미지·컨테이너·Testcontainers | 미실행: 호스트에 Docker daemon 없음. native DB 테스트 성공으로 대체하지 않음 |

실행 명령과 경계는 [로컬 개발 안내](../development/local.md), 프로젝트 의존성 메타데이터는 [CSV](../development/dependency-licenses.csv), 포함 파일 고지는 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md)에 남긴다. 조회된 라이선스명만으로 배포물의 법률 검토를 완료했다고 판단하지 않는다.

DB 명세의 `left_on`은 마지막 재직일을 포함한다. 인증의 재직 경계도 같은 의미로 적용하고 당일 허용·다음 날 거부를 실제 DB 테스트로 확인했다. 회사 코드의80자 계약, 잘못된 로그인1회당 요청 제한1회 반영, 세션 교체·회수도 검증했다. 이 결정은 근태 계산이나 휴가 정책 변경이 아니다.

Maven Central의 대용량 다운로드 지연은 커밋하지 않은 작업용 Maven mirror 설정으로 우회했다. 저장소 POM·전역 Maven 설정·공식 Wrapper 배포 URL을 변경하지 않았다. 재실행 시 기본 Central 또는 조직에서 승인한 mirror를 사용한다.

- 최초 검증 당시 호스트에는 사용 가능한 Docker 실행 환경이 없고 WSL 런타임도 없었다. 당시에는 Docker 이미지 빌드·Compose 기동·Testcontainers 실행을 통과했다고 기록하지 않았다.
- 로컬 DB 검증은 루프백에 바인딩한 별도 portable PostgreSQL18.6 인스턴스와 프로젝트 전용 데이터·계정으로 수행한다. 기존 서비스·외부 서버·운영 DB에는 접근하지 않는다.
- native DB 테스트와 Testcontainers 테스트는 같은 Flyway·인증·테넌트 경계를 검증하도록 구성하되, native 실행 성공이 컨테이너 실행 성공을 대체하지 않는다.
- DB 연결·Flyway 이력·일반 역할의 RLS·SQL 테넌트 조건·컨텍스트 실패/재사용·기본 로그인/CSRF 검증을 구분해 결과를 남긴다. Phase5의 실제 승인 동시성·근태 계산 테스트까지 완료한 것으로 확대하지 않는다.
- 공식 이미지 태그와 registry manifest digest를 조회해 [Dockerfile](../../Dockerfile)·[Compose](../../compose.yaml)에 고정했다. 태그는 `eclipse-temurin:25.0.4.1_1-jdk-noble`, `eclipse-temurin:25.0.4.1_1-jre-noble`, [공식 PostgreSQL 목록](https://raw.githubusercontent.com/docker-library/official-images/master/library/postgres)의 `postgres:18.6-bookworm`이다. digest 조회는 이미지 빌드·실행·OS 패키지 검증과 구분한다.
- Caddy·HTTPS·운영 백업/복원·부하·외부 배포는 이번 로컬 환경 검증 완료와 별개다. 실제 배포는 수행하지 않는다.

## Docker 후속 설치·실행 검증

2026-10-24 사용자가 남은 Docker 검증에 필요한 Docker·WSL 설치와 환경 설정을 추가 승인했다. 기존 실행 이력은 위에 보존하고 이후 결과를 구분한다. 외부 서버 접속·배포·Phase 5 기능 개발은 제외한다.

### 호스트 도구와 설치 근거

- 호스트: Windows 11 Enterprise 23H2, 빌드22631, x64. VirtualMachinePlatform과 Windows hypervisor는 이미 활성화되어 있었다. 기존 WSL 배포판·Docker Desktop은 없었다.
- WSL3.0.3: [Microsoft 고정 릴리스](https://github.com/microsoft/WSL/releases/tag/3.0.3)의 x64 MSI를 사용했다. [공식 자산 SHA256](https://github.com/microsoft/WSL/releases/expanded_assets/3.0.3)은 `ae977286974255bd26bf925f00f40f5692c6b2084155cbc0563c09aea091f211`이며 다운로드 파일과 일치했다. Microsoft Corporation Authenticode 서명은 `Valid`였다.
- [Microsoft 오프라인 설치](https://learn.microsoft.com/en-us/windows/wsl/install#offline-install)에 따라 관리자 MSI 설치에 `/qn /norestart REBOOT=ReallySuppress`를 사용했다. 종료코드0, WSL3.0.3.0·kernel6.18.54.1-1·기본WSL2를 확인했다. 일반 Ubuntu·WSL1 기능을 추가하지 않았다.
- Docker Desktop4.96.3: [공식 Windows 설치 절차](https://docs.docker.com/desktop/setup/install/windows-install/)의 사용자별 `--user --quiet --accept-license --backend=wsl-2`로 설치했다. 종료코드0, Docker CLI29.9.2와 Compose5.6.1을 확인했다. 관리자 상주 서비스·Windows 컨테이너를 위한 그룹 권한 추가는 하지 않았다.
- [Docker 공식 체크섬](https://desktop.docker.com/win/main/amd64/244472/checksums.txt)의 x64 설치 파일 SHA256 `3d8adad4f6c86583e0b00ea19a76197c51ccf5c83437c9ed775b739756452507`과 일치했고 Docker Inc Authenticode 서명은 `Valid`였다.
- Docker Desktop은 [개인 사용의 무료 조건](https://docs.docker.com/subscription-billing/desktop-license/)을 확인했다. 사용 주체·용도가 바뀌면 약관을 다시 검토한다. WSL·Linux kernel·Docker Desktop 포함물의 고지는 설치 배포물에 보존하며, 이 기록이 전체 라이선스 감사는 아니다.

설치 후 `desktop-linux`의 로컬 named pipe를 통해 Engine29.9.2(API1.56, Linux amd64) 응답을 확인했다. 기존 컨테이너·이미지는0개였다. WSL·Docker 엔진 기동에 재부팅은 필요하지 않았으며 자동 재부팅도 실행하지 않았다.

### Docker 빌드에서 확인한 문제와 조정

- 최초 빌드는 컨테이너 curl의 인증서 체인 검증 오류60으로 실패했다. Windows 기본 TLS 검증과 기존 Root 저장소를 확인한 공개 CA만 ignored 파일로 내보내 optional `local_build_ca` BuildKit secret으로 전달했다. TLS 검증을 해제하지 않았다. [Docker CA 안내](https://docs.docker.com/engine/network/ca-certs/)를 따른다.
- Dockerfile에 `unzip`을 추가했다. 없으면 공식 Wrapper가 ZIP URL을 tar.gz로 바꾸므로, ZIP으로 고정한 SHA256과 다운로드 형식을 일치시켰다. Wrapper 원본 스크립트나 체크섬 검증은 변경하지 않았다.
- Maven Central의 큰 JAR 전송이84초간 정체되어 해당 빌드만 중지하고, 앞서 사용한 로컬 미러 설정을 optional `maven_settings` BuildKit secret으로 주입했다. Maven 설정 내용은 이미지에 복사하지 않으며 migrate 실행에도 별도 read-only secret으로 전달했다. 저장소 기본 POM·Wrapper URL·전역 Maven 설정은 유지했다.
- CA는 builder의 OS·Java 저장소와 이를 상속한 migrate 이미지에 남는다. 앱 runtime은 독립 JRE에서 JAR만 복사하므로 CA·Maven 설정을 포함하지 않는다. CA·Maven 설정·호스트별 override·비밀번호는 Git에 포함하지 않는다. [BuildKit secret](https://docs.docker.com/build/building/secrets/)의 내용 변경은 캐시를 자동 무효화하지 않으므로 변경 시 재빌드한다.

### 후속 실행 결과

| 검증 | 결과 |
| --- | --- |
| 앱·migrate 이미지 | 고정 Temurin digest로 실제 빌드 성공, 위 로컬 네트워크용 secret 사용 |
| Compose 기동 | 전용 프로젝트 `vibe-attendance-local`, PostgreSQL18.6 healthy·Flyway V1 적용·migrate 종료0·앱 기동 성공 |
| Compose HTTP | health UP·UTF-8/CSRF/CSP·미인증 전체/htmx 차단·존재하지 않는 합성 계정의 일반화된 실패·고정 htmx·로그아웃 CSRF 등10개 통과 |
| DB 초기화·권한 | 최소3역할, 우회·상속·관리 권한 없음,4개 FORCE RLS, 일반 앱의 자격 테이블 접근·쓰기 차단, migration 이력1개 확인 |
| 운영 자료·실행 경계 | 고객사/계정0, 게시 포트 loopback만, 앱 uid10001·read-only·cap_drop ALL, 관리자/migration 자격·빌드 secret 앱 미전달 확인 |
| 이미지 분리 | 앱 runtime의 추가 CA 부재, migrate 이미지의 Maven 설정 내용 부재 확인 |
| Testcontainers2.0.5 | native JDBC override 없이 실제 PostgreSQL18.6 컨테이너 생성·실행. 단위3·DB11·웹11, 총25개 통과·실패/오류/건너뜀0 |

Compose에는 기본 사용자·데모 고객을 넣지 않았다. 성공 로그인과 고객 간 격리는 독립 Testcontainers 합성 자료로 검증했다. 검증 컨테이너는 자기 프로젝트만 종료하고 DB 볼륨과 ignored 로컬 실행 설정은 유지한다. 초기 보안·성능·백업 복원·운영 배포·법률 예외·Phase5 업무 규칙 검증이 모두 완료됐다는 뜻은 아니다. 외부 서버 접속·배포는 실행하지 않았다.
