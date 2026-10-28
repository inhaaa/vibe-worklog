# Hostinger 공개 데모 배포 진행

이 문서는 `180689d`·V10 **최초 배포 이력**이다. 이후 최신 완료 메뉴 `efd13ea`·V13의 실제 반영·검증은 [후속 배포 결과](public-menu-deploy-20261028.md)를 따른다.

기준일: 2026-10-28, Asia/Seoul. **[공개 합성 데모](https://worklog.inhaaa.com) 배포와 외부 HTTP40개·브라우저 입장 검증을 완료했다. Webconsole의 새 root 세션 응답과 임시 Docker 관리 연결 해제도 확인했다.** 배포 소스는 `180689dd0ee4a213a85159fc07d1e77b32ab43c3`이다. 사용자는 서버 접속·DNS/설정 변경·전용 DB 생성·배포와 임시 Docker socket 연결·작업 후 해제를 명시 승인했다. [실행 계획](../superpowers/plans/2026-10-27-hostinger-public-demo.md)과 [배포 절차](../../deploy/worklog/README.md)를 따른다. 상용 운영·실고객 수용 완료를 뜻하지 않는다.

## 구현과 격리

- 확정 커밋 `8fe09e5`에서 `codex/worklog-public-demo` 작업트리를 분리했다. 같은 저장소의 메뉴 개발 파일은 수정하지 않았다.
- `public-demo` 단독 프로필만 외부 합성 계정 로그인을 활성화한다. `local`/다른 프로필 혼용과 운영자 활성화는 시작을 거부한다. DB 해시·정확한 예약 identity/역할·RLS·CSRF·세션 현재성 검증을 유지한다.
- 공유 데모 계정의 사원 변경·역할 변경·재초대는 변환된 UUID를 기준으로 서비스에서 거부한다. 대소문자·쿼리·앞뒤 URL 공백을 통한 우회를 회귀 검증했다. 일반 local 프로필의 기존 동작은 보존했다.
- 별도 Compose project `worklog-demo`, DB `worklog_demo`, 전용 네트워크·볼륨을 사용한다. 앱은 loopback `18081`만 게시하고 DB 포트는 게시하지 않는다. 모든 운영 명령은 `-p worklog-demo`로 상위 환경의 프로젝트 이름을 덮어쓴다.
- 앱에는 app/auth DB 자격만, 별도 migration/bootstrap에는 migration 자격만 전달한다. 역할별 비밀번호는 새 난수로 준비하며 Git·로그·채팅에 기록하지 않는다.
- bootstrap은 명시적 확인 문자열·loopback JDBC·실제 DB명과 예약 identity를 검사한다. FORCE RLS를 유지하므로 전체 tenant 검사는 앱을 멈춘 상태의 owner `preflight.sql`로 따로 수행한다.
- 공유 가상 자료와 개인정보 입력 금지를 소개에 안내한다. 실제 메일·운영자 공개·로컬 DB 복사는 포함하지 않는다.

## 실제 로컬 검증

| 범위 | 결과 |
| --- | --- |
| 최종 Java 전체 | **530개: 단위107·PostgreSQL 통합423, 실패/오류/skip0**, Maven `verify` 성공. |
| 프론트 | **96개 통과**, TypeScript·Vite production build 성공. |
| Python | **69개 통과**. 첫 실행은 상위 환경의 JDK8을 참조해 신뢰 저장소 테스트가 실패했으며, 프로젝트 JDK25를 프로세스에만 지정한 재실행은 전체 통과했다. |
| 신규 공개 인증/CLI | 프로필 혼용·운영자 거부·Secure 쿠키, URL/DB/확인문자열·재실행 충돌·RLS 및 타 tenant 보존을 검증했다. |
| 공유 계정 보호 | 서비스 공개3/local3·기존 사원/계정28·공개 HTTP13을 검증했다. 서비스 미구현 시 공개3건의 assertion 실패를 관측했고 최종 전체 실행에서도 통과했다. |
| 독립 Compose 초기화 | 읽기 전용 DB root filesystem·tmpfs·postgres 사용자로 새 볼륨 healthy, V1~V10 migration exit0, public bootstrap exit0. 기존 기본 로컬 DB는 대상에 포함하지 않았다. |
| 샘플 RED/GREEN | bootstrap만 존재하면 기대 조직 개수로 실패 → 샘플 적용 후 통과. 샘플 배정의 연말 종료가 현재 변경을 막는 문제도 검증 SQL을 먼저 실패시킨 뒤 NULL 종료로 수정하고 새 DB에서 전체 초기화를 재검증했다. |
| owner 사전 검사 | 비owner 거부·다른 tenant 존재 거부·정상 owner 통과. 다른 tenant 거부 실험은 트랜잭션 rollback하여 보존했다. |
| 실행 JAR HTTP | 기존 검증된 JRE 이미지에 새 JAR을 읽기 전용 mount한 별도 로컬 앱에서 기능10항목·기존 HTTP 보안24항목 통과. 새 서버 이미지·외부 HTTPS 검증으로 확대하지 않는다. |
| 리뷰 | 프로젝트 이름 우선순위·공백 UUID 우회·샘플 배정 종료일 3건을 수정했고 독립 재리뷰에서 추가 확정 결함은 없었다. |

샘플은 가상 조직2개·총 사원4명·소속4건, admin의 당해년 월~금09~18 고정 일정과12~13 **예정 휴게**, 당해년 평일/주말 예시 달력, 합성 기초 연차12일이다. 법정 공휴일·법적 연차 발생량을 뜻하지 않는다. 출퇴근 원천·실제 근로/휴게 확인·허용 IP는 생성하지 않았다. 샘플 추가 뒤 bootstrap의 사원1명 검증을 재실행하지 않는다.

검증 근거는 작업트리의 ignored `target/deploy/` 및 Surefire/Failsafe XML에 보존한다. 전역 JDK·PATH·기본 로컬 앱/DB를 바꾸지 않았다.

## 서버에서 실제 수행한 작업

- Hostinger 로그인 후 기존 VPS의 amd64·2코어/8GB·가용 메모리 약4.9GB·가용 디스크31GB와 미사용18081/15433 포트를 확인했다.
- 기존 PriceSys·PriceSys 데모의 컨테이너 ID/시작시각을 `/srv/worklog/.local/existing-containers.before`에 기록했다. 기존 Caddy 원본은 `/srv/worklog/.local/Caddyfile.before`에 보관했다. 당시 Caddy SHA256은 `bbbb3f946d957a751ac145fd4b020131c8ac0ae4a8a40a1afc7046d8455192b4`다.
- `worklog` **CNAME → inhaaa.com, TTL300**만 추가했다. 이후 로컬 DNS에서 VPS `76.13.215.52`로 해석되는 것을 확인했다. 기존 demo·www·루트 레코드는 보존했다.
- 비공개 저장소의 서버 clone은 인증이 필요해 종료했다. GitHub 자격을 서버에 추가하지 않고 허용된 빌드 소스만 전송했다. 기반 아카이브401,738bytes의 SHA256 `df78f3361df3c454b899b90038391fb2a8cc8e1cd610d04a50991cf0648a44ff`를 서버에서 대조한 뒤 `/srv/worklog/source`에 풀었다. `.git`·`.env`·로컬 DB/백업은 전송하지 않았다.
- 기존 빌더를 변경하지 않고 `worklog-demo-builder`를 만들었다. 실제 memory1,610,612,736bytes·CPU quota75,000/period100,000을 확인했다. no-XML PostgreSQL 이미지 빌드를 nohup으로 시작했고 configure 진행까지 관측했다.
- 이어 Hostinger 콘솔이 `Session expired`가 됐다. 관리 화면에서 새 세션 발급/재연결을 시도했으나 빈 화면·`Invalid session_id`가 나타나 사용자에게 콘솔 연결을 요청했다.

## Webconsole 진단과 대체 경로

- 사용자는 Chrome에서도 새 Webconsole이 빈 화면이라고 보고했다. IAB의 관측에서는 터미널이 생기기 전 앱 루트가 비어 있었다. 세션 URL의 토큰은 문서·Git·보고에 기록하지 않는다.
- Hostinger 공개 상태 페이지는 해당 관측 시점에 정상으로 표시됐으나 개별 콘솔의 정상 여부를 보장하지 않는다. 정적 JavaScript 모듈34개의 다운로드·구문·모듈 연결은 성공했다. 실제 브라우저 예외 원인은 확인되지 않았으며 Chrome의 첫 오류 한 줄을 요청했다. 재부팅이나 기존 서비스 설정 변경은 하지 않았다.
- 서버 SSH22는 timeout이었고 로컬 Tailscale 연결·서버 SSH 자격은 확보하지 못했다. 방화벽을 완화하거나 새 로그인 자격을 만들지 않았다.
- 로그인된 Hostinger Docker Manager에서 별도 임시 프로젝트 `worklog-diagnostics-20261028`를 사용했다. 공개 포트·Docker socket 없이 전용 경로만 mount하고 CPU·메모리·PID·로그를 제한했다. 기존 PriceSys 프로젝트는 변경하지 않았다.
- 전용 빌드 로그와 종료 파일을 읽어 PostgreSQL 빌드 **exit0**을 확인했다. 당시 config digest는 `sha256:527cbff2154ce6294a46af160e70c4988ed625db9d3ccacd43ccee159731d8c8`이고, 현재 서버 Docker image inspect의 ID는 아래 manifest digest다.
- 준비 커밋 `180689dd0ee4a213a85159fc07d1e77b32ab43c3`의 runtime 변경분41,242bytes를 분할 전송했다. 서버에서 SHA256 `9142ab479f512165feb1785646a5dc269eea8bed971c7e83c8fad66be23046b8` 대조와 source 준비 표식을 확인했다. 문서·Git·로컬 DB·비밀파일은 전송하지 않았다.
- 소스 전달 후 외부 HTTP는 PriceSys401·데모 health200·포트폴리오200으로 기존 기준을 유지했다. 이 시점의 컨테이너 ID/시작시각 재대조는 아직 수행하지 못했다.
- 이후 사용자가 임시 관리 연결을 승인했다. `worklog-admin-20261028`에서 공식 고정 Docker CLI 이미지·전용 `/srv/worklog`만 연결했다. Docker socket은 제한 옵션과 별개로 호스트 전체 Docker 관리 권한을 갖는다는 경계를 유지했고, 아래 배포 후 실제 mount 제거를 확인했다.

사용자가 제공한 Chrome 오류에서는 필수 ESM4개와 `hostinger.png`가 **503 Service Temporarily Unavailable**로 실패했다. 토큰 없는 해당 파일5개를 별도 요청했을 때는 모두200이었다. 이후 일반 새로고침에서 터미널 렌더를 확인했고, 오래된 세션의 `Invalid session_id`는 닫은 뒤 hPanel 버튼으로 새 세션을 발급했다. 새 세션에서 Enter 응답·root 프롬프트와 `WORKLOG_CONSOLE_RECOVERED`, `/srv/worklog`의 `pwd` 출력을 확인했다. 고객 VPS 재부팅·방화벽 완화·로그인 자격 변경은 하지 않았다. 503의 공급자 내부 원인이나 영구 재발 방지를 확인한 결과는 아니다. 사용자 스크린샷의 session_id는 재기록하지 않았다.

## 서버 배포와 외부 검증 결과

| 범위 | 실제 결과 |
| --- | --- |
| 이미지 | 전용 recovery builder의 memory1.5GiB·CPU0.75·swap1.5GiB를 inspect로 확인하고 runtime/migrate를 순서대로 빌드했다. Maven package·두 이미지 build/load·최종 build exit0. |
| 전용 DB | 새 `worklog-demo-postgres-data`·`worklog-demo-private`에서 DB healthy, V1~V10 migration exit0, owner preflight 통과, public bootstrap exit0, 샘플 최초1회 적용·검증 통과. one-off 컨테이너와 볼륨은 보존했다. |
| 비밀·권한 | 역할별 서로 다른32byte 난수를 서버 `.env` mode600에만 저장했다. 앱은 UID/GID10001, DB는 postgres 사용자, 두 root filesystem은 readonly. 앱에 migration/owner/operator 비밀번호를 전달하지 않았다. |
| 포트·프록시 | DB published ports `{}`. 앱은 `127.0.0.1:18081`만 게시. 실제 app TCP peer `172.16.3.1`을 `/proc/net/tcp6`에서 확인했고 신뢰 정규식은 정확히 `^172[.]16[.]3[.]1$`다. |
| Caddy | 원본 hash 재확인·기존 prefix byte 비교·추가 블록 hash·후보 validate 후 reload 성공. 적용 전/후 전체 active JSON과 디스크 적응 결과를 strict 비교했다. 기존 호스트 블록을 수정하거나 Caddy를 재시작하지 않았다. |
| 외부 인증·권한 | 기본 TLS 인증서 검증을 유지한 .NET client에서 **36개 통과**: 소개·CSRF·쿠키 Secure/HttpOnly/SameSite=Lax·로그인/로그아웃·조직2/사원4·공유 identity 변경 거부·운영자/민감 Actuator 접근 거부 등. |
| 외부 네트워크 | **4개 통과**: HTTP301의 HTTPS/path/query 유지, 위조 forwarded proto의 HTTPS downgrade 차단, 위조 host의 로그인 redirect 오염 차단, 외부 TCP18081 연결 거부/timeout. DB 비공개는 daemon의 실제 port binding으로 별도 확인했다. |
| 브라우저 | 공개 소개의 가상 자료 안내 → 데모로 입장 → 홈·서울 날짜·예정 일정·기초 연차12일 표시를 실제 브라우저에서 확인했다. 출퇴근·신청 자료를 쓰는 검증은 하지 않았다. |
| 기존 서비스 | 빌드 전후와 DB 준비·07:46:03 최종 서버 probe까지 PriceSys·데모 Name/ID/StartedAt 동일. 최종 외부 HTTP는 PriceSys401·데모 health200·포트폴리오200·WorkLog health200/UP. 아래 동시 변경 관측을 구분한다. |
| 관리 연결 해제 | WorkLog 전용 builder2개를 정지했다. 관리 프로젝트는 socket·호스트 디렉터리 mount 없는 network none 구성으로 교체했고 `WORKLOG_ADMIN_DISCONNECTED`를 확인했다. 복구된 Webconsole의 실제 inspect도 `mounts=`·`network=none`·`state=exited`였다. |

서버 Docker inspect 기준 이미지 ID는 PostgreSQL `sha256:8c6816275f298f06bfd18b4ef150999a7328853f796cc8e5e11576d0dd8707c6`, app `sha256:2b217df44aab253e0519290c6aa0d859a5855e4cf7af17f7ef136fa35c37bcee`, migrate `sha256:47b7ea4538bf6725d9bca7a27e589db79a4c45cc1f427affc67a811a2a1d463a`다. app/migrate의 revision label은 `180689d`다.

Caddy 진단에서 두 검증기 조건을 보완했다. Node fetch의 Sec-Fetch-Mode 때문에 관리 API가403을 반환해 native HTTP GET으로 바꿨다. 이어 발견한 JSON 차이는 기존 데모 배포가 `/etc/caddy/Caddyfile.demo-candidate`라는 입력 파일명으로 자동 생성한 file_server.hide 한 값뿐이었다. child 내부에서 현재 파일을 같은 파일명의 readonly alias로 제공해 기존 동작을 보존했다. 비교 예외를 만들지 않았고 호스트의 기존 demo-candidate 파일도 수정하지 않았다. 적용된 main Caddyfile SHA256은 `da4f8a24e779169fe8522eba7d99883612149422fad6acc8e9232aa840b0e059`, 추가 블록 SHA256은 `b2f1a245e7e62fdf77332c798d2b73659793d03742e4d642e10b65a206f5d3c6`다. 근거: [Caddy 관리 API](https://caddyserver.com/docs/api), [file_server 설정 변환 소스](https://github.com/caddyserver/caddy/blob/v2.11.4/modules/caddyhttp/fileserver/caddyfile.go).

최종 probe 이후 콘솔에서 PriceSys 앱이 ID prefix `2e1677b4a6fa`·시작 `2026-10-27T22:46:55.684949502Z`로 교체된 것을 관측했다. 해당 project/working_dir은 `pricesys`·`/srv/pricesys`이고 WorkLog는 `worklog-demo`·`/srv/worklog/source/deploy/worklog`로 분리돼 있었다. 이 작업에서 PriceSys 배포·재시작 명령은 실행하지 않았으며, 교체 주체는 확인하지 못했다. 최신 HTTP401은 재확인했다. 이 후속 상태까지 이전 ID가 계속 보존됐다고 확대하지 않는다.

진단·전송·배포·검증 결과와 화면은 ignored `target/console-diagnostic/`에 보존한다. 최신 문서 마감에서는 링크와 diff를 검사하며 전체 Java/프론트/Python 회귀는 재실행하지 않는다. 위 로컬 회귀는 배포 소스 `180689d`의 실제 실행 이력이다.

## 이용 범위와 미실시 검증

모든 방문자가 같은 가상 자료를 공유한다. 허용 IP는 자동으로 열지 않았으므로 출퇴근 기록은 허용 네트워크 설정을 요구한다. 실제 메일 전달·운영자 공개·실고객 자료는 포함하지 않는다. 위조 XFF를 보낸 뒤 앱의 최종 client IP를 직접 관측하는 검증은 하지 않았다. 활성 Caddy의 XFF overwrite 설정과 실제 프록시 TCP peer 확인을 그 검증으로 확대하지 않는다. 서버 전체 취약점 재검사·새 장시간 부하·실고객 수용·자동 초기화/백업 스케줄은 이번 배포에서 수행하지 않았다. 기존 이미지의 잔여 보안 경고를 이번 배포로 해결했다고 표현하지 않는다.
