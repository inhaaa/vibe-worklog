# 업무 메뉴·인사정보 공개 통합 후보 검증

> 후속 실제 결과: 웹콘솔 연결 복구 후 `fc422d7`의 V14→V16 백업/독립 복원·자료/권한 보존을 마쳤고, 표시 보완 `70a4e81`까지 공개98+4항목·PC/390px·기존 서비스 보존을 확인해 배포했다. [최종 배포 결과](public-personnel-deploy-20261102.md)를 따른다. 아래 미실시·접속 대기는 후보 준비 당시 상태다.

기준일: 2026-11-02, Asia/Seoul. 개발 검증 커밋 `59b1b49cb9a30f12610589a401c1c3eb1e7fb343`을 최신 공개 작업트리 기준 `fd183b3`에 통합했다. 개발 기능의 [구현 계약](../design/personnel-workspace.md)·[개발 검증](personnel-workspace.md)과 공개 [후속 배포 절차](../development/worklog-public-updates.md)를 따른다. 이 문서는 로컬 공개 후보의 결과이며 실제 서버 배포·복원·기존 서비스 보존 결과는 별도 기록한다.

## 통합 소스와 보존

- 브랜치: `codex/personnel-public-release`.
- 기능 통합: `06cb9f1`. 공개 통합 보완을 포함한 최종 소스: `fc422d7f70cc56b3b282ba5787e68fcbe64fc0ad`.
- 기존 공개 AG Grid 36.2.0, 클릭/F2 직접 편집·Tab/Enter/Escape, 40px 한 줄 행·값 정렬을 보존했다. `WorklogSheet.tsx`, `worklog-sheet.css`, `sheetAssets.ts`, package.json/lock은 공개 기준선과 동일하다. 새 의존성은 없다.
- 업무 메뉴·공통 사원 헤더·인사정보/사진/상태 이력·연간 신청과 V15/V16을 통합했다. 사진 버전 URL, 본인/관리자 한정 민감 헤더와 tenant/RLS 경계를 유지한다.
- 신규 화면 CSS를 `/react/app.css`, 공개 그리드 CSS를 `/react/sheet.css`로 각각 생성하고 기존 `/app.css`와 함께 HTML shell에서 로드한다. 시트의 폰트/SVG 추출과 같은 출처 CSP 계약을 유지한다.
- 공개 가상 자료 안내·CSRF·공유 계정·운영자 차단·공개 프로필 경계를 보존했다. 로그인 기본 진입은 `/me/profile`, 기존 오늘 진입은 `/me/attendance` 안내로 갱신했다.

## 보안 RED → GREEN

`PublicDemoSecurityIT`에 인사 POST의 UUID 소문자/대문자/인코딩 공백과 쿼리 유무 6경로, 서비스 직접 호출, 사원·인사·상태 이력·감사 전체 행 불변 회귀를 먼저 추가했다. RED에서는 첫 HTTP가303으로 인사·상태·감사를 생성했고 직접 서비스 호출도 저장했다. 기존 공유 계정 보호 정규식에 `personnel`을 추가하고 `EmployeePersonnelService.update`가 기존 `PublicDemoIdentityGuard`를 먼저 호출하도록 보완한 뒤 모두403 및 전체 행 불변을 통과했다. 이 검증은 전용 Testcontainers 합성 DB만 사용했다.

CSS도 `app.css` 미생성과 shell 미연결의 RED를 확인한 뒤 앱/시트 자산 생성·실제 selector·CSP 자산·HTML 연결 회귀를 통과했다. 통합 중 기존 공개의 비동기 DOM 테스트 도우미와 개발의 정적 HTML 기대 차이는 실제 폼 값 검증으로 맞췄다.

## 실제 검증

| 검증 | 결과 |
| --- | --- |
| 공개 기준선 npm ci / React | 기존 lock 설치·audit0, 16파일252개 통과 |
| 최종 React 전체 | 22파일322개, 실패0·종료0 |
| TypeScript / Vite production build | 종료0, app.js/app.css/sheet.css와 같은 출처 SVG4개·폰트1개 생성 |
| 프로젝트 JDK25 전체 Maven verify | 단위126 + 실제 PostgreSQL 통합585 = **711개**, 실패/오류/skip0·종료0 |
| 선택 인사/공개 보안/연간/귀속/업그레이드 | 단위13·통합59개 통과 후 전체 회귀에도 포함 |
| Python 전체 도구 / 공개 배포 계약 | 74개, 실패/오류0 |
| 최종 JAR 정적 자산 | 정적9개 + 라이선스1파일이 최종 소스/dist와 바이트 일치 |
| 변경 범위 | git diff --check 통과, 원본/다른 공개 작업트리 수정 없음 |

Maven은 프로젝트 전용 JDK25.0.5와 이 작업트리의 target을 사용했고 호스트 전역 설정이나 기본 로컬 DB를 바꾸지 않았다. 전체 실행은 `mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`, 프론트는 `npm test` 및 `npm run build`, 도구는 프로젝트 JDK의 `python -B -m unittest discover -s scripts -p 'test_*.py'`다. 빌드의 기존 500kB 초과 chunk 경고는 남아 있으며 실패는 아니다.

Git 제외 근거는 이 작업트리 `target/public-personnel-{red,security-red,green,java-all,python}.log`, `target/public-personnel-frontend-complete.log`, `target/public-personnel-build-complete.log`, `target/public-personnel-assets-proof.json`과 Surefire/Failsafe XML이다. 앞선 실패/선택 실행을 최종 전수 통과 수에 합산하지 않았다.

## 통합 후보 실제 화면

