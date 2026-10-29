# 공통 시트·V14 장시간 부하·전체 취약점 최종 검증 (2026-10-29)

**새33분 실측15,465요청·오류0·p95 323.998ms와 종료 정합성, 별도 시트135항목, 전체 취약점9개 역할 검사를 완료했다.** 추가 설치 가능한 공급자 패치 후보는0이며 미수정/공급 대기 위험은 남는다. 제품·runtime 변경이 없어 공개 소스 `12f0f78`·V14를 유지했고 기존 서비스 보존을 확인했다. 운영 수용·침투·호스트 전체 검사 완료를 뜻하지 않는다.

## 실행 기준과 회귀

| 범위 | 이번 실제 결과 |
| --- | --- |
| Java 전체 | 단위115+PostgreSQL 통합541=656, 실패/오류/skip0·Maven exit0 |
| 공개 통합 프론트 |16파일247개, 실패0·exit0; Node22.14.0/npm11.2.0 |
| 공개 통합 Python | 기존69에5개 회귀 추가 후74개, 관련25개·실패0 |
| 실제 Linux 사전 검사 | HTTP24항목·multipart fields/file 각6항목·60초 smoke189요청 실패0·명령 preflight87요청 실패0 |
| 별도 시트 API |135항목·실패0, 소유 DB 전후43테이블 원행 보존 |
| 현재 공개 HTTPS |72항목+프록시/포트4항목·실패0 |
| 이미지 빌드 |frontend/Java build/app/migrate4개 target exit0·실제 실행 JAR 일치 |

Java 실행 기준은 `9923e78`(2026-10-29 13:10:50–13:17:39 KST)이며, 그 뒤 `c33d26f`까지 제품 `src`·`pom.xml`·`Dockerfile`·`infra`·`frontend`·`.mvn` 변경0을 확인했다. `c33d26f`는 HTTP 부하 도구와 회귀 테스트만 고쳤다. Java를 새로 실행했다고 receipt 시각/소스를 고쳐 쓰지 않았으며399개 class·dependency JAR의 SHA를 측정 전후 고정했다. 원본 시트 편집의246개와 공개 AuthPages 보호1개를 포함한 이번 통합247개를 구분한다.

첫 사전 smoke는38요청/40.988초에 HTTP200 승인함의 옛 제목 `지정 승인함`을 요구해1회 실패했다. 실제 `결재 대기함`·대기/페이지/담당자/PENDING 계약을 검증하도록 TDD RED3실패→관련25개→전체74개 순으로 보완했다.21연산·속도·지연·권한 경계는 바꾸지 않았다. 새 DB에서 사전 검사와33분 전체를 다시 실행했고 실패 trial 자료도 보존했다.

이미지 빌드의 PKIX 오류는 기존 Dockerfile이 지원하는 build-only CA secret으로 재시도했다. 실행 JRE·호스트 전역 신뢰/환경은 바꾸지 않았다. 측정 시작 전 wrapper 경로 게이트 오류는 실행 전 거부됐고, wrapper1개만 보완한 차이/원본 manifest를 보존한 뒤 최종 입력을 다시 고정했다.


## 실제 33분 HTTP 부하

측정은2026-10-29 13:48:48.074403–14:21:48.457403 KST(원시 UTC `04:48:48.074403–05:21:48.457403`)의1,980.383초다. 로컬 Linux 앱과 전용 합성 DB에3테넌트×300명·18세션·과거 원천55,800건을 구성했다. 일반 local 인증·CSRF·권한·RLS를 유지했다. public-demo의 고정 공유 계정과 혼합하지 않았고 공개 VPS에는 부하를 보내지 않았다.

| 지표 | 실제 결과 |
| --- | ---: |
| 전체 요청 / 예상 밖 실패 | 15,465 / 0 |
| 예상 권한 거부 | 1,007 (403 515·404 492) |
| 전체 p50 / p95 / 최대 | 33.275 / 323.998 / 2913.145 ms |
| 60초 ramp 후 요청 / 처리율 | 15,356 / 7.996322 req/s |
| ramp 후 p95 / 정상 응답만 p95 | 324.997 / 331.915 ms |
| 완료된32개 1분 구간 요청 범위 | 475–481 |
| 1분 구간 p95 최대 | 568.475 ms |
| Docker 표본 / 최대 간격 | 132 / 15.261938초 |
| DB SQL 표본 / 최대 연결·대기 advisory lock | 131 / 10·1 |

