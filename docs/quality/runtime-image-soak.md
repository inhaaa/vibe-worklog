# 최종 Linux 이미지·실제 multipart·지속 부하 재검증

## 상태와 이전 결과의 경계

2026-10-26 후속 검증 기록이다. **중간 DB의 wildcard 게시를 발견해 부하를 중단한 뒤 loopback 바인딩을 수정했다. 새 전체 Java 402개·실제 app/DB mapping·HTTP 보안 24개·multipart 12검사·60초 smoke를 다시 확인하고, 최종 Linux 이미지에서 1,980.205초·15,471요청의 지속 부하를 완료했다. 예상 밖 오류·논리 실패는 0이며 종료 후 정합성과 기본 DB 보존도 통과했다.** 이전 401개와 중단 표본은 당시 증거로 따로 보존하며 이번 성적에 합산하지 않는다.

[기존 33분 보고서](http-soak-vulnerability.md)는 당시 Windows native Java 서버의 15,471요청 결과다. [이후 보안 보완](residual-vulnerability-impact.md)과 [multipart 비활성화](../superpowers/plans/2026-10-25-multipart-boundary.md)를 반영한 이번 이미지의 부하 성적으로 재사용하지 않는다. 로컬 검증은 운영 수용·용량/SLA·취약점 해결 선언이 아니다.

## 확정한 이미지와 기본 환경

| 대상 | 실제 결과 |
| --- | --- |
| app | `sha256:45df2e1dd069dd0e3ad801010a7ee73a4acb7e6131087067c1e528205b70f4ad` |
| migrate | `sha256:b4ab7f157b9474def9ab99d1acc49b8b6afa485ee6d87290170eea9f63f1a27c` |
| 빌드·마이그레이션 | 두 이미지 빌드 성공. 기존 DB의 V10에서 migrate no-op·종료 0. 빌드 전용 secret이 런타임에 잔존하지 않음을 확인했다. 일반 앱에 마이그레이션 자격을 전달하지 않는다. |
| 기본 app | 새 이미지로 재기동한 뒤와 합성 부하 종료 후 loopback의 HTTP 보안 24개를 각각 통과했다. 실제 프로세스 UID/GID는 모두 `10001`, 모든 capability 집합은 0, `NoNewPrivs=1`이었다. 최종 상태는 같은 새 이미지로 running이다. |
| 기본 DB 보존 | DB 컨테이너 `7fb05e355fc0`의 ID·mount·labels가 전후 동일했다. 사전/사후 업무 9종의 행 수는 모두 0, Flyway 이력 10개·FORCE RLS 34개와 역할·권한 fingerprint도 동일했다. 전체 사전/사후 JSON이 일치했다. |

9종은 고객사·사원·계정·운영자·토큰·신청·출퇴근 원천·업무 감사·운영자 감사다. 합성 부하는 별도 소유 DB에서 실행했으며 기본 DB의 사전 상태와 종료 후 직접 조회를 대조했다. migrate도 위 새 이미지에서 종료 0을 유지했다.

HTTP 24개는 상태/노출 범위·로그인 CSRF/no-store/CSP·CSRF 없는 POST 거부·익명 업무 경로·만료 htmx·민감/운영자 경로 차단·복구 화면을 확인한다. 기본 DB에는 계정이 없으므로 이 검사만으로 유효한 계정의 로그인이나 multipart credentials 처리를 검증했다고 표현하지 않는다.

## 합성 실행 구성과 IP 계약

중단한 중간 측정은 Linux app `2f1fc8e337c6` + Testcontainers DB `5fe01126e683`에서 실행했다. 부하 생성기를 중단한 뒤 해당 앱·fixture를 종료하고 원본을 `interrupted-wildcard-trial/`에 분리 보존했다. 수정 후에는 **새 Linux app `117157076f05` + owned PostgreSQL `ba50c233b2cb`**를 사용했다. app은 위 image ID와 같다. Windows native fixture는 합성 자료 준비·행 수/정합성 대조와 multipart `enabled=true` 대조군을 담당했으며 Linux 부하의 HTTP 서버로 사용하지 않았다.

