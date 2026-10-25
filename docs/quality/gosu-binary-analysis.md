# gosu 바이너리·배포 서명 분석과 이미지 판단

## 결론과 적용 범위

2026-10-25 23:09~23:16 KST(14:09~14:16 UTC)에 기존 PostgreSQL 이미지의 gosu 1.19와 공식 1.20을 분석했다. **현재 비루트 Compose를 유지하고 gosu만을 위한 PostgreSQL 파생 이미지는 도입하지 않는다.** 정상 시작·healthcheck의 gosu 호출 경로가 확인되지 않았고, 1.20의 수정은 기존 Go 표준 라이브러리 경고 전체를 해결하는 변경이 아니다. 공식 이미지 갱신을 추적하고 아래 조건이 달라지면 판단을 다시 연다.

이는 패키지 수정 완료나 전체 무취약 판정이 아니다. 기본 Testcontainers의 root 시작은 별도 경로이며, JDK·libxml2의 P1 공급 대기는 [공급 재확인 보고서](security-supply-recheck.md)대로 남는다. 이번에는 애플리케이션 코드·설정·DB·실행 이미지·컨테이너를 변경하지 않았다.

## 입력 바이너리와 도구

| 대상 | 확인 결과 |
| --- | --- |
| PostgreSQL 입력 | Compose의 고정 `postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc`를 `docker image save`로 읽고 layer를 순서대로 대조해 `/usr/local/bin/gosu`를 추출했다. 컨테이너 생성·시작이나 DB 명령은 실행하지 않았다. |
| 이미지의 gosu 1.19 | 1,769,900 bytes, SHA256 `52c8749d0142edd234e9d6bd5237dff2d81e71f43537e2f4f66f75dd4b243dd0`. 공식 1.19 배포물과 바이트 해시가 같다. |
| 공식 gosu 1.20 | 1,774,474 bytes, SHA256 `b1892636c281590f34bc63c1118f058cd9de66c4ea463772e3909672d8b302ac`. release API digest와 같다. |
| 두 바이너리의 build 정보 | Go 1.24.6·linux/amd64·CGO0. `moby/sys/user`만 0.1.0→0.4.1로 바뀌며 `golang.org/x/sys`는 0.1.0이다. 바이너리는 실행하지 않았다. |
| 서명 도구 | 기존 Git 번들 GnuPG 2.4.4 / libgcrypt 1.9.4 / GPL-3.0-or-later. 프로젝트 target의 독립 키링만 사용했다. |
| 분석 도구 | 공식 Go 1.27.3 Windows/amd64 휴대형 ZIP의 크기·SHA256을 확인하고, 고정 모듈 `golang.org/x/vuln/cmd/govulncheck@v1.9.0`을 빌드했다. 두 도구는 BSD-3-Clause이며 govulncheck 최소 Go는 1.26.0이다. |
| 도구 공급 검증 | Go ZIP SHA256 `f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff`. 고정 모듈의 zip/go.mod h1 대조와 Go의 기본 sumdb 검증을 사용했다. 완성 분석 EXE의 SHA256은 `52cf9a0cc46f2c86abf1b04cbf8624bff8da9105090795291d9109959ba8d5cd`다. |
| 격리 | 전역 설치·호스트/셸 환경 변경 없이 child process의 GOBIN/GOPATH/GOMODCACHE/GOCACHE를 target에 지정했다. 스캔은 APPDATA/LOCALAPPDATA도 target으로 분리하고 해당 전용 설정에서 telemetry를 off로 확인했다. |

