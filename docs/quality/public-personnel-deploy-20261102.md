# 업무 메뉴·인사정보 공개 배포 완료

기준일: 2026-11-02, Asia/Seoul. 대상은 가상 자료 공개 데모 [WorkLog](https://worklog.inhaaa.com)이다. 사용자가 승인한 [공개 후속 배포 절차](../development/worklog-public-updates.md)를 따른다. 구현·공개 통합의 로컬 검증은 [인사정보 구현](personnel-workspace.md)·[공개 통합 후보 검증](public-personnel-release-20261102.md), 이전 실제 공개 기준은 [시트 정렬 배포 결과](public-sheet-alignment-deploy-20261102.md)에 보존한다.

**최종 공개 소스 `70a4e81`·V16 반영과 공개98+4항목·PC/390px 화면 검증을 완료했다.** 1차 `fc422d7`에서 백업·독립 복원과 기존43표407행/권한 보존을 확인해 V16을 적용했다. 실제 화면에서 발견한 두 표시 문제는 `70a4e81`의 앱만 교체해 해결했다. 최종 앱 기동은13:47:47 KST, health/반영 확인은13:48:08, 브라우저 최종 기록은13:52:56이다. 아래 두 단위의 검증 수를 합산하지 않는다.

## 승인 범위와 고정 기준

| 항목 | 기준 |
| --- | --- |
| 공개 주소 | `https://worklog.inhaaa.com` |
| 전용 서비스 범위 | `/srv/worklog`, Compose `worklog-demo`, DB `worklog_demo` |
| 기존 볼륨·네트워크 | `worklog-demo-postgres-data`, `worklog-demo-private` |
| 앱 게시·DB 경계 | 앱 `127.0.0.1:18081:18080`, DB 외부 게시 없음 |
| 1차 서버 증거 경로 | `/srv/worklog/.local/personnel-ui-20261102` |
| 독립 복원 DB | `worklog_personnel_restore_20261102` |
| 1차 고정 배포 소스 | `fc422d7f70cc56b3b282ba5787e68fcbe64fc0ad` |
| 이전 실제 공개 소스 | `3529a25bfcc8934433d3ef20362d8dd347921db1` |
| 이전 앱 이미지 | `sha256:a16cee35c41723cae6958ed749ec24b999d5a780f3ca1cbbc7f728c1490bf6b8` |
| 스키마 경로 | 1차 V14→V16, 표시 후속은 V16 유지 |
| 표시 후속 고정 소스 | `70a4e81400825ad7d1fe1a9e720076212d23cb5f` |
| 후속 서버 증거 경로 | `/srv/worklog/.local/personnel-contrast-20261102` |

각 소스 이후의 보고서·문서 커밋은 이미지 소스로 사용하지 않는다. PriceSys·demo·portfolio와 전역 Docker·DNS·Caddy는 일반 메뉴 반영의 변경 대상이 아니다. 실제 개인정보·실고객 자료·실제 메일·운영자 공개는 제외한다.

## 1차 실제 반영: `fc422d7`·V16

정상 Hostinger 웹콘솔 복구 후 서버에서 실행했다. 앞선 검은 화면·무응답은 통합 후보 보고서의 당시 이력이다. 1차 사전 상태 수집은13:19:15 KST, 새 앱 반영/health 확인 기록은13:26:40 KST, 서버 요약 수집은13:35:29 KST다. 앱 반영 기록 시각을 컨테이너의 정확한 시작 시각으로 바꾸어 쓰지 않는다.

- 서버에서 전송 묶음 SHA·패키지 파일집합/모드·가드9개를 통과했다. `3529a25` 기준105파일 패치로 `fc422d7`의 전체481파일을 정확히 재구성했다. 삭제0·링크0이다.
- 최신 공개 시트의 직접 편집·40px 한 줄 행·값 정렬과 공개 가상 자료 안내·CSRF·공유 계정 보호를 보존했다. 신규 인사 POST의 보호 경로와 서비스 `PublicDemoIdentityGuard`는 로컬 공개 통합 회귀 및 실제 공개 차단 검증으로 구분해 확인했다.
- 전용 `worklog-demo-builder`의1.5GiB·CPU0.75 제한으로 runtime/migrate 두 이미지를 준비하고 빌더를 중지했다. 두 이미지의 revision·불변 ID, 동일 JAR, 정적11파일과 SQL16파일의 집합/SHA를 검증했다.
- 사전 메모리는 총8,326,615,040bytes·가용5,143,523,328bytes·swap 여유0, 디스크 여유16,961,253,376bytes였다. 최소 가용 메모리2GiB·디스크4GiB 가드를 통과했다.

| 실행 산출물 | 1차 실제 값 |
| --- | --- |
| runtime 이미지 | `sha256:62be28a78d6881cfdf8d7183d6d43c52ceb2d2c8ffca5c86d14a72ec4424cc97` |
| migrate 이미지 | `sha256:72a59409e2e1dcfdca7c37c174e0488392f22d574a409865bfa642929309f310` |
| 서버 JAR SHA256 | `83619210a0738a7bd20470d9789957c16187826a9600a3cea303ecfa498e5660` |
| 앱 확인 | source `fc422d7`·schema16·health `UP` |
| 서버 설정 | `.env`의 이미지 태그만 변경, 원래0600→0600 및 나머지 바이트 보존 |

### 백업·독립 복원·자료와 권한

WorkLog 앱만 중지하고 primary의 다른 연결0을 확인한 뒤 새 V14 기준선과 custom dump를 수집했다. 고정 독립 복원 DB는 접속을 막은 상태로 생성하고 PUBLIC 권한을 제한한 뒤 열었다. 실제 복원 V14 exact 비교, 동일 migrate 이미지의 독립 DB V16 예행, primary V14 재확인과 V16 적용, 최종 백업 후 exact 비교 순서로 통과했다.

- 기존43개 업무 테이블407행의 모든 원래 열·자료 해시와 열 계약을 보존했다. 기존 RLS/정책·테이블/열 ACL·제약·인덱스·함수·트리거·default ACL·역할 membership과 이미 적용된 Flyway 기록을 비교했다.
- V15 귀속1표와 V16 인사/상태2표만 추가했다. 새3표는 앱 재개 전0행이었으며, 정지 중 최종 상태는 총46표407행이다.46표 모두 FORCE RLS를 확인했다. 독립 예행과 primary의 스키마·보안이 일치했다.
- 독립 복원 DB의 PUBLIC 접속 권한 회수를 확인했고 DB는 보존했다. bootstrap/sample-data와 과거 Flyway 수정, 자동 DB 복원·이전 앱 복귀는 실행하지 않았다.
- 정확한 자료 비교는 앱 정지 후 기준선과 V16 최종 백업 직후 사이에서 수행했다. 앱 재개 뒤에는 공개 사용자 쓰기를 구분하고 스키마/권한을 비교했다. 재개 후 현재 행 해시가 정지 중 기준과 같다는 주장은 하지 않는다.

| 백업 | 크기 | SHA256 |
| --- | --- | --- |
| V14 실제 독립 복원에 사용한 dump | 278,656bytes | `87d5e182d82ef29c98dd7cc3c6bc87a5ba289e6513d6095106f3ea02352f3f6b` |
| V16 적용 후 최종 dump | 298,696bytes | `e8cbd391930a57fd17ce6acbb36493cdce5d8cef3f23670a8b5911dbf7dfafcb` |

V14 dump는 실제 독립 복원과 exact 비교를 마쳤다. V16 최종 dump는 생성 성공과 그 전후 정지 상태의 manifest exact 비교 범위이며, 이 V16 dump의 별도 재복원을 했다고 표현하지 않는다.

### 공개 HTTP·정적 자산·기존 서비스

- 공개 기능/보안98항목을 통과했다. 정상 CSRF 로그인/로그아웃·Secure/HttpOnly/SameSite 쿠키, 미인증 인사/사진 경로와 운영자/진단 차단, 본인 헤더·연간 신청의 읽기 계약, 공유 사원 인사 POST의 소문자/대문자/인코딩 공백·query6경로403과 인사/헤더 불변을 포함한다. 신규 인사 차단 입력은 과도한 직급 길이와 음수 버전으로 쓰기를 방지했다. 양성 업무 저장·승인은 실행하지 않았다.
- 프록시/포트4항목도 통과했다. HTTP301의 경로/query 보존, 위조 forwarded scheme/host 차단과 HSTS, 외부18081 접속 차단이다.
-13:27:48 KST에 실제 HTTPS로 제공되는10개 정적 자산의 SHA가 서버 JAR·검증된 소스/dist와 같음을 확인했다. 사용하지 않는 `/vendor/htmx.LICENSE.txt`는 로그인302 경계로 확인했다. JAR 정적11파일과 공개로 제공된10파일을 구분한다.
-1차 최종 검사에서 PriceSys401, demo health200, portfolio200, WorkLog health200/UP를 확인했다. 기존18개 컨테이너에서 앱과 전용 빌더를 제외한16개는 ID·이미지·시작/종료·마운트·포트·실행/OOM/restart 상태가 같았다. 비실행 JAR 검사2개와 종료한 migration2개를 보존해 최종 컨테이너 수는22개다.
- Caddy SHA256 `da4f8a24e779169fe8522eba7d99883612149422fad6acc8e9232aa840b0e059`를 보존했다. 앱 loopback 단일 게시·DB 비공개·기존 실행 제한과 전용 빌더 중지를 확인했다. DNS·Caddy·다른 서비스·전역 Docker는 변경하지 않았으며 임시 관리 역할·포트·소켓 브리지를 만들지 않았다.

## 공개 PC·390px에서 발견한 두 표시 문제

공개 프로필·신청 화면의 PC와390px 관측 중 다음 RED를 기록했다. HTTP98+4와 정적 SHA 통과는 화면의 시각적 품질까지 보장하지 않았다.

| 문제 | 실제 RED 측정 | 보완 |
| --- | --- | --- |
| 신청 내용 단계의 `유형 변경` 버튼 |390px에서 전경 `rgb(49, 89, 229)`와 배경 `rgb(52, 94, 234)`의 유사한 파란색. 버튼60.21875×40px, 문서375px | `AnnualRequestPage.tsx`의 기존 버튼에 `t-btn` 클래스 추가1줄 |
| 모바일 상단 로그아웃 줄바꿈 |390px에서 긴 breadcrumb213.390625×44px·로그인 표시40.65625×45px와 함께 로그아웃이29.5546875×72px, `white-space:normal`로 세로로 밀림 | `WorkspaceNavigation.css`에 모바일 범위의 상단 배치·축소/말줄임·버튼 줄바꿈 방지5규칙 |

RED 원시는1차 증거 디렉터리의 `contrast-red.json`, `topbar-red.json`, `live-request-mobile-before-fix.jpg`, `live-personnel-mobile-before-fix.jpg`에 보존한다. 최종 공개 GREEN에서 유형 변경은 파란 글자·투명 배경/흰색 부모 배경으로 표시됐고44px 높이와 실제 유형 단계 복귀를 확인했다. 로그아웃은 `nowrap`·56×44px 한 줄이 됐고 로그인 이름은72px 말줄임으로 표시했다. 두 경우390px viewport의 문서 폭은375px였다.

## 표시 후속: `70a4e81` 앱 교체 완료

신규 소스는 `70a4e81400825ad7d1fe1a9e720076212d23cb5f`다. 제품 변경은 위2파일이며, 테스트 파일·의존성·Java·DB/Flyway·Compose/Dockerfile 변경은 없다. `fc422d7`의481파일에서 제품2+문서4의6파일 패치로 신규 Git 전체482파일·모드를 정확히 재구성한다. 삭제0·링크0, 기존 SQL16파일 동일이다.

### 후속 로컬 검증

| 검증 | 실제 결과·범위 |
| --- | --- |
| 버튼 보완 후 공개 React 전체 |22파일322개 통과. 이후 모바일 상단 CSS 추가 전 실행이며, 최종 CSS 이후 전체322개 재실행으로 확대하지 않음 |
| AnnualRequestPage 선택 회귀 | 개발/공개 각각32개 통과 |
| 최종 모바일 상단 CSS 후 선택 회귀 | 개발/공개 각각 App/WorkspaceNavigation2파일42개 통과. 앞선322개에 합산하지 않음 |
| 최종 TypeScript/Vite build | 개발/공개 양쪽 종료0. 공개는 기존 시트 자산을 함께 생성했고500kB chunk 경고는 유지 |
| 후속 JAR package | DB 미사용·테스트 재실행 없는 package 종료0,13:36:30 KST BUILD SUCCESS |
| 최종 로컬 정적 자산 |11파일 모두 최종 dist/static와 바이트 일치. 이전fc 대비 `/react/app.js`·`/react/app.css`만 변경 |
| 후속 배포 도구 | 로컬 가드6개·Python 구문9파일·PowerShell 구문3파일·셸 구문·전체/패치 재구성·전송 내용/모드 통과 |

후속 자산 기대값 파일 SHA256은 `b1db0e159116c21536af636734a091b0c84018042f78427361c683fda2925c99`다. 새 `/react/app.js` SHA는 `0ede64d6001f7ca0cd48fe12c00b9e587e65b79ec86d9b4dae2dbfa67d3a0ed1`, `/react/app.css`는 `bcba6b5c8f65cd02c36c821b9fa6dddd7757c813f7dd438df20d192015a1504a`다. 최종 서버 JAR과 실제 HTTPS 서빙에서 이 값의 일치를 확인했다.

### 후속 서버 실제 결과

후속은 새로운 DB DDL·백업/복원·migration 실행 없이 V16에서 앱만 교체한다. 새 `before`는1차 `final.json`과 현재fc 앱의 이미지/컨테이너 상태, 업무 행을 제외한46표 스키마·권한 메타데이터를 비교했다. 현재 업무 행을 다시 조회·해시하거나 과거 값에 맞추지 않는다.

| 후속 단계 | 실제 결과·증거 | 상태 |
| --- | --- | --- |
| 서버 전송 SHA·압축/패키지 집합·mode |120,096bytes, SHA `4b7ab0fe0f36f8301905364d819136fe7e576cccd741fbeeded6d86bec8a88ea` | PASS |
| 서버 배포 도구 가드 |6개 | PASS |
| `ops.py before` | 실제fc 이미지·V16·46표 메타데이터와1차 최종 기준 대조 | PASS |
| `prepare.py` | 신규REV 전체482파일·변경6파일·SHA/mode | PASS |
| 제한 빌드 | PID `353431` 종료0·runtime/migrate 빌드·전용 빌더 중지 | PASS |
| runtime 이미지 ID·revision | `sha256:3483e0422f96f4bd185f3efcf86a41489bdf209543aed785f9fe3b58e1a803d8`·`70a4e81` | PASS |
| migrate 이미지 ID·revision | `sha256:38172ec24966f3e71b3e63fa89a979d3c07bb2091bb98f3961eebb9b3c04d895`·`70a4e81`, 실행하지 않음 | PASS |
| 서버 JAR SHA·동일성 | `ccef04887398305ddd13f96058bd12322df2a4b07c8e66a55257f04400caa128`, 두 JAR 동일·React 외 backend/lib402개 entry가fc와 바이트 동일 | PASS |
| 서버 자산·SQL | 정적11파일·SQL16파일 SHA 일치·기존 적용 SQL 동일 | PASS |
| `.env` | 태그 한 줄 외 바이트 보존·0600→0600 | PASS |
| app-only 교체 |13:47:47 기동·13:48:08 health `UP`·실제 이미지 일치·V16 유지 | PASS |
| 공개 기능·프록시/포트 | 최종 소스에서98+4항목 재검증·종료0 | PASS |
| 공개 자산 |13:48:29에 HTTPS10파일 SHA 일치·unused vendor302 | PASS |
| PC/390px 표시 | 최종 PC3·390px4화면, 두 표시 문제 GREEN | PASS |
| 브라우저 | error/warn0·문서 가로 넘침 없음·빈 신청 닫기/유형 단계 복귀·viewport 복원 | PASS |
| 서비스·Caddy | PriceSys401·demo200·portfolio200·WorkLog200/UP·Caddy 동일 | PASS |
| V16 스키마/권한 |46표 메타데이터·SQL/Flyway/권한의 전후 exact·DB 변경 없음 | PASS |
| 최종 서버 증거 |13:51:41 요약·`final.json`·builder stopped | PASS |

모든 Compose 명령은 고정 프로젝트·원래 env 절대경로·명시한 file을 사용한다. 후속 app은 `--no-deps --no-build --pull never`로 하나만 교체하며, 새 migrate 이미지는 준비만 하고 실행하지 않는다. 실패 시 상태와 증거를 보존하고 자동 rollback이나 DB 복원을 하지 않는다.

후속 기준22개 컨테이너에서 앱·빌더를 제외한20개의 ID·이미지·시작/종료·마운트·포트·실행 상태가 같았다. 1차 이전의 보존 대상16개도 계속 유지됐다. 후속의 비실행 산출물 검사2개가 추가돼 총24개이며 새 서비스·관리 브리지·외부 포트는 없다. 앱은 loopback18081·read-only rootfs·메모리768MiB·CPU1·cap-drop ALL·PID256을 유지했고 DB 외부 게시도 없다.

최종 PC에서는 인사 등록·신청 유형 창·사원 시트를,390px에서는 본인 헤더·선택 사원 인사 등록·신청 유형/내용을 관측했다. 본인/선택 사원의 구분·헤더 아래 탭·유형 전환 시 날짜/단계·유형 단계 복귀·닫기를 확인했다. 사원 시트4행 모두40px이고 기본/앱/시트 CSS3개를 로드했다. 이 공개 점검에서 업무 자료를 양성 저장하지 않았으며, 저장→헤더 반영은 앞선 로컬 합성 계정 검증을 따른다. 캡처와 `browser-proof.json`, `topbar-green.json`, `contrast-green.json`, `sheet-proof.json`은 후속 로컬 증거 디렉터리에 있다.

## 증거와 한계

로컬 원시는 Git 제외 `target/personnel-deploy-20261102/`와 `target/personnel-contrast-20261102/`, 서버 원시는 위 두 전용 경로에 보관한다.1차 요약은 실서버에서 확인한 값을 저장한 `server-deployment-summary.json`이다. 비밀값·쿠키/CSRF·원문 업무 행·환경 파일 내용·전체 inspect·보간된 Compose 설정은 문서/Git에 넣지 않는다.

| 1차 실제 증거 | SHA256 |
| --- | --- |
| 서버 배포 요약 | `c1c212221085caf4c31986150ed54e7d522b4e0fd36a2632d4f1b4b37a7d3309` |
| 공개98항목 | `dd25ba73bf79ded18962c4cabcee4e2f53cf105f1dae0dbe9a0c67cecd4509bc` |
| 프록시/포트4항목 | `b003a1e34e1f1e90450bb32a9294731a5413e89b259914377df9fa51608bf383` |
| 공개10자산·unused vendor302 | `5724dde5feb6b477dd2fecd32ffb2f002d13d105e33e2e4a5ed7d03544143354` |
| V14 정지 기준선 | `7b5271c4e947c845fb9bd05a6f3b81302e0d53e0e667e098fc8decd4c0df509b` |
| V16 정지 중 최종 manifest | `52151a47460fb3606b994df4025458554b5d6ac8ce4eb37e35c4d8fc6f891386` |
|1차 `final.json` | `bc8d7dee1e3125e79cf32e9b2bb6bfe30c6827f5c4b1007e2bbec036b50a726a` |

| 최종 후속 증거 | SHA256 |
| --- | --- |
| 실서버 배포 요약 | `d4f9c12c231b7432d9ffac5eea8225275e10d155a46f0778bc44472d8ee0bdb3` |
| 공개98항목 | `e1f30daa4671968a00c1ed3b8a7a5ebae2261d97375948ca673f3a9191869e8c` |
| 네트워크4항목 | `c4ba4241dd019eac8b0d449ab908e72a2c50a9ce6f156f9c1a896c35bc603f56` |
| 공개10자산·unused vendor302 | `aba9fa114ed4fe452eb2db3a20e409bb30f591d0334efef679b647fc60748740` |
| 실제 브라우저 관측 | `99aad612462a548b210cfa7dbf5ad59f0851db22f0456aea45669f7ba74b967a` |
| V16 후속 전/앱 직전/직후/최종 스키마·권한 | 모두 `3922182719e051e81592055ce863bbe7bd251ffc84382fa2f437483ee0f1ba82` |
| 후속 서버 `final.json` | `3b53817e6a21823f3daa98d661bb35bdbe7fdf98bb31b3691d87150c86a2b2a0` |

새33분 부하·새 전체 취약점 전수 검사·실제 고객·실제 개인정보·메일·법률/운영 수용은 이번 결과에 포함하지 않는다. 기존 미해결 보안 위험과 Vite chunk 경고는 연결된 보고서의 한계를 유지한다. 후속은 프론트 표시 변경이며 Java711개·Python74개는 앞선 동일 백엔드/도구의 공개 통합 결과로 구분하고 이번 후속에서 전수 재실행했다고 표현하지 않는다.

최종 보고서 갱신은 위 실제 실행 증거를 바탕으로 한 문서 작업이다. 문서 갱신 때문에 코드/DB 검증을 다시 실행한 것으로 계산하지 않는다.
