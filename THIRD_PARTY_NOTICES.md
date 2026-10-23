# 제3자 소프트웨어 고지

이 문서는 저장소에 포함한 제3자 파일의 출처와 의존성 조사 범위를 기록한다. 프로젝트 자체의 배포 라이선스를 정하는 문서는 아니다.

## Maven Wrapper

- 버전: Apache Maven Wrapper 3.3.4, only-script 배포 방식.
- 대상: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.
- 출처: [공식 고정 태그](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4).
- 라이선스: Apache License 2.0. [라이선스 전문](LICENSES/Apache-2.0.txt)과 [원본 NOTICE](LICENSES/maven-wrapper-NOTICE.txt)를 함께 보존한다. NOTICE의 행 끝 공백만 정리했다.
- Wrapper가 내려받는 Maven은 3.9.16으로 고정하며 공식 배포 ZIP의 SHA256을 Wrapper 설정에서 검증한다.
- Docker의 build·migrate 단계에도 이 고지와 `LICENSES`를 복사한다. 최종 앱 이미지는 Maven Wrapper를 포함하지 않는다.

## htmx

- 버전: 2.0.11.
- 파일: [htmx.min.js](src/main/resources/static/vendor/htmx.min.js), [LICENSE](src/main/resources/static/vendor/htmx.LICENSE.txt).
- 출처: [공식 고정 태그의 배포 파일](https://raw.githubusercontent.com/bigskysoftware/htmx/v2.0.11/dist/htmx.min.js), [라이선스](https://raw.githubusercontent.com/bigskysoftware/htmx/v2.0.11/LICENSE).
- 라이선스: BSD Zero Clause License (0BSD).
- 배포 파일 SHA256: `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`.
- 원본 자산을 수정하지 않았으며 애플리케이션에서 직접 제공한다.

## Maven 의존성 조사

2026-10-24 실제 해석한 프로젝트 의존성 126개(테스트 포함)의 좌표·범위·라이선스 메타데이터를 [CSV](docs/development/dependency-licenses.csv)에 기록했다. 각 아티팩트의 POM과 상속한 parent POM의 `licenses`를 사용했으며, 라이선스를 명시한 POM 좌표도 함께 남겼다. 조사 당시 라이선스 메타데이터가 없는 항목은 0개였다.

이는 프로젝트 의존성의 메타데이터 목록이다. 여러 라이선스 표기의 선택·적용 조건, 각 JAR의 LICENSE/NOTICE·포함 코드, 빌드 플러그인 의존성, JDK·컨테이너 OS 구성요소까지 검토한 법률 감사는 아니다. 실제 배포 전 해당 배포물의 고지와 적용 조건을 별도로 확인하고, 의존성을 변경하면 목록을 갱신한다. 테스트 전용 구성요소와 서비스 배포 구성요소는 구분한다.

JDK·PostgreSQL·주요 도구의 직접 라이선스와 고정 버전 근거는 [ADR-0001](docs/adr/0001-technology-baseline.md)을 따른다. 상용 기능이나 유료 라이선스가 필요한 제품을 자동 도입하지 않는다.