원시 요청15,465행의 성공 조건과21개 연산의 실행을 다시 검사했고 runner·sampler는 모두 exit0이다. 지연은 세션·CSRF·실제 HTTP 본문 전송을 포함하며 client pacing/queue·브라우저 렌더링·외부 인터넷은 포함하지 않는다. 목표8req/s의 paced closed workload이며 운영 SLA·최대 수용량을 의미하지 않는다.

| 연산 | 요청 | 실패 | p95 ms |
| --- | ---: | ---: | ---: |
| `adminMonthForm` | 73 | 0 | 753.668 |
| `approvalInbox` | 205 | 0 | 33.460 |
| `approve.form` | 338 | 0 | 52.167 |
| `approve.submit` | 337 | 0 | 171.357 |
| `approved.detail` | 336 | 0 | 41.510 |
| `auditPage` | 132 | 0 | 103.969 |
| `clock.form` | 830 | 0 | 125.575 |
| `clock.replay` | 827 | 0 | 114.592 |
| `employeeAdminDenied` | 515 | 0 | 24.777 |
| `employeeList` | 43 | 0 | 42.903 |
| `exportPrepare` | 34 | 0 | 571.335 |
| `foreignResultDenied` | 492 | 0 | 31.626 |
| `historicalDay` | 2004 | 0 | 39.118 |
| `leave.form` | 339 | 0 | 36.201 |
| `leave.submit` | 338 | 0 | 167.860 |
| `month` | 2577 | 0 | 539.160 |
| `myRequests` | 1337 | 0 | 38.814 |
| `recalculate` | 39 | 0 | 809.773 |
| `resultDetail` | 1301 | 0 | 54.271 |
| `today` | 3334 | 0 | 124.709 |
| `workbookDownload` | 34 | 0 | 43.421 |

앱은 CPU1·768MiB·PID256, DB는 CPU1·512MiB·PID128로 제한했다. loopback만 게시하고 전용 bridge/gateway `/32`를 사용했다. 앱은 UID10001·read-only·cap drop ALL·NNP·관리 자격 미주입을 확인했다.

| 자원 | Docker CLI 메모리 최소–최대 MiB | CPU 표본 최대 | PID 범위 |
| --- | ---: | ---: | ---: |
| app | 222.0–268.2 | 47.06% | 32–40 |
| db | 105.8–126.9 | 100.43% | 14–18 |

실측 전체 구간의 앱·DB OOM/restart는0이다. 메모리는 Docker CLI의 cache 조정 수치이며 JVM heap/RSS와 같지 않다. SQL 표본의 최대 advisory 대기1은 교착 상태 판정이 아니며 요청 실패는 없었다. 33분을 넘는 장기 누수·장애 복구·외부 네트워크 부하는 이 시험 범위가 아니다.

## 종료 정합성과 보존

앱을 정지한 뒤 read-only repeatable-read SQL로 다음을 대조하고 native fixture를 정상 종료했다. fixture exit0, 소유 PostgreSQL 자동 삭제를 확인했다.

- 과거 원천55,800건의 내용·시간·IP 참조 지문 불변, 당일 IN18건·OUT0·총55,818건·명령 중복0.
- 신청347건=승인343+대기4(초기 대기3 포함), 승인343일=사용343일, 기초1,200일−사용343일=잔여857일. 음수·차감/복원/참조 불일치0. 종료 시 대기는 실패로 세지 않는다.
- 불변 Excel37개와 다운로드 감사40건이 실제 연산 수와 일치하고 초기3개 workbook 지문을 보존했다.
- V14 그룹/코드/근태유형/불변 버전과 기초 신청 정책 참조 불변.
- 측정 전후 source·399개 class·의존 JAR·실행 설정·helper SHA가 일치했다.

## 재현 증거

원시 실행/검사 증거는 `codex/public-sheet-release` 작업트리의 Git 제외 `target/sheet-soak-security-20261029/`에 보존한다. 일반 작업트리에는 별도 `shared-sync-python*` 재검증 자료가 있다. `load-run-r2/verified-measurement.json`, 요청 JSONL, Docker/SQL JSONL, `fixture-before-soak.json`·`fixture-after-soak.json`·`consistency-verdict.json`·전후 publication manifest가 이번 실측 근거다. 과거15,471건 결과를 복사하지 않았다.

## 별도 시트 API와 행 보존

