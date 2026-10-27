# Hostinger 공개 데모 배포 진행

기준일: 2026-10-28, Asia/Seoul. **로컬 배포 준비·검증과 서버 PostgreSQL 이미지·검증 소스 전달을 완료했다. Webconsole 복구와 임시 Docker 관리 연결 승인은 대기 중이며 공개 HTTPS 배포는 아직 완료하지 않았다.** 사용자는 서버 접속·DNS/설정 변경·전용 DB 생성·배포를 승인했다. [실행 계획](../superpowers/plans/2026-10-27-hostinger-public-demo.md)과 [배포 절차](../../deploy/worklog/README.md)를 따른다.

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
- 전용 빌드 로그와 종료 파일을 읽어 PostgreSQL 빌드 **exit0**을 확인했다. 이미지 ID는 `sha256:527cbff2154ce6294a46af160e70c4988ed625db9d3ccacd43ccee159731d8c8`이다.
- 준비 커밋 `180689dd0ee4a213a85159fc07d1e77b32ab43c3`의 runtime 변경분41,242bytes를 분할 전송했다. 서버에서 SHA256 `9142ab479f512165feb1785646a5dc269eea8bed971c7e83c8fad66be23046b8` 대조와 source 준비 표식을 확인했다. 문서·Git·로컬 DB·비밀파일은 전송하지 않았다.
- 소스 전달 후 외부 HTTP는 PriceSys401·데모 health200·포트폴리오200으로 기존 기준을 유지했다. 이 시점의 컨테이너 ID/시작시각 재대조는 아직 수행하지 못했다.
- 다음 빌드·기동을 위한 임시 Docker CLI 프로젝트의 구체 설정을 준비했다. Docker socket 연결은 컨테이너 제한과 별개로 호스트 전체 Docker 관리 권한을 부여하므로 실행 직전 별도 승인을 요청했으며 **아직 연결하지 않았다**. 승인 후에도 기존 서비스는 조회·전후 비교만 하고 작업 뒤 관리 연결을 해제한다.

진단·전송 근거와 화면은 ignored `target/console-diagnostic/`에 보존한다. 이 대체 경로 확보를 Webconsole 복구 완료로 표현하지 않는다.

## 남은 작업

1. 실제 Chrome 오류 근거를 확보하고 Webconsole 복구 여부를 확인한다. 기존 서비스 영향이 있는 재부팅은 임의로 하지 않는다.
2. 임시 Docker 관리 연결 승인 후 기존 컨테이너 ID/시작시각을 재대조하고 app/migrate 이미지를 자원 제한한 전용 빌더로 만든다.
3. 서버 전용 비밀파일을 생성하고 전용 DB healthy → migration → owner 사전 검사 → bootstrap → 샘플 검증 → app 순으로 기동한다.
4. 실제 Docker gateway 하나만 신뢰하도록 설정하고, 기존 Caddy 원본 hash 재확인·새 호스트 블록 후보 diff/validate 후 HTTPS를 적용한다.
5. 외부 HTTPS·브라우저·위조 전달 헤더·직접 backend/DB 비공개와 기존 서비스 ID/시작시각·HTTP 기준을 전후 대조한다.

현재 서버의 WorkLog DB/웹앱 기동, Caddy 수정/reload, TLS 발급, 외부 로그인 검증은 **미수행**이다. 저장소 전체 취약점 재검사·새 장시간 부하·실고객 수용도 수행하지 않았다. 기존 이미지의 잔여 보안 경고를 이번 배포 준비로 해결했다고 표현하지 않는다.
