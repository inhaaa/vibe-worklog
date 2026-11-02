# 시트 정렬·한 줄 행 공개 배포 결과

기준일: 2026-11-02, Asia/Seoul. 사용자가 연결한 정상 Hostinger 웹콘솔에서 가상 자료 공개 데모 [WorkLog](https://worklog.inhaaa.com)에 반영했다. 구현·로컬/공개 프론트 검증은 [시트 정렬 보고서](sheet-alignment.md)에 보존한다.

## 실제 반영

- 공개 소스 `3529a25bfcc8934433d3ef20362d8dd347921db1`, 제품 구현 `47fb527`. 앱 시작09:37:36 KST, 최종 서버 보존 확인09:41:43 KST.
- 앱 이미지 `sha256:a16cee35c41723cae6958ed749ec24b999d5a780f3ca1cbbc7f728c1490bf6b8`, JAR SHA256 `f248e20277f85beb19db0638d249d3f3549f9822f4819469b464b26d63a24204`.
- 이전 `12f0f78`을 기준으로 만든437파일 후보를 전체 SHA/파일 집합/모드로 검증했다. 변경24개는 프론트8개·문서14개·Docker context에서 제외되는 기존 검증도구2개이며 삭제0이다. 공개 로그인 보호·Java·DB/Flyway·의존성/lock·실행 설정은 그대로다.
- 서버 전용 빌더의1.5GiB·CPU0.75 제한을 확인해 app `runtime`만 빌드하고 빌더를 중지했다. 새 앱 이미지에서 JAR을 제외한12개 실행 레이어는 이전과 같고, React 외 백엔드·의존성·기존 정적자산367개 entry도 바이트가 같다. React8개는 검증된 공개 dist와 서버 JAR·실제 HTTPS 배포본의 SHA가 모두 같다.
- `/srv/worklog`·Compose `worklog-demo`·DB `worklog_demo` 범위를 지켰다. 기존 `.env`의 이미지 태그만 변경하고 나머지 바이트·0600을 보존했다. `up --no-deps --no-build --pull never app`으로 앱 하나만 교체했다. 이전 이미지는 복귀용으로 보존했다.

## 공개 검증과 기존 서비스 보존

- 공개 HTTPS/보안/메뉴72항목을 배포 전후 각각 통과했다. 정상 CSRF 로그인/로그아웃, Secure/HttpOnly/SameSite 쿠키, 공유 계정 단건·일괄 변경 차단, 미인증 업무·운영자/진단 차단을 포함한다.
- 배포 전후 프록시/포트4항목도 통과했다. HTTP→HTTPS 경로/query 보존, 위조 forwarded scheme/host 차단·HSTS와 외부18081 차단이다.
- 공개 PC1280px·모바일390px에서 사원/공통코드2화면씩4회 확인했다. 헤더는 각각8개/16개 모두 중앙, 행은 모두40px, 본문은 한 줄이다. 공통코드 숫자4개는 우측이고 짧은 값 중앙·4어절/문장 좌측·긴 내용 말줄임을 확인했다. 페이지 가로 넘침0, 모바일 고정 열은 선택 열뿐이다.
- 사원 이름을3→4어절로 **미저장 편집**하면 좌측 정렬·변경1행이 되고 Esc로 원래 이름·변경0행이 복원됐다. F2→Tab으로 입사일 편집 이동·Esc 복원과 재조회 원값을 확인했다. 상세 링크12개가 유지됐고 실제 업무 저장은 실행하지 않았다. 브라우저 경고/오류0, viewport 원복·최종 변경0행이다.
- 읽기 전용 새 DB 기준선·교체 직전·직후·전체 HTTP/UI 종료 후를 대조했다. 업무43테이블407행의 모든 열 해시, 스키마·Flyway V14 전체 이력, RLS/ACL·함수·제약·인덱스·트리거·역할 경계가 exact 일치했다. migration·seed·bootstrap·새 백업/복원은 실행하지 않았다.
- 당시 기존17개 컨테이너 중 교체 앱과 작업용 WorkLog 빌더를 제외한15개의 ID·시작/종료·이미지·마운트·포트·실행/OOM/restart 상태를 보존했다. 후보 JAR 추출용 비실행 컨테이너1개를 추가해 최종18개이며 실행 서비스는 추가하지 않았다.
- PriceSys401(기존 인증 요구), demo health200, portfolio200, WorkLog health200/UP를 전후 확인했다. Caddy SHA256 `da4f8a24e779169fe8522eba7d99883612149422fad6acc8e9232aa840b0e059`는 동일하다. DNS·Caddy·다른 프로젝트·전역 Docker·방화벽·SSH/인증 설정은 변경하지 않았다.
- 앱은 non-root10001·read-only·cap-drop ALL·no-new-privileges·768MiB/CPU1·PID256·전용 네트워크·loopback18081을 유지했다. DB는 계속 비공개이며 앱에 주입된 DB 비밀번호 역할은 app/auth뿐이다. 임시 관리 접속 서비스/키/포트는 만들지 않았다. 최종 디스크 여유15.78GiB다.

## 증거와 한계

원시는 공개 worktree의 Git 제외 `target/sheet-alignment-deploy-20261102/`, 서버 제한 경로 `/srv/worklog/.local/sheet-alignment-20261102`에 남겼다. 비밀값·쿠키/CSRF·원문 업무 행은 문서/Git에 넣지 않았다.

| 증거 | SHA256 |
| --- | --- |
| 최종 안전 보고서 | `2b8a836289cbce5ffa99cc162d82246175831642bf8d6a84ddb10e63cf8aa546` |
| 실제 브라우저 관측 | `bd69cb7c2ec16cb8e24bd78b0ef47f0993541c6a216139a5a4fef90418c98bf3` |
| 공개72항목 | `9fba24caa8a1197339b897233b6385c37cfaaf70deabdfdf36e107c7d4cfc958` |
| 프록시/포트4항목 | `14e7ba8371fffe9385ad60c4aabd504a6174a0156d090a3fe7fb9e72d528aa0c` |
| 실제 정적8자산 | `57a2bf379368b2c24b6e37904908f920f3e5dd8a86f3b30deaa08edd5cca73cb` |

초기 자산 검증 도구가 JAR의 `/static/react/`를 `/static/`로 잘못 비교해 배포 전 차단됐다. 경로를 바로잡아8개 자산과 나머지367개 entry 동일을 재검증했으며 제품 변경이나 재빌드는 필요하지 않았다. Windows Python TLS 검사 실패는 인증 검증을 끄지 않고 기존 Windows/.NET 검증 경로로8개 모두 확인했다.

이번 단위는 UI 표시 변경의 공개 배포다. 일반251개·공개252개/타입·빌드는 앞선 동일 소스의 실제 결과를 사용했으며 Java 전체 회귀·새33분 부하·새 전체 취약점 검사를 재실행한 것은 아니다. 이전 공급 대기 보안 위험과 Vite chunk 경고, 필터 입력 때 수동 열 너비 유지의 후속 과제는 남는다. 실제 고객·실제 개인정보·메일·업무 저장/승인 수용은 이번 검증 범위가 아니다.
