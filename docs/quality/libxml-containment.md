# PostgreSQL libxml2 경로 제거·검증

기준일: 2026-10-27, Asia/Seoul. 사용자 요청에 따라 **현재 PostgreSQL runtime의 XML parser와 libxml2 소비자를 제거하고 로컬 기본 DB에 반영했다.** 이는 libxml2 upstream 패치가 아니라 현재 구성의 취약 컴포넌트·입력 경로 제거다. 이전 [잔여 영향 보고서](react-residual-impact.md)의 libxml2 P1은 아래 고정 이미지에서 조치했으며, 다른 잔여 경고·운영 수용까지 종료한 것은 아니다.

## 방법과 변경 범위

- [ADR-0004](../adr/0004-postgresql-without-xml.md)에 따라 같은 PostgreSQL18.6·PGDG18.6-1.pgdg12+2 원본/패치/패키징을 사용해 server/client를 **18.6-1.pgdg12+2+worklog1**의 프로젝트 DEB로 빌드했다. 서버 코드는 수정하지 않고 공식 configure의 XML/XSLT/LLVM만 비활성화했다.
- [공통 Dockerfile](../../infra/postgres/Dockerfile)에서 signed APT source·DSC/original/Debian tar3개 SHA256과 common-dev293.pgdg12+1 SHA를 검증한다. 공식 nollvm/nolibpkgs/nodoc/nocheck profile을 사용하되 별도로 upstream SQL regression을 실행했다. PGDG 경로·hardening·ICU·SSL·timezone·storage ABI·공식 libpq와 같은 소스의 btree_gist/PLpgSQL을 유지한다.
- 새 server/client를 함께 설치하고 기존 JIT·LLVM·libxml2·libxslt·xml2 모듈을 정상 APT/DPKG 의존성 처리로 제거했다. GPG/SQLite 제외·OpenSSL u2·gosu1.20 보완과 서비스 최소 권한은 유지한다.
- [이미지 회귀](../../src/test/java/kr/tna/PostgresImageSecurityIT.java)는 SQL 입력·cast·wellformed·XPath/XMLTABLE·정상 binary COPY의 거부와 패키지/ELF/provider 부재를 검증한다. 기존 백업 복원·SCRAM·locale·gpgv 회귀를 유지했다.
- 라이선스와 고정 source/옵션은 [제3자 고지](../../THIRD_PARTY_NOTICES.md)와 runtime의 `/usr/share/doc/postgresql-18/worklog-no-xml-build.txt`에 남겼다. 설치 server copyright는 공식 Debian 원문과 바이트가 같다. 이 산출물을 PGDG 원본 서명 바이너리라고 표현하지 않는다.

## 권한 회수 대신 빌드 옵션을 사용한 이유

