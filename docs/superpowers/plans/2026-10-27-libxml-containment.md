# PostgreSQL libxml2 입력 경로 제거 계획

**Goal:** 수정판 공급을 기다리지 않고 현재 MVP에서 사용하지 않는 PostgreSQL XML parser와 libxml2 실행 컴포넌트를 제거하고 실제 호환성을 검증한다.

**Architecture:** 동일 PostgreSQL18.6·PGDG18.6-1.pgdg12+2 소스와 Debian 패키징을 사용한다. 공식 configure에서 XML/XSLT/LLVM을 비활성화해 서버·클라이언트 DEB를 프로젝트 변형 `+worklog1`로 빌드한다. 기존 libpq·ICU·SSL·locale·18클러스터·테넌트 권한을 유지한다. LLVM/JIT는 libxml 간접 소비자이므로 함께 제외한다. 업무 SQL과 데이터 스키마는 바꾸지 않는다.

**Tech Stack:** 기존 Docker/PGDG APT·Debian 빌드 도구, Java/JDBC·JUnit·Python·Syft/Grype. 빌드 도구는 별도 stage에 한정하며 전역 설치하지 않는다.

## 수용 계약

- SQL REVOKE·xmloption·일부 함수 래핑을 전체 차단으로 사용하지 않는다. SQL/XML 입력·literal/cast·wellformed·XPath/XMLTABLE·binary receive는 권한과 관계없이 parser 이전에 거부돼야 한다. NULL 단락 반환과 기존 xml 출력 보존은 별도다.
- 최종 runtime의 libxml2/libxslt/LLVM/JIT·xml2 모듈 부재, core ELF의 libxml 연결 부재를 검증한다. builder에서 공식 문서를 처리하는 XML 도구는 고객 입력 경로와 구분한다.
- 현재 DB의 XML 직접/간접 타입·SQL 객체·확장/함수/동적 SQL·preload를 읽기 전용으로 확인하고 사용 흔적이 있으면 자동 교체하지 않는다. 기존 전체 백업을 새 이미지의 전용 DB에 실제 복원해 업무·권한을 대조한다.
- 공식 source/packaging SHA256·서명된 APT 인덱스·custom version·구성 옵션·라이선스/출처를 보존한다. 다른 배포판 .so 혼합이나 서버 코드 임의 패치는 하지 않는다.
- 현재 승인된 로컬 설정·DB·컨테이너 범위에서 진행한다. 기본 데이터 삭제·호스트 전역 변경·외부 배포·Push는 포함하지 않는다. 같은18.6 데이터 볼륨 반영 전후 35테이블 전체 행·역할/RLS·클러스터·볼륨을 대조한다.

## 실행

1. [x] 공식 입력 경로·빌드 계약·현재 DB preflight와 소스 검토를 완료한다.
2. [x] 현재 XML 허용 이미지에서 차단 회귀를 먼저 실패시킨다. 기존 백업·SCRAM·GPG 제외 회귀는 유지한다.
3. [x] 공통 PG Dockerfile에서 pinned PGDG 소스·공식 profile·configure override로 custom DEB를 빌드하고 runtime 소비자를 제거한다.
4. [x] 차단 회귀 GREEN, 전체 Java·Python, 새 이미지의 일반/제한 역할·binary 입력·실제 HTTP·합성 데이터 정합성·백업/복원을 검증한다.
5. [x] 변경 runtime 이미지 SBOM·취약점·ELF/패키지 부재를 검사하고, 기존 전체 백업의 격리 복원 및 정상/no-op 기동을 검증한 뒤 로컬 기본 DB에 반영한다.
6. [x] 현재 차단 범위·JIT 비활성 영향·남은 보안 항목·자체 빌드 유지 조건·미수행 검증을 문서화하고 링크·새 파일·git diff/status를 확인한다.

근거는 `target/libxml-containment-20261027/`에 보존한다. 전체 Java는 기존 전용 JDK25/Maven의 `-Dfrontend.skip=true verify`, Python은 JDK25 환경에서 기존62개를 실행한다. 새 XML 차단은 정상적인 non-null 합성 XML만 사용하며 UAF exploit은 실행하지 않는다. 프론트·33분 부하의 이전 결과를 새 실행으로 표현하지 않는다.