2026-11-02 11:39~11:42(서울)에 전용 fixture-owned `127.0.0.1:18094` 앱과 합성 계정으로 확인했다. 이 앱은 공개 통합 소스의 `default` 프로필이며 실제 공개 프로필 보안은 위 PostgreSQL 통합 테스트와 구분한다.

- 로그인 후 `/me/profile` 진입, 3단계 메뉴·본인 헤더·헤더 아래 업무 탭을 확인했다. 메뉴를 통해 사원 관리로 이동하고 인사정보 등록 링크를 열었다.
- 인사 등록 화면에서 합성 직급을 `선임`→`책임`으로 저장했다. `?saved` 복귀, 헤더와 입력값의 즉시 변경, 같은 적용일의 기존 상태 이력 보존을 확인했다. 실서버나 기존 로컬 기본 앱의 자료는 수정하지 않았다.
- PC 1440px와 모바일 390px에서 단계형 신청 창을 관측했다. 모바일 문서 폭375px·라디오18px이며 가로 넘침이 없었다. 첫 단계에서 닫기와 모바일 업무 메뉴 펼침·하위 메뉴 이동을 확인했다. 미저장 내용이 있는 확인창의 추가 수동 검증은 이 관측에 포함하지 않는다.
- 기존 사원 시트의 실제 행 높이40px, PC 문서 폭1440px와 직접 편집 시트 표시를 확인했다. 이 브라우저 실행에서 셀 값 저장을 다시 수행하지 않았고 기존 편집 회귀는 위322개 자동 검증에 포함된다.
- 등록 화면의 모바일 문서 폭375px/viewport390px, 사원 헤더의 저장된 직급 반영과 브라우저 error/warn 로그0건을 확인했다.

캡처·폭 근거는 Git 제외 `target/personnel-ui/{profile-desktop,request-desktop,request-mobile,sheet-desktop,personnel-saved-desktop}.jpg`, `mobile-proof.json`에 있다. 임시 앱은 합성 전용 DB와 loopback 단일 포트를 사용하고25분 종료로 제한했다. QA 후 PID·생성 시각·실행 인자·포트 소유를 대조해 해당 앱만 종료했고18094 listen 부재를 확인했다(`cleanup-proof.json`).

## 공개 반영 준비와 연결 상태

배포 소스는 위 `fc422d7`로 고정했다. 전체481파일 아카이브 SHA256은 `7107660e57ad30d266c47d1201a2417b2d07cd14c89ab996fb005b34ef35c26e`이며, 기존 배포 소스 `3529a25`의 파일·모드를 검증해 재구성하는105파일 패치도 준비했다. 새 V15/V16을 포함하므로 앱만 교체하는 경로를 사용하지 않는다. 전용 제한 빌더의 runtime/migrate 이미지 확인 → WorkLog 앱만 정지 → 최종 백업 → 독립 제한 DB 복원 및 V14→V16 예행 → 원본 자료/권한 보존 검증 → 본 DB 마이그레이션 → 앱 교체 → 공개 HTTP·기존 서비스 대조 순서다. 배포 보조물은 Git 제외 `target/personnel-deploy-20261102/`에 보관하며 실제 서버 실행 결과와 구분한다.

배포 준비 가드9개(패키지 경로/내용/모드·고정 DB/Compose 대상·신규 RLS/권한·기존 자료/이력 변조 거부·복원 접근 제한)의 로컬 테스트를 통과했다. 공개 기능 검증98개와 네트워크 경계4개 스크립트를 준비하고 PowerShell7/Windows PowerShell5.1 파서·정적 검사를 통과했다. 이102개는 배포 후 실행용이며 현재 공개 서버 통과 수로 계산하지 않는다. 신규 인사 POST의 공개 확인 입력은121자 직급과 음수 버전으로 이중 무쓰기 경계를 두고403과 인사/헤더 불변을 검사한다.

사용자가 연결한 Hostinger 관리 화면에서 올바른 VPS의 Running 상태와 웹콘솔 탭을 확인했다. 새 웹콘솔도 열어 확인했지만 터미널은 검은 화면·커서만 표시했고 Enter와 읽기 전용 `hostname` 입력에 출력이 없었다. 실제 셸 프롬프트와 명령 결과를 확인하지 못해 서버 명령·소스 전송·DB·배포는 실행하지 않았다. 사용자에게 프롬프트가 보이는 연결 상태 확인을 요청했으며 추가 배포 승인이 필요한 상황으로 해석하지 않는다.

11:45(서울)에 Windows의 정상 인증서 검증으로 WorkLog health200/UP·PriceSys401·demo health200·portfolio200을 다시 확인했다(`target/personnel-ui/public-connectivity-before.json`). 처음 Python3.13의 기본 검증은 로컬 인증서 체인 검사에서 실패했으며 인증서 검증을 끄거나 신뢰 설정을 바꾸지 않았다. 외부 HTTP4개 확인은 현재 서버 내부 SHA·스키마·자료/컨테이너 보존 검증을 대신하지 않는다.

## 이 보고서에 포함하지 않은 검증

실제 공개 서버 접속·백업/독립 복원·V14→V16 서버 마이그레이션·이미지 교체·공개 HTTP/PC/390px·다른 서비스 보존은 이 로컬 후보 검증과 별도 수행한다. 새33분 부하·전체 취약점 전수 검사·실고객/법률/운영 수용도 이번 결과에 포함하지 않는다. 배포 시 bootstrap/sample-data를 다시 실행하지 않고 전용 DB 백업·복원·기존 자료/권한 보존을 먼저 확인해야 한다.
