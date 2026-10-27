# 전체 React 전환 실행 결과

기준일: 2026-10-26, Asia/Seoul. [승인된 계약](../superpowers/specs/2026-10-26-react-full-migration.md)과 [ADR-0003](../adr/0003-react-frontend.md)에 따른 로컬 결과다. 기존 업무 확장 설계의 신규 기능·운영 수용·외부 배포 완료를 뜻하지 않는다.

> 2026-10-27 후속: 재점검 3건을 수정하고 Java487개·프론트95개·Python47개·HTTP24개와 최종 실행을 확인했다. 최신 상태는 [수정 보고서](react-review-fixes.md)를 따른다. 아래479/71개와 이미지·브라우저 관측은 최초 전환 당시 이력이다.

> 2026-10-27 보안·장시간 후속: 최신 결과는 [최종 보안·부하 보고서](react-soak-security.md)의 Java489·프론트95·Python62·실제33분/15,471요청·이미지7종·보안 패치와 남은 항목을 따른다. 아래 미실시 표기는 최초 전환 당시의 이력이다.

## 변경

- 소개·로그인/초대/복구·홈/달력·조직/사원/계정/근무·출퇴근/휴가/신청/승인/원장·집계/계산근거/Excel/감사·독립 운영자 화면을 실제 React JSX로 렌더한다. HTML 주입·iframe·Thymeleaf 문법 해석기를 사용하지 않는다.
- 기존 Controller·Java 서비스·URL·폼 인코딩·commandId·expectedVersion·RLS를 유지하고 페이지별 allowlist의 JSON 표현을 추가했다. 일반 진입은 XSS 인코딩된 bootstrap을 소비하고 이후 탐색/폼은 같은 출처 JSON을 사용한다.
- CSRF·기본 거부·현재 권한 재검증·고객사/운영자 세션 분리를 유지한다. 세션이 파기된503은 개인정보를 지우고 일반 업무503은 입력/명령 재시도를 보존한다.
- 저장 대기 중 탐색·뒤로가기·중복 제출, 역순 GET, fragment 이동, POST422의 GET 주소/입력 유지 회귀를 추가했다.
- Maven/npm·Docker 프론트 builder를 연결했다. JAR에서 Thymeleaf 의존성과 기존 템플릿·htmx 실행 자원을 제외했다. 소스 원문은 삭제하지 않고 대조 이력으로 보존했다.

## 실행한 검증

| 범위 | 실제 결과·근거 |
| --- | --- |
| Java·전용 PostgreSQL 전체 | **479개(단위87·통합392), 실패/오류/skip0**. Windows Java25·Testcontainers의 새 전용 PostgreSQL. `target/react-java-verify-final.log` |
| 프론트 | **71개 통과**. 입력/체크 변경·CSRF/복수 역할·승인 표시·오류/미확정·초기화·탐색/저장 경합·세션503·서울 시각·달력. `npm --prefix frontend test` |
| Python 도구 | **47개 통과**, Java25 환경. boot JSON CSRF의 누락/빈값/실행script/중복/손상 거부 포함. `target/react-python-tests-final.log` |
| 타입·production 빌드 | TypeScript6.0.3 검사·Vite8.3.5 빌드 통과. 프론트 bundle 약343kB·gzip 약96kB |
| Maven 패키징 | 기본 frontend 잠금 설치/빌드 후 JAR 생성 성공. 별도 `-DskipTests` 패키징은 위 전체 테스트와 구분. `target/react-package.log` |
| JAR 내용 | React entry·MIT 전문 포함, Thymeleaf JAR·templates·htmx.min.js·이전 app.js 부재 확인 |
| Docker | 고정 Node22.22.2 Linux builder의 프론트 빌드 및 새 app/migrate 이미지 빌드 성공. 최종 서식 정리 뒤에도 재빌드. `target/react-docker-build-formatted.log` |
| 로컬 실행 | app running·migrate exit0, 기존 loopback18080/15432 유지. Flyway10개 검증·V10 no-op |
| 실제 HTTP | 새 런타임에서 기본 보안 smoke **24개 통과**. CSRF·CSP·캐시/노출·미인증 JSON·운영자 기본 차단. `target/phase6-http-result.json` |
| 실제 브라우저 | 데모 로그인, 13개 공통/관리 메뉴, 검색 빈 결과, 직원 상세·계정·근무 배정·원천/확인, 직접 새로고침, 직원 선택 fragment 초점, 모바일 메뉴/가로 넘침, 로그아웃/뒤로가기 개인정보 차단 확인. 앱 console error0 |
| 개발 서버 | 5173 문서 GET·JSON/POST/CSS loopback 프록시·unknownprefix404 확인. 새 백엔드와 데모 로그인→홈→직접 새로고침→로그아웃 실제 브라우저 확인. 임시 개발 서버/탭 정리 |