부하900명 DB를 재사용하지 않고 새 전용 native fixture/같은 Linux 앱 이미지·일반 local 인증으로 검증했다. 전용 scope 없는 metadata·기본18080/공개18081 대상은 거부한다.3개 테넌트 관리자의 메뉴5개 GET과 일반 사용자 차단, 조직·사원·결재선·공통그룹·상세코드·근태유형6종 bulk의 CREATE·명령 재전송·UPDATE·stale409·유효/무효 혼합422 원자성·CSRF403·비관리자403·타테넌트404/조회 누출 차단을 확인했다.

read-only repeatable-read SQL로 platform/tna43개 표의 각 행 SHA256 다중집합을 전후 비교했다. **기존94,101행 모두 보존, 새 업무10행+ROW/BATCH 감사24행=34행만 증가**했다. 사원900→901명(계정900 유지), 그룹3→4·코드9→10·근태유형6→7·불변 버전6→8, 새 조직1·미배정 결재선1/버전2다. 과거 원천55,800건·기초 정책 참조는 불변이다. 신규 사원에 계정/소속/일정은 배정하지 않았다. 제품/compiled class/dependency/helper 불변과 정상 fixture exit0·소유 PG 자동 정리도 확인했다. 이135항목을33분21연산 지표에 합산하지 않는다.

## 전체 취약점 검사

[전체 검사·공급 근거](sheet-security-20261029.md)와 [OS 공식 판정 CSV](sheet-security-20261029-os.csv)를 따른다. Syft1.55.0/Grype0.121.0, 새 고정 DB v6.1.10(`2026-10-28T06:23:30Z`)로 프로젝트 Java120·plugin167·Maven52 JAR/소스 자원, npm production/dev, 최종9개 이미지 역할을 검사했다. npm audit는 두 범위 모두0이다.

최종 실효9역할 원시2,676행(C52/H720/M1141/L232/N526/U5), 실패 사전 PG를 포함한 전체10회 검사3,085행이다. primary vulnerability ID289개는 CVE/GHSA 등이 섞여 있어 고유 CVE289개가 아니다. OS2,362행은 공식 수정 설치217·비적용3·제품 오매치4·미수정/수정 미공급2,138행으로 대조했다. 도구314행은 별도 공급/입력 경계에 연결했다. 매핑 누락·추가 적용 패치 후보0이며 ignore/VEX로 경고를 숨기지 않았다. raw 매치 수를 독립 취약점 개수나 실제 악용 가능성으로 해석하지 않는다.

새 Maven Guava와 npm 내부 undici 경고의 공식 번들도 조사했으나 현재 호환 배포판/번들에 해결판이 없어 내부 JAR 강제 치환이나 문제를 해결하지 않는 major 전환은 하지 않았다. 이 미해결 위험을 패치 완료·위험0으로 표시하지 않는다. 공개 VPS 전체 filesystem을 새 export/scan한 것은 아니며, 아래 배포 내용 대조 범위만 검증했다.

## 기존 서비스와 공개 버전 유지

- 로컬: 사전 실행2개+정지20개 컨테이너22개의 ID·이미지·시작/종료·실행·restart·mount 속성·포트 동일. 기본 DB43개 표의 자료/열/ACL/RLS·V14 지문 동일, 앱18080 health200. 원래 snapshot에 OOM 필드가 없어 기존 컨테이너의 OOM 전후 동일은 주장하지 않으며 사후 OOM은0이다.
- 공개: 기존 컨테이너17개와 Caddy SHA, PriceSys401/demo health200/portfolio200/WorkLog health200 동일. 지난 배포의 검증된 V14 지문과 업무43개 표407행·schema/ACL/RLS/정책 참조·Flyway 이력을 읽기 전용 exact 비교했다. 서버 검증 시각은2026-10-29 14:12:52 KST다.
- Docker가 반환하는 mount 목록 순서만 달라 원시 비교가 거부된 기록을 보존했다. 목록 순서를 정규화하되 모든 source/destination/volume/RW 등 속성은 그대로 비교하고 RW 변경이 거부되는 자체 검증을 했다. DB 비교 파일 형식 오류도 자료 변경 전에 거부된 기록을 보존했다.
- 소유 fixture3회는 모두 정상 종료했고 DB는 자동 정리됐다. 이번 로컬의 정지 임시 컨테이너7개/빈 network3개와 공개 metadata 검사 컨테이너3개만 정확 ID·라벨·기초22/17개 제외를 확인해 정리했다. 전용 builder는 정지 상태로 재현 자료와 함께 보존했다. 전역 Docker/DNS/Caddy/기존 PriceSys·demo·portfolio는 변경하지 않았다.

