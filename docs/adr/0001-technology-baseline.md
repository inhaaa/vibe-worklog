# ADR-0001: 기술 기준선과 검증 경계

## 상태·맥락

- 결정일·공식 자료 조회일: 2026-10-24, Asia/Seoul.
- 상태: 문서 설계 기준 채택. 프로젝트 생성·의존성 해석·통합 실행·배포 검증은 미수행.
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

이번 단계에서는 위 조건을 실제 수행하지 않는다. 문서 작성 완료가 프로젝트 생성·환경 설정·DB 작업·배포·외부 API 사용 승인을 뜻하지 않는다.