## DB 보존과 실행 이력

최초 `compose up -d app`은 depends_on 서비스까지 재평가해 DB 컨테이너도 재생성했다. 따라서 **DB 컨테이너 ID 불변 검사는 실패**했으며 성공으로 기록하지 않는다. 기존 `vibe-attendance-local_postgres-data` 볼륨(생성일 2026-10-23T16:30:03Z)을 그대로 재사용했고, PostgreSQL의 기존 데이터 디렉터리 감지·초기화 생략과 Flyway V10 변경 없음을 확인했다. 별도 삭제·볼륨 제거·초기화 명령은 실행하지 않았다. 전체 회귀는 기본 DB를 쓰지 않은 새 Testcontainers 환경에서 수행했다. 이후 앱 재기동은 `--no-deps`로 분리한다.

최종 서식 정리 후에는 새 app/migrate를 `--no-deps`로 갱신했고 DB 컨테이너 ID 유지를 확인했다. 최종 JAR·프론트 빌드·실행 중인 JS의 SHA256 일치, HTTP24개 재확인과 브라우저 재로그인·직원 화면·오류 로그0도 확인했다.

## 의존성과 검증 한계

- 현재 Maven 앱/테스트 의존성은120개다. 기존126개에서 Thymeleaf starter·Boot adapter·엔진·Spring adapter·attoparser·unbescape6개를 제외했다. [라이선스 CSV](../development/dependency-licenses.csv)를 실제 좌표와 대조했으며 미확인0이다.
- 프론트 잠금 metadata122개(플랫폼별 선택 패키지 포함)와 직접 도구의 라이선스를 [CSV](../development/frontend-licenses.csv)·ADR에 기록했다. 실제 설치에 대한 npm audit는0건이었다. React·ReactDOM·Scheduler의 동일 MIT 전문을 소스와 정적 자원에 포함했다.
- TypeScript7.0.2의 Linux 선택적 네이티브 컴파일러 설치 실패를 확인한 뒤6.0.3으로 바꿔 Windows와 Linux 빌드를 확인했다. 최초 실패는 성공으로 계산하지 않는다.
- 새 컨테이너/Node builder의 전체 SBOM·취약점 재검사, 장시간 혼합 부하·운영/파일럿·실메일 검증은 수행하지 않았다. 이전 이미지의 보안·33분 부하를 이번 이미지의 결과로 대체하지 않는다.
- 기존 SSR 폼을 직접 파싱하는 장시간 부하·multipart 진단 스크립트는 당시 이력이다. 이번에는 기본 HTTP smoke를 React 계약으로 갱신했으며 나머지 도구의 현재 JSON 계약 적용·재실행은 미완료다.
- 커밋·Git Push·외부 배포·호스트 전역 설치/신뢰 설정 변경은 하지 않았다.

브라우저 증거는 `target/react-verification/employees-desktop.jpg`에 보존했다. 합성 로컬 데모 자료이며 이 파일과 원시 로그는 ignored target의 로컬 증거로만 보관하고 Git에는 게시하지 않는다.