로컬 개발 미리보기5175는 종료 점검에서 연결 거부/리스너0이었다. 이전 단계의 도구 기록에서2026-10-29 07:38 기동·08:01 Ctrl+C 종료를 확인했고 이후 재기동 근거는 찾지 못했다. 이번 작업 시작 당시 포트 기준선은 없어 연속 가동/종료 시점을 단정하지 않으며5175 보존 성공을 주장하지 않는다. 이번 검사의 수정/중지 대상이 아니었고 재기동도 하지 않았다. Docker22개·기본18080·운영 사이트의 검증 결과와 구분한다.

실제 공개 앱 image는 `sha256:78bbfe3e96f637b9fb993a9e16993fe1311b6406bbf0fe53b88e62f2f9904198`, 이번 부하 앱은 `sha256:b8e5545677b9f0d94d085e4b5e4a20d596c31a5ec62d11134fee472852da66a1`이다. outer JAR ZIP SHA는 다르지만480개 entry raw 내용·전체 클래스·production62 JAR·설치 패키지·JRE5/PG4개 주요 파일 SHA가 일치했다. 제품/DB/의존성/runtime 변경이 없고 이번 수정2개 Python 테스트 도구 파일은 Docker 실행 이미지에 COPY되지 않으므로 **불필요한 앱/DB 재배포를 하지 않고 공개 `12f0f78`·V14를 유지했다.**

## 일반 개발본 동기화

일반 `codex/common-sheet`에는 계획·공통 HTTP 테스트 도구2파일·검사 결과 문서만 선택적으로 반영했다. 공개 보안 구현 전체를 merge하지 않았다. 실제 실행한 공개 소스 `c33d26f`와 공용2파일의 SHA가 같음을 확인한 뒤 일반 개발본 Python67개를 다시 통과했다. 공개74개와의 차이는 공개 전용 `test_public_demo_deploy.py`7개이며, 이를 일반 개발본의 미실행/누락으로 합산하지 않는다.

첫 동기화 검사에서는 기본 PATH의 Java8이 선택돼 테스트용 TruststoreRemediation 컴파일이 실패했다(61개·setup error1). 실패 로그를 보존하고 프로젝트 전용 JDK25.0.5의 JAVA_HOME/PATH를 자식 프로세스에만 주입해67개·오류0·exit0으로 재실행했다. 전역 환경/기존 JDK는 변경하지 않았다. 동기화 원시는 일반 worktree의 ignored `target/sheet-soak-security-20261029/shared-sync-python*`에 남겼다. 이번 최종 결과 문서 편집에는 추가 제품 코드 테스트를 반복하지 않고 수치/해시/CSV·상대 링크와 Git diff를 검증했다.

## 범위와 남은 검증

실제 개인정보·실고객 자료·메일 발송·운영자 공개·상시 자동 배포는 수행하지 않았다. 운영 VPS에 지속 부하를 보내거나 호스트/커널/개발 PC 전체 침투·수일 누수·장애 주입·오프사이트 복구·새 브라우저 시각 회귀를 수행한 결과로 확대하지 않는다. 기존 PC/모바일 관측은 이전 배포 보고서의 이력이다. 미수정 OS/개발 도구 위험과 운영 수용/SLA 검증은 남는다.

## 주요 원시 근거 SHA256

| target 기준 상대 경로 | SHA256 |
| --- | --- |
| `load-run-r2/verified-measurement.json` | `3f6650316308d3595e32753aa21f8598fd7f0b72e473b2159157aa6b4502b3c4` |
| `load-run-r2/linux-soak.requests.jsonl` | `9b184225858ed32baf93b1747db1ca61cc21b1bb8331e322ff162518c79a88b9` |
| `load-run-r2/consistency-verdict.json` | `3881cf9ba0fd1882de7a3c27b82d1b795c74d7ebdc4ad3e66d1f958b7fbfd2b6` |
| `sheet-probe-run/sheet-http-result.json` | `2ed7c9f6dbb756c6ef9610d319bb2150cc6e2d1dbd1dffc725022295b5f1b71e` |
| `sheet-probe-run/sheet-data-after.json` | `76206ae8b55cce4c4c75bd359cf4dc9d62c4ad2cf235b2e0dc909cfe3b2395f1` |
| `build/server-after-verdict.json` | `b243f7db733e998d8c1ea2e006e2254396906a535d07284441d1d136e2ef4886` |
| `build/local-containers-final-verdict.json` | `f634f21810e55bb25d386247029081fa25ed824319fee19b4745906ca6662c6a` |
| `cleanup/owned-resources-removed.json` | `f311f749cf3c0d5f865e82873b02a3da9d61c452e74a67df203fe1f8d97511ad` |