| 경계 | 계약 |
| --- | --- |
| 기본 서비스 DB | 기존 비루트·capability 제거·NNP의 hardened Compose DB다. 부하 DB와 구분하고 원래 컨테이너·볼륨을 보존한다. |
| 합성 DB | 수정한 DatabaseFixture가 소유하는 새 Testcontainers DB다. 실제 `5432/tcp` mapping은 **`127.0.0.1:55060` 한 개뿐**이며 wildcard/IPv6 게시가 없다. 표준 이미지의 root 초기화는 유지하며 기본 hardened Compose DB의 비루트 구성을 동일하게 검증한 결과로 확대하지 않는다. |
| Linux app | 새 전용 일반 bridge와 명시적 **`127.0.0.1:60707`** 게시를 확인했다. 중간 실행의 53899와 구분한다. app의 loopback만으로 DB까지 제한되지 않았던 문제를 DB 자체 binding 수정으로 보완했다. outbound 차단·no-egress 검증은 아니다. |
| 허용 IP | 새 gateway **`172.22.0.1/32` 하나**를 사용하며 원천 기록의 실제·검증 IP가 이 주소와 일치함을 SQL로 확인했다. 과거 원천 55,800건의 loopback 이력은 유지했다. 중간 실행의 `172.21.0.1`과 구분하며 넓은 CIDR·proxy 신뢰로 대체하지 않는다. |
| 계정·메타데이터 | fixture가 생성한 18개 합성 사용자와 임의 합성 비밀번호를 사용한다. `fixtureScope=owned-synthetic`·loopback origin·target 내부 파일을 검사하며 비밀번호·CSRF·응답 본문을 보고서/출력에 남기지 않는다. |

[Phase6HttpFixture](../../src/test/java/kr/tna/Phase6HttpFixture.java)의 gateway 함수는 부재 시 기존 loopback-only fixture를 유지한다. 허용 주소는 정확히 한 `/32`로 반환하며 공백·비표준 표기·공개/loopback/IPv6·CIDR·여러 주소 입력을 거부한다. [단위 테스트](../../src/test/java/kr/tna/Phase6HttpFixtureTest.java)에서 미구현 상태의 2개 실패를 확인한 뒤 3개가 통과했다.

DB 수정은 [DatabaseFixture](../../src/test/java/kr/tna/DatabaseFixture.java)의 소유 컨테이너 생성 경로에만 적용했다. `PublishAllPorts=false`와 `5432→127.0.0.1`의 임의 호스트 포트 한 binding으로 기존 wildcard/불필요한 binding을 대체한다. [회귀](../../src/test/java/kr/tna/DatabaseFixtureLoopbackTest.java)는 빈 HostConfig와 상속된 IPv4/IPv6/추가 포트에서 RED 1개→GREEN 1개를 확인했다. 독립 코드 검토에서도 기존 network mode·별도로 지정한 로컬 JDBC 경로를 유지하는 범위와 실제 mapping 증거를 대조했고 수정이 필요한 결함은 발견하지 못했다.

이 변경은 **소유 PostgreSQL의 5432 게시 경계**다. Testcontainers의 Ryuk cleanup controller가 사용하는 별도 기본 포트까지 loopback으로 제한한 변경이나 호스트/엔진 전체 노출 점검은 아니다.