공식 [Go 배포](https://go.dev/dl/?mode=json), [govulncheck 문서](https://pkg.go.dev/golang.org/x/vuln/cmd/govulncheck), [고정 모듈](https://proxy.golang.org/golang.org/x/vuln/@v/v1.9.0.info), [gosu 1.19](https://github.com/tianon/gosu/releases/tag/1.19)·[1.20](https://github.com/tianon/gosu/releases/tag/1.20)을 근거로 삼았다. 분석 도구의 Go 버전과 분석 대상 바이너리의 Go 버전은 별개다.

## 배포 서명 검증

[공식 INSTALL](https://github.com/tianon/gosu/blob/1.20/INSTALL.md)·릴리스의 전체 지문 **B42F6819007F00F88E364FD4036A9C25BF357DD4**를 [공개 키](https://keys.openpgp.org/vks/v1/by-fingerprint/B42F6819007F00F88E364FD4036A9C25BF357DD4)와 대조했다. 호스트 키링 대신 `--no-options --homedir <전용 target> --no-autostart --no-auto-key-retrieve`를 사용했다.

| 검증 입력 | 결과 |
| --- | --- |
| 공식 1.19·1.20 원본 각각 | `GOODSIG`·일치하는 전체 지문의 `VALIDSIG`, 종료 0 |
| 각 바이너리의 1바이트 변조본 2개 | 원본을 보존하고 새 파일로 생성. 모두 `BADSIG`, 종료 1 |
| 1.20 서명의 1바이트 변조본 | 새 파일로 생성. `BADSIG`, 종료 1 |

정상 2건 통과·변조 3건 거부를 각각 확인했다. 첫 키 import는 agent 자동 시작 차단 때문에 종료 2였지만 `IMPORT_OK`가 기록됐고, 이후 독립적인 오프라인 검증은 위 결과를 반환했다. 이 경고도 보존했다. 새 키링의 ownertrust는 `TRUST_UNDEFINED`로 유지했다. 공식 문서의 전체 지문에 연결된 암호학적 서명 검증이며 키 소유자의 실제 신원 전체 인증을 뜻하지 않는다.

## 고정 DB의 제외 없는 binary 분석

[Go 공식 전체 DB ZIP](https://vuln.go.dev/vulndb.zip)을 내려받아 같은 로컬 snapshot으로 두 바이너리를 검사했다. ZIP은 3,514,290 bytes·SHA256 `ac40b1349760605f07288ad78a600a9496cae9dffca21d352d188974f12d8447`, 추출 파일은 4,713개이며 `index/db.json`의 modified는 **2026-10-21T00:27:29Z**다. DB 수정 시각·다운로드 시각·검사 시각을 구분한다. [공식 file DB 규약](https://go.dev/doc/security/vuln/database)을 따른 `file:///.../target/gosu-analysis-20261025/db/snapshot`에 `-db`를 고정했다.

검사 인수는 `-mode=binary -json -db=<위 file URL> <입력 바이너리>`이며 기본 `scan_level=symbol`을 확인했다. 별도 `-mode=extract <입력 바이너리>`로 GOOS/GOARCH와 심볼 추출도 확인했다. upstream의 [제외 wrapper](https://github.com/tianon/gosu/blob/1.20/govulncheck-with-excludes.sh)는 읽기만 하고 실행·적용하지 않았다. wrapper의 GO-2023-1840 제외와 함수 없는 finding 필터를 원시 결과에 적용하지 않는다.

| 집계 | 이미지 gosu 1.19 | 공식 gosu 1.20 |
| --- | --- | --- |
| raw OSV 응답 | 190개 | 190개 |
| finding 전체 | 71행 | 66행 |
| module 수준 finding | 64행 / 59개 GO ID | 63행 / 58개 GO ID |
| package 수준 finding | 4행 | 3행 |
| symbol 수준 finding | **3행 / 같은 GO ID 1개** | **0행** |
| 추출 심볼 | 2,962개 | 2,971개 |
| 추출 GOOS/GOARCH | linux/amd64 | linux/amd64 |
| 검사 종료·stderr | 종료 0·비어 있음 | 종료 0·비어 있음 |

1.19의 symbol finding은 모두 **GO-2026-6686 / CVE-2026-61801**, `moby/sys/user`의 `ParseGroupFilter`·`GetExecUser`·`GetExecUserPath`다. **기존 scanner 원장의 Medium 1행**이며 새로 발견한 CVE로 세지 않는다. 이번에 확인한 것은 수정판 1.20의 공식 공급·서명과 취약 심볼 경고의 제거다. 1.20은 0.4.1 수정으로 이 finding을 제거하지만 Go 1.24.6 자체를 갱신하지 않는다.

기존 원장 PostgreSQL Go 61행은 **stdlib 59행·moby/sys/user 1행·x/sys 1행**이다. 전부 이 snapshot의 GO ID에 매핑됐으며 [개별 대조 CSV](gosu-binary-findings.csv)에 원래 등급·모듈·DB 매핑·두 버전의 최고 finding 수준을 남겼다. 기존 원장과 등급은 수정하지 않았다.

| 기존 Critical/High stdlib 35행의 1.19 최고 보고 수준 | 행 수 |
| --- | --- |
| module | 33 |
| package | 1 |
| 미보고 | 1 |
| symbol | 0 |

package 수준으로 남는 stdlib 3건은 GO-2026-4602(`os`)·GO-2026-4864(`internal/syscall/unix`)·GO-2026-4970(`os`)이며 두 버전에 공통이다. 마지막 항목이 기존 High 1행이다. 취약 패키지 존재와 취약 함수 포함·실제 호출은 서로 다르므로 추가 분석 대상으로 유지한다.

**해석 한계:** JSON 모드는 취약점 finding이 있어도 종료 0을 반환하므로 종료 코드만으로 통과를 판정하지 않았다. raw OSV는 플랫폼·버전 필터 전 정보이고 위 집계와 원장 행 수를 합산하지 않는다. 추출 심볼이 존재해 전체 module fallback 상태는 아니지만 패키지 경로가 비어 있는 항목이 각각 110/112개이고 inline·심볼 추출 한계도 남는다. [공식 binary 구현](https://github.com/golang/vuln/blob/v1.9.0/internal/vulncheck/binary.go)은 call graph를 계산하지 않는다. 따라서 C/H 35건을 패치 완료·일괄 무영향으로 바꾸거나 symbol 0을 전체 안전으로 확대하지 않는다.

## 실제 시작 경로와 파생 이미지 판단

| 경계 | 이번 확인과 판단 |
| --- | --- |
| 이미지 파일 | 추출된 `/etc/passwd`는 889 bytes, `/etc/group`은 474 bytes이며 둘 다 root:root·0644다. gosu는 root:root·0755다. 신뢰하지 않는 사용자 파일을 제공하는 현재 Compose 마운트는 없다. |
| 실행 중 기본 DB | 비밀 환경값을 제외한 새 `docker inspect`에서 고정 이미지·`User=postgres`·`CapDrop=ALL`·`no-new-privileges:true`·running을 확인했다. 마운트는 데이터 디렉터리와 읽기 전용 초기화 스크립트뿐이다. live `/etc` 내용이나 실제 프로세스 syscall을 검사한 결과는 아니다. |
| 정상 시작 | 이미지에서 추출한 entrypoint는 [고정 공식 소스](https://github.com/docker-library/postgres/blob/e00e1bd34ec5c8a8e7ad89b273b3d42efaf6d5bc/docker-entrypoint.sh)와 SHA256이 같다. `postgres` 명령이고 UID0일 때만 gosu를 호출한다. 현재 비루트 설정은 새/보존 볼륨 모두 그 분기를 건너뛴다. 초기화가 끝났기 때문이라고 설명하지 않는다. |
| healthcheck·exec | 현재 healthcheck는 `pg_isready`를 직접 실행한다. 일반 [docker exec](https://docs.docker.com/reference/cli/docker/container/exec/)는 지정 명령을 실행하며 entrypoint/gosu를 자동 호출하지 않는다. 명시적 gosu 실행·root override·ensure/enforce-initdb 스크립트는 별도 경로다. |
| 기본 개발 테스트 | [DatabaseFixture](../../src/test/java/kr/tna/DatabaseFixture.java)의 기본 Testcontainers에는 사용자·capability·NNP 재정의가 없다. Compose 완화를 이 root 시작 경로에 확대하지 않는다. 공격자가 사용자 파일을 공급할 수 있다는 사실까지 입증한 것은 아니다. |
| parser 경계 | [gosu SetupUser](https://github.com/tianon/gosu/blob/1.19/setup-user.go)는 사용자 파일을 읽은 뒤 Setgroups/Setgid/Setuid를 한다. 명시적 gosu 실행 때 NNP·capability 제거만으로 parser DoS를 차단한다고 주장하지 않는다. Docker/OCI 자체의 사용자 해석은 별도 호스트 구성요소다. |

[공식 수정 advisory](https://github.com/moby/sys/security/advisories/GHSA-mjcv-p78q-w5fw)와 [0.4.1 구현](https://github.com/moby/sys/blob/user/v0.4.1/user/user_utils.go)의 일반 파일 검사·10MiB 읽기 상한·group 행 1MiB 상한을 대조했다. 악성 사용자 파일·DoS exploit은 실행하지 않았다.

현재 판단은 **Compose 유지·공식 PostgreSQL 이미지 갱신 대기**다. gosu만 교체한 파생 이미지는 기존 C/H 35건이나 P1 libxml2를 해결하지 않고 유지·공급 검증 부담을 추가하므로 이번에는 만들지 않는다. 기본 Testcontainers의 root 경로와 기존 Medium 1행은 추적을 유지한다. 공식 이미지가 갱신되면 실제 gosu 버전·digest·서명 근거와 최초/no-op·회귀를 확인한다.

root 시작·명시적 gosu 호출이 운영 경로에 필요해지거나 사용자 파일의 외부 제공/쓰기 경로가 생기면 파생 이미지 필요성을 재검토한다. 그 경우 새 바이너리 서명뿐 아니라 UID·PGDATA·처음/기존 볼륨·RLS/역할·복원·전체 회귀·SBOM을 검증해야 한다. 현재 결론을 다른 실행 구성에 자동 적용하지 않는다.

## 검증 기록과 재현 자료

이번 실행은 실제 서명 2건·변조 거부 3건, 분석 도구 빌드, 고정 DB binary 검사 2건·심볼 추출 2건, 61행 DB 대조와 source/설정/이미지 정적 검토다. Java 회귀·HTTP·부하·새 이미지 빌드·DB 작업은 실행하지 않았다. 과거 396개·HTTP24개 등의 실행 결과를 이번 결과로 재사용하지 않는다.

원본·명령·종료 코드·UTC·해시·도구와 snapshot은 ignored `target/gosu-analysis-20261025/`에 보존한다. 주요 파일은 `image/extraction.json`·`container-policy.json`, `signature/signature-summary.json`, `tools/govulncheck-build.json`, `db/download.json`·`snapshot-manifest.json`, `scan/scan-summary.json`·`*.jsonstream`·`*.extract.jsonl`·`go-coverage-summary.json`, `source/source-summary.json`이다. gosu ELF·공개 키·모의 변조본·DB·도구를 소스 저장소에 추가하지 않는다.