XML 타입 I/O·binary receive·XMLPARSE/XMLTABLE 특수식은 함수 EXECUTE 회수만으로 모두 막을 수 없다. TYPE USAGE나 xmloption/xmlbinary에도 전체 disable 설정은 없다. 공식 no-libxml 구현은 parser 진입을 compile-out하고 SQLSTATE **0A000**으로 거부한다. 일부 기존 XML 문자열 출력·NULL/빈 결과와 XML catalog 타입은 남을 수 있으므로 모든 XML 이름의 함수/타입을 삭제했다고 주장하지 않는다. [공식 XML 타입](https://www.postgresql.org/docs/18/datatype-xml.html), [공식 configure](https://www.postgresql.org/docs/18/install-make.html), [18.6 구현](https://github.com/postgres/postgres/blob/REL_18_6/src/backend/utils/adt/xml.c)

현재 DB의 XML 배열/domain/composite 및 의존 객체·관련 함수/식·미검토 확장/루틴·preload blocker는 모두0이었다. 실제 설치 확장은 btree_gist1.8/plpgsql1.0뿐이었다. LLVM은 별도 libxml 소비자라 함께 제외했다. **이 구성에서는 PostgreSQL XML/XSLT와 LLVM/JIT를 지원하지 않는다.** `jit=on` GUC 문자열이 남아도 `pg_jit_available()`는 false이며 provider 파일도 없다. 현재 MVP 업무·Excel 생성은 SQL/XML에 의존하지 않고 실제 회귀를 통과했다.

## 실행 검증

| 범위 | 실제 결과 |
| --- | --- |
| upstream PostgreSQL | 새 source build의 SQL regression **231개 통과**. 별도 PG 빌드 stage에서 postgres 사용자로 실행. |
| Java 전체 | 최종 **496개(단위97·PG 통합399), 실패/오류/skip0**, BUILD SUCCESS. 새 이미지6개 회귀·업무·권한·테넌트·동시성 포함. |
| 차단 대조군 | 이전 이미지에서6개 중4개 보안 기대 실패/기존2개 통과. 최종 이미지6개 모두 통과. **11개 정상 SQL×두 역할×기존/차단 서버=44회**에서 기존은 성공, 새 서버는0A000 거부. 정상 COPY binary도 기존 허용→새 거부. |
| 검사 문법 확인 | 첫 전체 실행의 XMLEXISTS/XMLTABLE PASSING cast에 괄호가 빠진 테스트 문법 오류를 수정했다. 기존/차단 서버의44회 대조 및 위 최종 전체 재실행으로 문법 오류를 차단 성공으로 처리하지 않았음을 확인했다. XPath/XMLTABLE의 cast 선행 거부와 callback 자체 실행을 구분한다. |
| Python | 프로젝트 전용 JDK25 환경에서 기존 **62개 통과**. |
| Linux HTTP | 보안24개·multipart fields6개/file6개, **60.024초 smoke190요청·예상 밖 오류0**. 3고객사 명시 재계산·출력·독립 승인·Excel 반복 hash 통과. 전체18명 고유 출근 명령을 각각2회 전송해 중복 차단도 확인했다. smoke와 명시 추가 요청을 합산하지 않는다. |
| 합성 종료 정합성 | 과거 원천55,800건·당일18개 고유 명령·중복0, 승인/잔여 원장·snapshot 정합성 통과. fixture 정상 종료0·소유 PG 자동 제거. |
| native 파일시스템 | **906개 ELF·alias128개·SONAME148개**, 미지원0. XML/XSLT/LLVM/pgxml 파일·패키지·DT_NEEDED/SONAME·관련 undefined parser import0. PGXS의 with*=no·pg_config의3개 without 옵션 확인. |
| 별도 빌드 정합 | Compose와 실제 HTTP 테스트 이미지의 postgres/psql/initdb/pg_dump/pg_restore/btree_gist/plpgsql **7개 파일 SHA256 동일**. 최종 image/config 식별자는 별도로 보존한다. |
| 물리 복원 | 원본에서 SHA256 manifest의 `pg_basebackup`을 확보해 **별도 소유 볼륨·network none**에 풀고 `pg_verifybackup` 통과 후 새 서버로 기동했다. 원본 볼륨은 복제본에 mount하지 않았다. 원본35개 테이블 전체 행 hash·권한/RLS·클러스터 ID 동일. |
| 논리 복원 | 이전 서버의 전체 custom dump202,206bytes를 격리 클론의 별도 DB에 실제 복원했다. **35개 테이블 전체 행 hash·역할/RLS/스키마·함수 ACL 동일**. DB 이름 차이만 비교에서 분리했다. 복원 뒤 XML 호환성 blocker0. |
| storage/ABI | 기존/새 pg_controldata의 catalog·alignment·block/WAL·identifier/index 등 **12개 제어 값 동일**. 실제 물리 클러스터 기동과 같은 소스 contrib 검증을 함께 수행했다. |
| 기본 DB 반영 | 반영 직전 XML 사전 검사·새 backup/list 검증, 반영 전후 **35테이블 전체 행 SHA256·역할/RLS/Flyway·클러스터·볼륨 동일**. 초기화 skip·V10 no-op·migrate 종료0. 기본 HTTP24개 재통과, 실제 관리자/앱 역할 XML0A000 거부·libxml 파일 및 process maps 부재 확인. |

초기 물리/논리 백업은 앱 쓰기를 잠시 멈추고 확보한 뒤 기존 앱을 즉시 재시작했다. 물리 백업은4,499,348bytes이며 [공식 pg_basebackup](https://www.postgresql.org/docs/18/app-pgbasebackup.html)의 WAL fetch·manifest를 사용했다. 기본 DB 전환 직전에는 별도 새 논리 백업을 다시 확보했다. 로컬 Docker·전용 합성 자료와 기존 합성 데모만 사용했다.

## 이미지 검사

| 역할 | 현재 image ID 앞부분 | 전체 패키지 | 원시 매치 | libxml2/CVE-2026-6653 |
| --- | --- | ---: | ---: | --- |
| 일반 실행 PostgreSQL | `b997fdd17e59` |137|421|패키지/해당 CVE 매치0|
| 실제 HTTP Testcontainers PostgreSQL | `28346b53c7be` |137|421|동일|

각각 이전144패키지·436행에서137패키지·421행으로 바뀌었다. **새 원시 매치0**, 제거 컴포넌트의 원시15행씩/합계30행은 [차분 CSV](libxml-containment-diff.csv)에 보존했다. 30개의 서로 다른 CVE를 패치했다는 뜻이 아니다. 다른 OS·Go 등의 **421행/이미지(C8/H134/M137/L29/N112/U1)**은 남아 있으며 이전 [서비스 영향 판정](react-residual-impact.md)을 함께 따른다.

Syft1.55.0·Grype0.121.0와 이전 고정 DBv6.1.10(built2026-10-26T06:38:44Z, archive SHA256 `214b59fbe13608346ee1b22583ec0531083d6088c33891f3520d0fbdedb1b308`)로 변경2종을 재검사했다. OCI 사슬·archive/SBOM/raw SHA를 검증했으며 ignore/VEX0·외부 이미지/SBOM 업로드0이다. 다른5종의 전체 검사를 새로 실행했다고 표현하지 않는다.

**실행 이미지의 합쳐진 파일시스템**에서 제거한 것이다. 원본 base layer/cache와 source build stage의 문서 생성 도구에는 libxml 관련 코드가 남을 수 있다. pg-build는 고객 데이터 서버용 stage가 아니며 고정된 공식 source/문서만 처리한다. 이를 upstream 취약점 수정이나 저장소 전체의 libxml 부재로 확대하지 않는다.

## 유지 조건과 한계

- 다음 PGDG 업데이트는3개 source hash·common-dev·custom version·profile을 함께 갱신하고 같은 패키지/ELF/XML 거부·복원·업무 회귀를 재실행한다. stock 서버 패키지로 수동 교체하면 XML이 재활성화될 수 있다. 공식 수정판 공급 후에도 XML/JIT를 자동 재활성화하지 않는다.
- LLVM/JIT 제외 뒤의 대규모 분석 성능·운영 용량/SLA는 검증하지 않았다. 이번60초는 기능 smoke이며 이전33분 부하를 새 구성 성적으로 재사용하지 않는다. 현재 XML 객체/확장이 없는 조건을 벗어나면 교체 전 별도 검토가 필요하다.
- UAF 악성 exploit·전체 침투·호스트/커널/임의 C확장·숨겨진 정적 동등 parser 전체의 부재를 증명하지 않았다. 차단은 정상 non-null 입력과 공식 compile guard·runtime 부재·권한 경계를 검증한 결과다.
- 프론트·앱 업무 코드·호스트 전역 도구/신뢰 설정·외부 배포·메일·커밋·Push는 변경/수행하지 않았다. 기존 React 미커밋 변경은 보존한다.

계획은 [실행 계획](../superpowers/plans/2026-10-27-libxml-containment.md)을 따른다. 원본은 ignored `target/libxml-containment-20261027/`의 source manifest·build log·`java-final-verify.log`·`python-final.log`·`all-xml-statements-before-after.json`·`native-review/`·`source-restores-proof.json`·`storage-abi-proof.json`·`scan/`·`http-probe/`·`maintenance/default-update-proof.json`·`default-xml-block-proof.json`에 보존한다.
