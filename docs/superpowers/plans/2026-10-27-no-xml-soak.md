# no-XML/JIT 구성 단계별 게시·장시간 부하 재검증

**Goal:** 사용자가 지정한 순서대로 기존 변경을 기능 단위로 commit→일반 push한 뒤, 현재 no-XML/JIT 이미지의33분 혼합 부하·정합성을 검증하고 문제를 수정·재검증한다.

**Architecture:** 현재 main의 기존 변경을 보존하며 명시 파일 목록으로만 stage한다. 단계별 index snapshot을 target의 별도 폴더에 복사해 검증한다. 장시간 측정은 다섯 기존 단계의 push가 모두 확인된 후 전용 합성 DB·Linux app에서 실행한다. 기본 데이터/서비스와 로컬 백업은 보존한다.

**Tech Stack:** 기존 Git·Docker·Java25/Maven·React/Vitest·Python 표준 도구. 새 의존성을 추가하지 않는다.

## 게시 순서

1. [x] React 페이지·세션/JSON·프론트 및 Java build/runtime 연결·MIT 배포물. index snapshot에서 프론트95개·타입/build·Java 단위/패키징을 확인하고 commit/push한다.
2. [x] 운영자 조회 반환 전 현재 권한 재검증3파일. snapshot에서 해당 단위·DB 통합을 확인하고 commit/push한다.
3. [x] React HTTP·multipart·혼합 부하 구현/회귀6파일. snapshot에서 Python 전체를 확인하고 commit/push한다.
4. [x] 공통 no-XML PostgreSQL·fixture·이미지/Ryuk 테스트7파일. snapshot에서 전체 Java 회귀를 확인하고 commit/push한다.
5. [x] 기존 설계/명세·라이선스 metadata·검증 보고서/CSV·현황 문서. 링크/집계를 확인하고 commit/push한다. 각 단계마다 원격 main과 HEAD 일치를 확인한다.

Git force/reset/rebase·외부 배포는 실행하지 않는다. `.env`, target, node_modules, dist, Python cache와 자격/백업/fixture 원본은 게시하지 않는다. 문서의 과거 미커밋 상태는 당시 이력으로 유지하고 이번 게시 결과를 별도 기록한다.

## 측정 계약

- 기존 XML 지원 PG의 최종33분 표본 `target/react-soak-security-20261027/patched-db-run/`과 현재 app동일 image 및 no-XML PG구성을 대조한다. 실제 image ID·custom PG버전·라이브러리/JIT 부재와 기본 DB 식별자를 고정한다.
- 새 소유 DB에3고객사×300명·원천55,800건,18세션·기존21연산을 준비한다. HTTP 보안·multipart·짧은 smoke·명시 재계산/출력/독립 승인·불변 workbook·고유18명령을 사전 확인한다.
- 총1,980초, ramp60초 후 steady1,920초·목표8req/s를 실제 실행한다. 대형 빌드/전체 테스트/스캔을 측정과 겹치지 않는다. 기본 서비스 상태는 양쪽 표본에서 구분한다.
- 예상 밖 HTTP 오류·업무 검증 실패·종료 정합성 실패·OOM/restart는0이어야 한다.21연산 모두 수행하고 steady 실측 rate가 목표±5%를 벗어나면 원인을 확인한다. 이전과 정확한 요청/거부 수 일치를 강제하지 않는다.
- 전체·steady·연산별 p50/p95/max·분별 분포를 비교한다. 전체/steady p95가 기존 대비25% 넘게 증가하거나 충분한 표본의 연산 p95가50% 넘게 증가하면 입력 비중·자원·DB 대기/실행 경로를 조사한다. 이 수치는 로컬 회귀 조사 기준이며 운영 SLA가 아니다.
- 보조 진단선도 실행 전에 고정한다. steady<7.92req/s, steady p95>300ms, 양쪽100건 이상인 연산의 p95>`max(기준×1.25, 기준+25ms)`, 완전한 분 처리량<456건 또는 p95>500ms가3분 연속이면 조사한다. steady CPU 평균>`max(기준×1.5, 기준+10%p)`와 마지막5분 메모리 중앙값이 첫5분 대비25%·64MiB를 모두 초과하는지도 확인한다. 연산 혼합을 보정한 경험분포와 원시 지연을 함께 보고한다.
- Docker exact ID의 app/DB CPU·메모리/PID·OOM/restart와 동일 DB의 SQL 연결/대기/크기를15초 간격으로 관측한다. native fixture JVM 수치를 Linux 앱 지표로 쓰지 않는다. JIT 기본 on은 이전 쿼리의 실제 JIT 사용 증거가 아니므로 단독 인과효과로 해석하지 않는다.
- 합성 UUID에 따라 연산 비중이 달라질 수 있음을 기록하고 요청 지연에서 pacing·클라이언트 잠금/대기열이 제외됨을 유지한다. 시간 순서·warm-up·표본 부족·로컬 동시 작업 등 비교 한계를 명시한다.

## 완료

6. [x] 동일 최종 구성의33분 부하·자원 관측과 종료 SQL/원장/명령/Excel 정합성을 확인한다. 문제가 있으면 원인 수정·관련 회귀 후 새 최종 표본을 실행한다.
7. [x] 소유 임시 자원 정리·기본 DB/서비스 보존, 실제 측정·비교·미검증 범위를 문서화한다. 결과를 별도 commit/push하고 git 상태와 원격 반영을 확인한다.

상세 원본과 단계별 게시/검증 원장은 `target/no-xml-soak-20261027/`에 보존한다. 부하 완료 전 성공 수치나 성능 무영향을 기록하지 않는다.

실행 결과: 기존5단계는 `0d154cb284ac`→`6808ee75b1b4`→`d77fc08efc6c`→`8ace53fd030b`→`dd42fb55bf35` 순서로 각 commit 직후 Push했다. 그 후1,980.232초·15,471요청·오류/의미 실패0·steady7.999033req/s·p95 253.014ms를 확인했다. 조사 경고0이며 추가 성능 수정은 필요하지 않았다. 정리·기본35테이블 hash/클러스터/볼륨 보존·HTTP UP도 통과했다. [결과 보고서](../../quality/no-xml-soak.md)에 수치·표본 한계·보존 검사 조건 보정과 결과 문서의 별도 게시 범위를 기록한다. 마지막 결과 게시 commit은 Git 이력과 완료 응답에서 식별한다.
