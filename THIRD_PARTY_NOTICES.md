# 제3자 소프트웨어 고지

이 문서는 저장소에 포함한 제3자 파일의 출처와 의존성 조사 범위를 기록한다. 프로젝트 자체의 배포 라이선스를 정하는 문서는 아니다.

## Maven Wrapper

- 버전: Apache Maven Wrapper 3.3.4, only-script 배포 방식.
- 대상: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.
- 출처: [공식 고정 태그](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4).
- 라이선스: Apache License 2.0. [라이선스 전문](LICENSES/Apache-2.0.txt)과 [원본 NOTICE](LICENSES/maven-wrapper-NOTICE.txt)를 함께 보존한다. NOTICE의 행 끝 공백만 정리했다.
- Wrapper가 내려받는 Maven은 3.9.16으로 고정하며 공식 배포 ZIP의 SHA256을 Wrapper 설정에서 검증한다.
- Docker의 build 단계에 이 고지와 `LICENSES`를 복사한다. JRE·완성 JAR만 사용하는 최종 앱·migrate 이미지는 Maven Wrapper를 포함하지 않는다.

## htmx

- 버전: 2.0.11.
- 파일: [htmx.min.js](src/main/resources/static/vendor/htmx.min.js), [LICENSE](src/main/resources/static/vendor/htmx.LICENSE.txt).
- 출처: [공식 고정 태그의 배포 파일](https://raw.githubusercontent.com/bigskysoftware/htmx/v2.0.11/dist/htmx.min.js), [라이선스](https://raw.githubusercontent.com/bigskysoftware/htmx/v2.0.11/LICENSE).
- 라이선스: BSD Zero Clause License (0BSD).
- 배포 파일 SHA256: `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`.
- 원본 자산을 수정하지 않았다. 2026-10-26 React 전환 이후 소스 대조 이력으로만 보존하며 신규 JAR에서는 실행 파일을 제외하고 실제 화면에서 로드하지 않는다.

## Pretendard 글꼴

- 버전: Pretendard 1.3.9 (npm `pretendard`, 정확한 버전 고정).
- 사용 파일: `dist/web/variable/pretendardvariable-dynamic-subset.css`와 같은 폴더의 `woff2-dynamic-subset/*.woff2` 92개. 빌드가 `/react/`에 같은 출처 파일로 내보낸다. CDN·`data:` URL을 쓰지 않는다.
- 라이선스: SIL Open Font License 1.1. Copyright (c) 2021 Kil Hyung-jin, with Reserved Font Name Pretendard. [전문](LICENSES/Pretendard-OFL-1.1.txt)을 보존하고 빌드 결과의 `/react/THIRD_PARTY_LICENSES.txt`에도 포함한다.
- 글꼴 파일을 수정하거나 단독 판매하지 않는다. 결정 근거는 [ADR-0006](docs/adr/0006-worklog-design-layer.md)을 따른다.

## React 프론트엔드

- 런타임: React19.3.1·ReactDOM19.3.1·Scheduler0.28.0, MIT. 세 패키지의 설치 LICENSE는 동일하며 [전문](LICENSES/react-MIT.txt)을 보존한다. 빌드 결과에도 `/react/THIRD_PARTY_LICENSES.txt`로 포함한다.
- 프론트 개발 도구의 고정 버전·공식 호환성·라이선스 검토는 [ADR-0003](docs/adr/0003-react-frontend.md)을 따른다. 잠금 파일의 전체 플랫폼별 의존성 metadata는 [프론트 CSV](docs/development/frontend-licenses.csv)에 기록했다. 설치하지 않은 선택적 플랫폼 패키지도 포함한 목록이며 모든 도구가 런타임에 배포되는 것은 아니다.
- Exec Maven Plugin3.6.4는 Apache-2.0이며 npm 설치/빌드를 실행하는 빌드 전용 도구다. Node builder와 JRE runtime을 분리한다. 전체 컨테이너 OS·배포물의 법률 감사를 대신하지 않는다.

## PostgreSQL 로컬 no-XML 변형

- PostgreSQL18.6·PGDG18.6-1.pgdg12+2의 공식 source/packaging을 사용해 XML/XSLT/LLVM 소비자를 제외한 프로젝트 빌드 `18.6-1.pgdg12+2+worklog1`을 만든다. 원본 PGDG 바이너리와 구분하며 서버 코드는 수정하지 않는다.
- [ADR-0004](docs/adr/0004-postgresql-without-xml.md)와 [Dockerfile](infra/postgres/Dockerfile)에 출처·hash·옵션·빌드 계약을 기록한다. 원본 source의 COPYRIGHT와 설치 DEB의 파일별 `/usr/share/doc/*/copyright`를 유지하며, runtime의 `/usr/share/doc/postgresql-18/worklog-no-xml-build.txt`에 변형 고지를 추가한다.
- PostgreSQL 자체는 [PostgreSQL License](https://www.postgresql.org/about/licence/)를 따르며 Debian 패키징·전이 라이브러리의 개별 고지를 대체하지 않는다. 빌드 전용 문서 도구의 XML 라이브러리가 runtime에 배포되는 것은 아니다.

## Maven 의존성 조사

2026-11-21 계정 메일에 Spring Boot 4.1.2의 mail starter를 적용하고 관련 라이브러리의 Maven 범위와 라이선스 메타데이터를 갱신했다. [메일 기술 결정](docs/adr/0007-account-mail-delivery.md) · [좌표별 라이선스](docs/development/dependency-licenses.csv)

2026-10-26 React 전환 뒤 현재 CSV를 실제 Maven 앱/테스트 의존성120개와 대조해 갱신했다. 아래126개는 이전 조사 이력이다. Thymeleaf 관련6개를 제외했고 남은 좌표의 기존 라이선스 출처를 보존했으며 미확인 metadata는0개다.

2026-10-24 실제 해석한 프로젝트 의존성 126개(테스트 포함)의 좌표·범위·라이선스 메타데이터를 [CSV](docs/development/dependency-licenses.csv)에 기록했다. 2026-10-25 보안 패치로 MyBatis3.6.0 및 라이선스를 선언한 parent POM 좌표를 갱신하고 전체126개 좌표·scope를 다시 대조했다. 각 아티팩트의 POM과 상속한 parent POM의 `licenses`를 사용했으며, 라이선스를 명시한 POM 좌표도 함께 남겼다. 조사 당시 라이선스 메타데이터가 없는 항목은 0개였다.

이는 프로젝트 의존성의 메타데이터 목록이다. 여러 라이선스 표기의 선택·적용 조건, 각 JAR의 LICENSE/NOTICE·포함 코드, 빌드 플러그인 의존성, JDK·컨테이너 OS 구성요소까지 검토한 법률 감사는 아니다. 실제 배포 전 해당 배포물의 고지와 적용 조건을 별도로 확인하고, 의존성을 변경하면 목록을 갱신한다. 테스트 전용 구성요소와 서비스 배포 구성요소는 구분한다.

JDK·PostgreSQL·주요 도구의 직접 라이선스와 고정 버전 근거는 [ADR-0001](docs/adr/0001-technology-baseline.md)을 따른다. 상용 기능이나 유료 라이선스가 필요한 제품을 자동 도입하지 않는다.
