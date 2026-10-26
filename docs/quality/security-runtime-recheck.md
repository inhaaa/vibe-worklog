# 최종 실행 이미지 보안 재검사

## 상태와 검사 범위

2026-10-26 회사코드 없는 [로컬 데모 로그인](../design/local-demo-login.md)을 반영한 새 app/migrate·builder와 기존 PostgreSQL 이미지 4종을 고정해 SBOM·취약점 검사를 완료했다. **원시 매치는 1,289행이며 새로 비교에 추가된 Medium 3개 CVE·5행은 공급자 평가 대기·추가 분석으로 남긴다.** scanner 매치를 실제 악용 가능 취약점 수나 운영 안전 판정으로 해석하지 않는다.

로컬 데모 실제 HTTP **16개**·공통 HTTP 보안 **24개**, 최종 전체 Java **433개(단위65·통합368)**·Python **35개**를 통과했다. Java 실패·오류·건너뜀은 모두 0이고 Java/Python 프로세스 종료는 각각 0이다. **Ryuk 게시 경계 단위5개와 실제 접속·자동 정리 검증도 통과했다.** 선별 Java37개는 보완 당시 기록으로 보존하며 전체 회귀에 중복 합산하지 않는다. 이번 단위에서는 33분 부하를 재실행하지 않았다. [이전 Linux 부하](runtime-image-soak.md)의 15,471요청·p95 241.014ms는 이전 이미지의 성적으로 보존하며 이번 새 이미지에 재사용하지 않는다.

## 이미지 식별과 재현성

| 대상 | Docker가 반환한 고정 imageId |
| --- | --- |
| app | `sha256:b77d4456f12b4ff2fbe8ffedb397868060b98149c9f704acfca214262f8376a1` |
| migrate | `sha256:12c0ab94c24cfea7dbed9f22854cfc79e5fa48cfc573af60c96063ead83d9eb4` |
| build | `sha256:1bb0fc4c393df766fc6bbed05dfd1da90af36819ea80d21a2066b8d0b83af1b6` |
| PostgreSQL | `sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc` |

각 저장 archive에서 **OCI index → linux/amd64 manifest → config**의 실제 바이트 SHA256과 descriptor 연결을 검증했다. 위 imageId와 최종 config digest는 서로 다른 식별자다. 예를 들어 app의 linux/amd64 config는 `sha256:b5220113e986f846d46b0b8ae68b54e3ee055668a97bc796cb778d96c3fb3ff6`이며 archive·SBOM hash와 전체 연결은 ignored `scan/{app,migrate,build,postgres}-manifest.json`에 보존한다. 태그 이름이나 imageId와 config의 단순 문자열 일치로 판정하지 않았다.