첫 internal 전용 네트워크 시도에서는 app이 running이고 내부 HTTP 18080 기동도 확인했지만 `NetworkSettings.Ports`의 18080 매핑은 빈 배열이고 `docker port` 출력도 비어 호스트 HTTP에 접근하지 못했다. 해당 시도의 활성 multipart 대조군 결과와 정상 종료 기록을 보존했다. 이후 일반 bridge 측정도 위 DB wildcard 게시 때문에 중단했으므로 두 시도 모두 성공한 33분 검증이 아니다. [Docker 네트워크 구분](https://docs.docker.com/compose/how-tos/networking/)·[포트 게시 규칙](https://docs.docker.com/engine/network/port-publishing/)과 app/DB 각각의 실제 mapping을 대조하며 internal 격리·no-egress를 주장하지 않는다.

## 최종 loopback 구성의 multipart 재검증

[전용 CLI](../../scripts/verify-multipart-http.py)는 같은 합성 계정으로 별도 세션 3개를 사용한다. URL-encoded 양성 대조가 성공해야 multipart 거부를 유효한 결과로 해석한다. `--with-file`은 10KB 미만의 무해한 sentinel을 추가하며 tempfile의 부재까지 입증하지 않는다.

| 시나리오 | 최종 비활성 이미지의 기대 결과 | 이번 실제 실행 |
| --- | --- | --- |
| URL-encoded + form CSRF | 로그인 302 `/me/today`, 인증된 HTML 200과 해당 합성 marker | 새 fixture의 fields/file 모두 통과 |
| multipart, CSRF 없음 | 403, 후속 업무 GET은 로그인으로 이동 | 새 fixture의 fields/file 모두 통과 |
| multipart credentials + 유효한 header CSRF | 302 `/login?error`, 후속 업무 GET은 로그인으로 이동. 403만으로 통과시키지 않음 | 새 fixture의 fields/file 모두 통과 |
| Windows native `enabled=true` 대조군 | multipart 인증 성공 시 CLI가 실패해야 비활성화 검출 능력 확인 | 앞 단계의 대조군: 4검사 통과, 마지막 2검사는 실제 인증 성공·업무 GET 200으로 실패. CLI 종료 1로 탐지 |

수정 후 새 fixture에서 HTTP 보안 24개와 fields 6개·작은 file part 6개가 다시 통과했고 각 CLI는 종료 0이다. 중단 전 결과를 재사용하지 않았다. 대조군의 기대된 실패도 최종 이미지의 실패로 합산하지 않는다. 기본/`local`의 ConfigData·bean 2개와 실제 요청을 구분하며, MIME 전체의 일괄 거부·컨테이너 임시 파일 생성/잔존·모든 Servlet/parser 입력의 안전성을 자동으로 주장하지 않는다.

## 중단한 측정과 최종 33분 부하

중단 전 smoke는 60.036초·189요청·예상 밖 오류/논리 실패 0·예상 거부 14개, p50 29.094ms·p95 337.518ms·max 634.790ms였다. 이 기록과 중단한 장시간 표본은 archive에 보존하며 다음 새 측정에 합산하지 않는다.

DB wildcard로 중단한 runner의 시작은 2026-10-25 16:55:22.068399 UTC(KST 10월 26일 01:55:22.068399)였다. 해당 실행은 성공한 33분 결과가 아니다.

수정 후 **새 smoke는 60.206초·189요청·예상 밖 오류 0·논리 실패 0**이며 종료 0이다. 예상 거부 11개(403 4개·404 7개), 200 161개·303 17개다. 전체 p50 **19.676ms**, p95 **379.777ms**, max **587.100ms**였다.

**최종 부하는 실제 runner 기준 1,980.205초를 완료했다.** 측정 구간은 **2026-10-25 17:07:47.372236~17:40:47.577236 UTC / KST 10월 26일 02:07:47.372236~02:40:47.577236**다. wrapper 시작 17:07:43.850602 UTC, 준비 로그인·multipart·두 smoke·중단 표본은 이 표본/시간에 합산하지 않는다.

| 항목 | 최종 결과 |
| --- | --- |
| 서버 실행물·DB | 새 app `117157076f05`·owned DB `ba50c233b2cb`에서 app/5432 모두 loopback 게시 확인 후 실행 |
| 표본·의미 검증 | **15,471요청·예상 밖 오류 0·논리 실패 0**. 예상 거부 1,040개(403 521개·404 519개), 200 12,885개·303 1,546개. runner 종료 0 |
| 전체 지연 | p50 **19.293ms**·p95 **241.014ms**·max **525.491ms**. 예상 거부도 포함한 전체 분포 |
| steady·분 단위 | steady 15,360요청·**7.9991요청/초**, p50 19.279ms·p95 **240.599ms**. 완전한 분 구간마다 478~482요청, 해당 분 p95 최댓값 275.721ms |
| 자원 표본 | Docker 132개·최대 간격 15.0365초·수집 오류 0. OOM/restart 0. 동일 owned DB SQL도 132개 표본 |
| 종료·기본 환경 | 최종 Linux app 정지·OOM false. fixture JVM 종료 0과 마지막 행 수가 직접 조회와 일치했고 소유 PG는 Testcontainers 자동 정리로 inspect 대상이 사라졌다. 기본 app/DB는 유지되었고 종료 후 기본 HTTP 24개 재통과 |

21개 연산 모두 실패 0이다. 빠른 거부 응답이 전체 지연에 포함되므로 주요 연산을 별도로 기록한다.

| 연산 | 요청 | p95(ms) | max(ms) |
| --- | ---: | ---: | ---: |
| 오늘 화면 | 3,328 | 36.830 | 335.346 |
| 월 결과 | 2,586 | 329.597 | 491.796 |
| 결과 상세 | 1,324 | 48.857 | 301.178 |
| 출퇴근 명령 재전송 | 818 | 74.831 | 275.553 |
| 연차 제출 | 330 | 84.004 | 411.696 |
| 승인 제출 | 329 | 102.374 | 328.388 |
| 재계산 | 49 | 462.651 | 525.491 |
| Excel 생성 | 20 | 424.691 | 426.268 |
| Excel 다운로드 | 20 | 37.851 | 38.603 |

## 자원 표본과 종료 정합성

| 측정 대상 | 132개 표본에서 확인한 범위 |
| --- | --- |
| Linux app | Docker CLI memory **441.6~459.9MiB**, PIDs **54~57**, CPU 표본 최댓값 **36.19%** |
| 소유 PostgreSQL | Docker CLI memory **123.9~139.2MiB**, PIDs **12~15**, CPU 표본 최댓값 **63.64%** |
| 동일 DB SQL | 연결 수 최댓값 **7**, advisory lock 대기 수 최댓값 **1**, DB 크기 표본 최댓값 **53,466,815bytes** |

[Docker stats 공식 설명](https://docs.docker.com/reference/cli/docker/container/stats/)에 따라 Linux CLI memory는 cache를 차감한 값이며 PIDs에는 커널 thread가 포함된다. 위 수치는 JVM heap·Java thread 수나 호스트 전체 CPU가 아니다. 약 15초 간격의 표본 최댓값이므로 표본 사이 피크·일시 대기·누수 부재를 입증하지 않는다. Windows native fixture의 JVM·pool 수치는 Linux app 자원 지표에서 제외했다.

종료 후 같은 owned DB를 읽기 전용 repeatable-read로 조회하고 runner·fixture 최종 행 수와 대조했다. `consistency-verdict.json`의 각 판정은 통과했다.

| 정합성 대상 | 확인한 결과 |
| --- | --- |
| 출퇴근 원천·IP | 총 **55,818건 = 과거 55,800건 + 당일 IN 18건**. 과거 fingerprint 동일, 당일 18개 명령 key·중복 그룹 0. 장시간 재전송 818요청 후에도 중복 원천은 없었다. 과거 loopback와 당일 실제/검증 gateway `172.22.0.1`이 일치했고 범위 밖 기록은 0 |
| 신청·승인 | 총 **338건 = 승인 330 + 대기 8**. 초기 대기 3 + smoke 제출 5/승인 1 + 장시간 제출 330/승인 329와 일치. 대기 8건은 초기 3 + smoke 경계 4 + 장시간 경계 1이며 실패로 분류하지 않음 |
| 연차 원장 | 12개 bucket의 **기초 1,200일 − 사용 330일 = 잔여 870일**. 음수·효력·승인 차감·잔여 불일치 0, 대기 연차 8일은 차감하지 않음 |
| 검토용 Excel·감사 | 보고서 **21개 = smoke 1 + 장시간 20**, 다운로드 21개, 감사 4,515개. 조기 확보한 workbook 3개의 digest가 종료 후 동일하고 잘못된 snapshot은 0 |
| 보존·정리 | 기본 DB 사전/사후 전체 JSON 동일, ID·mount·labels 동일. fixture 종료 0·최종 행 수 일치와 임시 app 정지를 확인. 중단 시도의 표본은 최종 성적과 분리 |

같은 Windows PC에서 발생하는 클라이언트→loopback→Docker Desktop Linux 경로다. 외부 TLS/Caddy·운영 네트워크·브라우저 렌더링·900명 전체 동시 활동·장애/수일 부하를 검사하는 구성은 아니다. 프로세스 종료·검증 기간·표본·의미 검사를 함께 확인하고 도구의 final 표기나 종료 코드 하나로 통과시키지 않는다.

## 실행한 검증과 남은 범위

| 검증 | 확인한 결과 |
| --- | --- |
| Python 표준 라이브러리 테스트 | 총 35개 통과. 신뢰 저장소 도구 회귀에 Java 25를 지정했다. 실제 HTTP 검사 성적과 별개다. |
| gateway RED→GREEN | 새 계약의 2개 실패 확인 후 fixture 3개 통과 |
| multipart 설정 회귀 | 기본/local 2개 통과. 위 fixture와 합쳐 Java 5개, 실패·오류·건너뜀 0 |
| 중단 전 전체 Java 회귀 | 당시 `verify` 401개(단위 51·PostgreSQL 통합 350), 실패·오류·건너뜀 0. DB loopback 게시 수정 후 회귀는 아님 |
| DB loopback RED→GREEN | binding 회귀 1개 실패 확인 후 1개 통과. 새 owned DB의 실제 mapping과 독립 코드 검토도 확인 |
| 수정 후 전체 Java 회귀 | `verify` **402개(단위 52·PostgreSQL 통합 350)**, 실패·오류·건너뜀 0 |
| 이미지·기본 앱 | app/migrate 빌드·V10 no-op·새 app 기동·HTTP 24개 통과. 부하 종료 후 기본 HTTP 24개 재통과·실제 비루트/권한 경계 확인 |
| 새 Linux fixture 실제 HTTP | HTTP 보안 24개·multipart fields 6개/file 6개를 다시 통과. 앞 대조군의 마지막 2검사 실패 정상 탐지와 구분 |
| 새 Linux 혼합 부하 | smoke 60.206초·189요청과 최종 1,980.205초·15,471요청 모두 예상 밖 오류/논리 실패 0. 132개 자원/DB 표본·종료 정합성·기본 DB 보존·임시 자원 정리 확인 |

이전 multipart 보완 단위의 전체398개 Java 회귀는 [당시 계획의 실행 결과](../superpowers/plans/2026-10-25-multipart-boundary.md)에 보존한다. 401개·402개·관련 단위 검증은 서로 중복 합산하지 않는다. 실제 포트 게시 경계는 회귀 숫자만으로 입증되지 않아 새 컨테이너 inspect와 함께 확인했다. 이번 실행에서 새 SBOM/취약점 스캔은 수행하지 않았다. 잔여 P1 JDK/libxml2의 공급·검증 조건은 기존 보고서대로 남으며 부하 통과로 해소되지 않는다. 외부 배포·운영 수용은 수행하지 않았다.

증거는 ignored `target/runtime-revalidation-20261026/`에 보존한다. 이미지·기본 환경은 `final-images.json`·`compose-*.log/json`·`main-db-pre.json`·`main-db-post.json`·`base-http-result.json`·`base-http-post-result.json`·`base-process-boundary.txt`, 이전 401개는 `verify.log`, 새 402개는 **`verify-loopback.log`·`test-totals.json`**, binding RED/GREEN은 `loopback-red.log`·`loopback-green.log`다. `owned-db-boundary.json`·`load-identities.json`은 새 실제 mapping/실행물, `linux-http-result.json`·`multipart-probe/linux-fields.json`·`linux-file.json`·`linux-smoke.json`은 HTTP 재검증 증거다.

최종 수치·종료 정합성은 **`verified-measurement.json`·`linux-soak.json`·`linux-soak-exit.json`·`fixture-after-soak.json`·`consistency-verdict.json`·`fixture-exit.json`** 및 Docker/DB 표본에 보존한다. **`interrupted-wildcard-trial/`**·첫 `internal-trial-*`·활성 대조군은 분리 보존했다. 원시 fixture 자격·메타데이터·본문을 tracked 문서에 복제하지 않는다.
