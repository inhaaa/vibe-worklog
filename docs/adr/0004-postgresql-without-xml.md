# ADR-0004: PostgreSQL의 XML/XSLT·LLVM 소비자 제외

날짜: 2026-10-27. 사용자가 libxml2 수정판을 기다리지 않는 안전한 차단과 검증을 요청했다. 현재 고정 근무 MVP는 XML 타입·SQL/XML·XML import·LLVM/JIT에 의존하지 않는다. 현재 DB의 XML 의존 객체·함수/식·확장·preload를 읽기 전용으로 확인한 뒤 이 구성을 채택한다. 실제 완료 결과는 [검증 보고서](../quality/libxml-containment.md)를 따른다.

## 결정

- PostgreSQL18.6과 기존 PGDG `18.6-1.pgdg12+2`의 공식 원본·Debian 패치·패키징을 유지한다. [공통 Dockerfile](../../infra/postgres/Dockerfile)에서 서명된 PGDG APT source와3개 SHA256, 공통 빌드 규칙293.pgdg12+1의 SHA256을 검증한다.
- 공식 `pkg.postgresql.nollvm`, `pkg.postgresql.nolibpkgs`, `nodoc`, `nocheck` profile을 사용하고, 공통 Makefile include 뒤 configure flags에서 XML/XSLT/LLVM만 제외한다. 별도로 upstream SQL regression을 실제 실행한다. 서버 코드를 임의 패치하지 않는다.
- server/client를 같은 custom version **`18.6-1.pgdg12+2+worklog1`**의 DEB로 함께 설치한다. 기존 경로·hardening·ICU·SSL·locale·storage/catalog ABI와 공식 libpq를 유지하며, 같은 소스의 btree_gist·PLpgSQL을 사용한다.
- 기존 JIT 패키지·LLVM·libxml2·libxslt와 xml2 모듈을 정상 APT/DPKG 의존성 처리로 제거한다. XML 파서는 compile-out하며 LLVM/JIT는 이용할 수 없다. 단순히 `jit=off`로 설정해 라이브러리를 남기는 방법은 사용하지 않는다.
- 기존 최소 권한·비루트·loopback·RLS·별도 migration 역할을 유지한다. 업무 SQL·Flyway 스키마 이력은 변경하지 않는다.

## 대안과 범위

SQL 함수 EXECUTE 회수·TYPE USAGE 회수·xmloption/xmlbinary 설정만으로는 타입 I/O·binary receive·XMLPARSE/XMLTABLE 특수식의 parser 진입을 모두 차단할 수 없다. 다른 배포판의 새 .so 혼합이나 일부 심볼 래핑도 ABI·누락 경로 문제 때문에 채택하지 않는다. [공식 XML 타입](https://www.postgresql.org/docs/18/datatype-xml.html), [configure](https://www.postgresql.org/docs/18/install-make.html), [18.6 XML 구현](https://github.com/postgres/postgres/blob/REL_18_6/src/backend/utils/adt/xml.c)

이 결정은 **libxml2 자체 패치가 아니라 현재 runtime의 코드·소비자·새 parser 입력 경로 제거**다. 일부 XML 문자열 출력·NULL/빈 결과는 공식 no-libxml 동작에서 남을 수 있다. XML 이름의 모든 함수가 오류를 내거나 PostgreSQL의 XML 타입 catalog가 삭제된다고 표현하지 않는다.

LLVM을 libxml 없이 다시 유지하는 별도 빌드는 범위를 키우므로 선택하지 않는다. JIT를 요구하는 향후 분석 업무의 성능·기능은 다시 평가한다. 기존33분 성능 수치를 새 구성의 측정값으로 재사용하지 않는다.

## 수용과 유지

1. 정상 non-null SQL/XML·cast·wellformed·XPath/XMLTABLE 입력과 COPY binary의 거부 SQLSTATE0A000을 [이미지 회귀](../../src/test/java/kr/tna/PostgresImageSecurityIT.java)에서 확인한다. cast에서 먼저 거부되는 문장과 callback 자체 실행을 구분한다.
2. runtime의 패키지·ELF 의존성·parser import·xml2/JIT provider 부재와 새 SBOM을 확인한다. 빌드 stage의 공식 문서 처리용 XML 도구를 고객 데이터 runtime과 구분한다.
3. 새 초기화·SCRAM·전체 업무/격리 회귀·기존 전체 논리 백업 복원·별도 볼륨의 기존 물리 클러스터 기동을 검증한다. 기본 DB 반영 전후 전체 업무 행·권한/RLS·클러스터/볼륨을 대조한다. 기존 XML 데이터/객체나 미검토 모듈이 있으면 자동 교체하지 않는다.
4. 새 upstream/PGDG 버전을 반영할 때 원본/패키징/common-dev hash와 profile·옵션을 함께 갱신하고 같은 검증을 재실행한다. stock 패키지로 임의 교체하면 XML이 다시 활성화될 수 있으므로 운영 중 수동 APT upgrade로 이 이미지를 관리하지 않는다.
5. 공식 libxml 수정판이 공급돼도 자동 재활성화하지 않는다. XML/JIT가 필요해지면 승인된 요구와 새 버전·호환성·회귀를 검토한다.

이는 프로젝트 자체 빌드이며 PGDG가 배포한 원본 바이너리와 동일한 서명 산출물이라고 주장하지 않는다. 공식 source·Debian patch·original COPYRIGHT와 DEB의 `/usr/share/doc/*/copyright`를 보존하고 runtime의 `worklog-no-xml-build.txt`에 출처/hash/변경 옵션을 남긴다. PostgreSQL 라이선스 및 제3자 파일별 고지는 원문을 따른다. [공식 라이선스](https://www.postgresql.org/about/licence/)