| 도구·데이터 | 고정 기준과 확인 |
| --- | --- |
| Syft | **1.55.0·Apache-2.0**. [공식 배포](https://github.com/anchore/syft/releases/tag/v1.55.0)의 Windows amd64 ZIP SHA256 `ada4c9b93af575f479ce80fce6629897272a2d41f300a14bc49bace020767ca2` 대조 |
| Grype | **0.121.0·Apache-2.0**. [공식 배포](https://github.com/anchore/grype/releases/tag/v0.121.0)의 Windows amd64 ZIP SHA256 `f867bbdc14bd8b0d99b3fc47d839af07480a540072b16896373a8ba32b76f588` 대조 |
| 취약점 DB | **v6.1.10**, built **2026-10-25T06:27:34Z**, archive SHA256 `4bdb42924659936a80a73f5df039603dc01734ed6032575b9879745216a713ea`, `db status` valid |
| 검사 방식 | 로컬 archive → Syft JSON → Grype. **squashed** 파일시스템 기준으로 검사하며 삭제된 과거 layer를 현재 설치 패키지로 합산하지 않음 |
| 네트워크·필터 | 원격 패키지 보강·앱 확인·DB 자동 갱신 비활성화. 소스·이미지·SBOM 외부 업로드 없음. 사용자 ignore/VEX 없음, 실제 `ignoredMatches=0` |

도구 checksum 원본과 라이선스, DB 출처·상태, 검사 설정은 ignored 증거에 보존한다. 같은 도구 버전이어도 [이전 검사](http-soak-vulnerability.md)의 DB built/hash와 이번 DB는 구분한다.

## 원시 매치와 이전 원장의 범위

| 대상 | SBOM 패키지 | 원시 매치 | Critical | High | Medium | Low | Negligible | Unknown | Java archive 매치 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| app | 188 | 140 | 5 | 26 | 94 | 12 | 3 | 0 | 0 |
| migrate | 188 | 140 | 5 | 26 | 94 | 12 | 3 | 0 | 0 |
| build | 467 | 510 | 11 | 41 | 359 | 96 | 3 | 0 | 3 |
| PostgreSQL | 160 | 499 | 10 | 143 | 165 | 51 | 129 | 1 | 0 |
| 합계 | — | **1,289** | **31** | **236** | **712** | **171** | **138** | **1** | **3** |

app/migrate의 Java archive 매치 0은 OS·개발 도구까지 취약점이 없다는 의미가 아니다. Critical/High에는 배포판 backport와 upstream 버전 식별 불일치가 포함되므로 원시 등급과 [기존 영향·공급 판단](residual-vulnerability-impact.md)을 구분한다.

새 tracked [원장](security-runtime-matches.csv)은 **이미지 4종만의 1,289행**이다. 기존 [1,294행 검사 기록](http-soak-vulnerability.md)은 이미지 1,287행 + 프로젝트 3행 + 플러그인 4행의 범위로 그대로 보존한다. 두 합계를 단순 증감 비교하거나 이번에 호스트 프로젝트·플러그인 7행까지 다시 검사했다고 표현하지 않는다.

같은 패키지명·버전과 CVE alias의 이전 기록에 **1,284행**을 유일하게 연결했다. primary/related의 동일 CVE 식별자는 중복 제거했으며 CSV roundtrip도 확인했다. 현재 상태는 `previous_exact_version_record_linked` 1,284행과 `vendor_evaluation_pending` 5행이다. **이 연결은 벤더 전수 재평가 완료가 아니다.** 보존된 이전 `unreviewed` 상태 1,054행도 포함하며, 후속 영향 보고서와 공식 근거를 함께 읽어야 한다. 같은 키라는 사실만으로 바이너리 내용·도달성·현재 공급 상태까지 같다고 보증하지 않는다.

PostgreSQL 499행은 이전과 같은 패키지·버전·CVE alias key 집합이다. 이번 검사로 기존 libxml2·glibc·gosu 등 잔여 항목을 수정 완료나 무영향으로 바꾸지 않는다.

## 새로 비교에 추가된 Medium 3개 CVE

| CVE·패키지 | 이번 이미지·매치 | 공식 Noble 상태 | 분류·다음 확인 |
| --- | --- | --- | --- |
| [CVE-2026-111497](https://ubuntu.com/security/CVE-2026-111497) · `libbrotli1 1.1.0-2build2` | app·migrate·build 각 1행, 총 3행. Grype Medium | **Needs evaluation** | **공급자 평가 대기·추가 분석**. 실제 Brotli decoder 소비자·압축 입력과 도달성을 확인하고 배포판 수정 상태를 추적 |
| [CVE-2026-112060](https://ubuntu.com/security/CVE-2026-112060) · `librtmp1 2.4+20151223.gitfa8646d.1-2build7` | build만 1행. Grype Medium | **Needs evaluation** | **공급자 평가 대기·추가 분석**. builder의 RTMP 소비자·실행 명령·외부 응답 입력을 확인 |
| [CVE-2026-112061](https://ubuntu.com/security/CVE-2026-112061) · 같은 `librtmp1` 버전 | build만 1행. Grype Medium | **Needs evaluation** | 위 소비자·입력 분석과 함께 개별 CVE 수정·호환성을 확인 |

공식 Ubuntu 페이지 3개는 **2026-10-26 02:08:22~02:08:25 UTC**에 HTTP 200으로 수집했고 URL·정확한 조회 시각·응답 SHA256을 `sources/manifest.json`에 남겼다. `Needs evaluation`을 수정판 미공급 확정·무영향·검사 제외로 바꾸지 않는다. 라이브러리 존재만으로 현재 앱의 악용 가능성을 확정하지도 않는다. 이 5행은 이전 원장과의 비교에서 새로 추가된 매치이며 코드 변경이 새 취약점을 만들었다는 판정이 아니다.

Noble app/migrate/build의 SBOM에서 zlib **`1:1.3.dfsg-3.1ubuntu2.3`**를 확인했고 **CVE-2026-85091의 이번 매치는 0**이다. [Ubuntu 공식 상태](https://ubuntu.com/security/CVE-2026-85091)도 같은 `.3`를 Fixed 기준으로 표시했다(02:08:26 UTC·HTTP 200, 응답 SHA 보존). 이는 **이전에 적용한 패치의 재확인**이며 이번 새 수정으로 세지 않는다. Debian PostgreSQL의 zlib 판단에 Ubuntu revision을 적용하지 않는다.

## Ryuk 실제 게시·접속·정리 경계

Testcontainers **2.0.5**의 고정 `testcontainers/ryuk:0.14.0`에 [테스트 전용 modifier](../../src/test/java/kr/tna/RyukLoopbackModifier.java)와 [SPI 등록](../../src/test/resources/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier)을 적용했다. Ryuk의 `8080/tcp`만 `127.0.0.1` 임의 호스트 포트로 게시하며 `PublishAllPorts=false`를 사용한다. 포트 형태 변경이나 host network는 거부한다. 단위 **5개 통과** 후 실제 Testcontainers 경로를 검증했다. [공식 RyukContainer 소스](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/utility/RyukContainer.java)의 포트·socket·권한 구성과 대조했다.

| 실제 관측 | 결과 |
| --- | --- |
| 컨트롤러 | image `sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0`, probe 종료0·판정 PASS |
| 게시·연결 | **`127.0.0.1:65528` 한 개만** 게시. 수정한 테스트 classpath에서 실제 Testcontainers 연결 성공 |
| 자동 정리 | Testcontainers 소유 대상 자동 삭제·익명 볼륨 **1개 제거**. 수동 TCP/필터 probe나 컨트롤러 강제 중지를 사용하지 않음 |
| 보존 | 범위 밖 sentinel 유지, 기존 Compose의 선별 inspect 필드·`.testcontainers.properties` SHA 전후 동일. 전체 호스트 설정·DB 내용 검증과 구분 |
| 남은 권한 | **root·privileged 및 RW Docker socket 유지**. `/var/run/docker.sock`의 읽기/쓰기 권한을 줄인 변경이 아님 |

적용 범위는 현재 프로젝트의 테스트 SPI와 고정 Ryuk 이미지다. PostgreSQL 5432 loopback 제한과 별도로 검증했으며 다른 Ryuk 이미지·Testcontainers 버전·원격 Docker 환경은 다시 확인해야 한다. 호스트/엔진 전체 노출·침투 검사는 하지 않았다. Ryuk 컨트롤러 이미지의 취약점 스캔은 위 이미지 4종의 1,289행 합계에 포함되지 않는다.

정상 클라이언트 연결 종료 후 같은 세션의 10초 prune·`removed containers=1`·종료 로그를 확인했다. 후속 읽기 전용 확인에서 daemon의 정상 응답과 대상·컨트롤러의 `No such container`, 익명 볼륨의 `no such volume` 응답을 각각 보존했다. 볼륨은 컨테이너 제거의 `RemoveVolumes` 처리로 함께 제거되므로 별도 volume prune 로그의 `volumes=0`과 구분한다. 범위 밖 소유 sentinel은 자동 정리에서 살아남았고 증거 수집 후 정상 정지했다.

## 현재 로컬 실행과 DB 보존

현재 `http://127.0.0.1:18080`은 회사코드 없이 `admin / admin`으로 로그인한다. `local,local-demo`에서만 활성화하며 실제 DB 계정·해시·정확한 두 역할을 검증한다. 명시 CLI 최초 실행·동일 자료 재실행은 모두 종료0이다. 기존 main DB는 작업 전 업무 집계가 모두0이었고, 예약 고객사1·사원1·계정1·역할2·감사1만 추가됐다. 운영자·토큰·원천 출퇴근·승인 신청은0을 유지했다. Flyway V10·성공10건, RLS34개와 역할·스키마·함수 ACL/마이그레이션 이력 지문은 전후 동일하며 기존 DB 컨테이너 ID·이미지·볼륨·라벨의 선별 필드도 유지했다. 이 집계·ACL 검증을 전체 DB 백업/내용 지문으로 확대하지 않는다.

최종 app의 UID10001·read-only·capability 전체 제거·NNP·loopback 게시와 관리 비밀번호 부재를 확인했다. 실제 이미지에서 추출한 JAR의 production 항목234개는 최종 전체 회귀 후 JAR와 바이트가 모두 같았고, 테스트 SPI·Ryuk modifier 클래스는 포함되지 않았다. 전체 회귀 후에도 실제 데모 HTTP16개·공통 HTTP24개를 재통과했다.

## 완료한 검증과 남은 조건

| 검증 | 현재 결과 |
| --- | --- |
| 최종 이미지 4종 | archive/OCI 연결·SBOM·같은 고정 DB 검사 완료, ignored 0, 1,289행 집계·이전 기록 연결 검증 |
| 로컬 데모 인증 | 역할 경계 보완 후 선별 Java **37개 통과**. 실제 데모 HTTP **16개**·공통 보안 HTTP **24개 통과** |
| 최종 전체 Java 회귀 | **433개(단위65·통합368)**, 실패·오류·건너뜀0. `BUILD SUCCESS`·종료0. 이전 402개와 구분 |
| Python 도구 회귀 | **35개 통과**, `OK`·종료0 |
| Ryuk 게시/접속/정리 | 단위 **5개 통과**·실제 loopback 게시/접속·자동 정리 PASS. 단위5개는 위 전체65개에 포함하며 중복 합산하지 않음 |
| 새 이미지 33분 부하 | **이번 미실행**. 이전 `45df2e1d…` 이미지의 성적을 새 `b77d4456…` 성적으로 전용하지 않음 |

**잔여 P1 JDK/libxml2는 미해결**이다. 이번에는 Windows/Noble JDK·bookworm libxml2의 공식 공급 상태를 다시 조회하지 않았으며, 마지막 [공급 재확인](security-supply-recheck.md)의 시각·조건과 [native 입력 분석](native-input-docker-boundaries.md)을 유지한다. 기존 [gosu 분석](gosu-binary-analysis.md)이나 Java archive 0을 전체 OS 안전 판정으로 확대하지 않는다. 새 3개 CVE의 실제 입력 분석·벤더 평가와 기존 미해결 항목의 후속 검증이 남아 있다. 외부 배포·실고객 적용·운영/파일럿 보안 수용은 하지 않았다.

## 보존한 증거

원시 증거는 ignored `target/security-runtime-20261026/`에 보존한다. `security-scan-complete.json`·`scan-summary.json`·`tools-manifest.json`·`db-status.json`·`scan/*-manifest.json`은 이미지·도구·DB·원시 수치, `ledger-validation.json`은 1,284/5 분류·alias 중복 제거·CSV roundtrip 근거다. `sources/manifest.json`·공식 HTML은 Ubuntu 응답의 URL·조회 UTC·HTTP 상태·SHA256을 보존한다. `demo-http-result.json`·`demo-common-http-result.json`·선별 Java 로그는 이번 인증 검증 근거다.

최종 회귀는 `verify-final.log`·`verify-final-exit.txt`·`regression-summary.json`과 `python-final.log`·`python-final-exit.txt`에 보존한다. DB/실행 JAR는 `main-db-preservation.json`·`main-db-post.json`·`runtime-final-proof.json`으로 확인한다. 실제 Ryuk는 `ryuk/actual-verdict.json`·`ryuk/cleanup-notfound-confirmation.json`·실행 probe/컨테이너/보존 증거와 `ryuk/source-manifest.json`의 공식 원본 응답·조회 UTC·HTTP 상태·SHA256으로 추적한다.

원시 자격 메타데이터·환경값·비밀번호·토큰은 보고서와 원장에 복제하지 않는다. 기존 1,294행 원장은 수정하지 않으며 이번 이미지 매치만 별도 CSV에 보존한다.
