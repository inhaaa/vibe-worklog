# 업무 메뉴·인사정보 공개 통합 후보 검증

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

## 이 보고서에 포함하지 않은 검증

실제 공개 서버 접속·백업/독립 복원·V14→V16 서버 마이그레이션·이미지 교체·공개 HTTP/PC/390px·다른 서비스 보존은 이 로컬 후보 검증과 별도 수행한다. 새33분 부하·전체 취약점 전수 검사·실고객/법률/운영 수용도 이번 결과에 포함하지 않는다. 배포 시 bootstrap/sample-data를 다시 실행하지 않고 전용 DB 백업·복원·기존 자료/권한 보존을 먼저 확인해야 한다.
