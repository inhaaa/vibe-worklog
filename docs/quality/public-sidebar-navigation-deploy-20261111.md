# 사이드바 탐색 개선 통합·공개 배포 결과

2026-11-11 14:55:19 서울 시각의 서버 final 기록에서 `0e40eda87b9c3747d249a46121e70481baa3bf24` 배포를 확인했다. 이후 공개 HTTP와 실제 PC의 메뉴·권한별 표시·키보드·모달 동작, 실제 브라우저·관리 콘솔 로그아웃과 마지막 서버 재검사, 임시 도구 종료까지 확인했다.

## 통합과 배포 범위

- 일반 제품: `fc4972383fe63ce4bdb85c516aa0bfc2b7f774ba`. 이미 원격 main에 반영된 기능 3개(`591c800`·`1bb982c`·`29aa049`)와 기존 main 통합 merge를 확인하고 로컬 main을 fast-forward했다.
- 메뉴 계층 표시·모두 펼치기/접기·데스크톱 숨김/복구·아이콘을 반영했다. `3f90437..fc49723`은 프런트 5파일·67줄 추가/5줄 삭제다. 사용자 미추적 파일과 기존 시간 정밀도·대상 사원·저장 사유 고정을 보존했다.
- 공개 실행 소스: `0e40eda87b9c3747d249a46121e70481baa3bf24`. 기존 공개 인증·계정 보호·CSRF·권한 경계를 보존했다. 후속 문서 커밋은 이 실행 소스와 구분한다.
- 이미지: `sha256:6ad20c30a9a8453a84459239671696728b5f7f96c6c3b3e46e6c6d9095949f63`.
- 서버 JAR SHA-256: `8274339ca66131bc71dd75ac27b6266240385edbf6a0a94638404c7978584378`. 이미지 ID와 JAR 전체 해시의 서버 비교를 모두 통과했다.
- JAR 639개 중 React `app.js`·`app.css` 2개만 변경했고 637개·폰트 92개는 동일하다. Java·DB·설정·의존성과 OS/JRE 12개 층을 보존했다.
- 배포는 `/srv/worklog`·Compose `worklog-demo`·앱 `127.0.0.1:18081` 범위다. 기존 53개 컨테이너·Caddy, DB V17·48개 표의 스키마·권한·앱 정지 전후 자료가 보존됐다.

## 실행한 검증

| 구분 | 이번 결과 |
| --- | --- |
| 서버 배포 | before·build·artifact·app·final 5단계 PASS. final `2026-11-11T05:55:19.197277+00:00` |
| 기존 공개 서비스 | WorkLog·demo·portfolio HTTP 200, PriceSys HTTP 401 유지 |
| 프런트엔드 | 일반 42파일·514개(168.34초), 공개 42파일·524개(169.72초), 양쪽 TypeScript·Vite 빌드 통과 |
| Java·패키징 | 공개 Java 단위 165개(20.137초), JAR package(7.250초) 성공 |
| 배포 가드 | 17개 통과. 기준 상수 5개 외 ops AST와 기존 보존·롤백 가드 동일 |
| 배포 후 GET | 후보 `0e40eda` 기준 109/109 통과, exit 0 |
| 인증·보안 HTTP | 34개 통과, 로그인 3회·업무 POST 0회·로그아웃 확인, exit 0 |
| 모달 조회 HTTP | 19개 통과·계산 근거 1개 건너뜀, 로그인 1회·업무 POST 0회·로그아웃 확인, exit 0 |

## 실제 PC 관측

1280×720 블루 화면에서 모두 펼치면 상·중위 묶음 버튼 15개가 열리고 업무 링크 24개가 보였다. 모두 접으면 열린 묶음·보이는 업무 링크가 각각 0개가 됐고, 내 정보로 이동하면 현재 조상 2개가 자동으로 열렸다. 직원 권한 보기에서는 `/me` 링크 5개만 표시됐으며 담당자 보기로 복원했다.

사이드바를 숨기면 `display:none`·`aria-expanded=false`가 되고 본문 왼쪽 위치가 232→0px로 이동했으며 가로 넘침은 없었다. Tab 이동에서 숨긴 사이드바가 제외되고 상단 브랜드 링크가 나타났다. 다시 열면 `display:flex`·왼쪽 232px로 복원됐다. 차콜의 사이드바 숨김 상태에서 신청 모달 X·Escape 닫기 모두 `/me/requests/new` 호출 버튼으로 초점이 복귀했다. 브라우저 오류는 `logs=[]`, UI 업무 POST는 0회였다.

확인 후 블루·담당자 보기·사이드바 표시·모두 접기 상태로 복원했다. 두 테마 화면 파일을 저장했다. 모바일 390×844를 요청했지만 실제 viewport가 1280×720로 유지돼 모바일 동작은 미검증이며 viewport 요청을 초기화했다.

## 종료 상태와 한계

가상 계정의 로그아웃 안내와 관리 콘솔의 로그인 프롬프트 복귀를 확인했다. UI 확인 후 `FINAL_READONLY_PASS`로 이미지·기존 컨테이너·사이트 HTTP 200/401을 재검사했다. 로컬 전송/화면 확인 도구 종료 뒤 `HELPER_LISTENERS_ABSENT_PASS`·`HELPER_PROCESSES_ABSENT_PASS`로 포트 18096/18097과 해당 프로세스 부재를 확인했다.

계산 근거 모달은 접근 가능한 사원의 현재·직전 월에 저장된 계산이 없어 1개를 건너뛰었다. PostgreSQL 통합 634개는 이번에 재실행하지 않았고 위 테스트 수에 합산하지 않는다. 새 장시간 부하·전체 취약점 검사·실고객 운영 검증은 실행하지 않았다. 기존 500KB 초과 번들 경고는 유지한다.

## 증거

Git 제외 `target/public-nav-ops-20261111/commands.md`·`preparation-proof.json`은 준비 기록이며 `observed-deployment.json`은 서버 콘솔·브라우저·종료 관측 요약이다. 서버 원문 결과는 `/srv/worklog/.local/public-nav-20261111`에 있다. 로컬 HTTP 폴더 `target/public-nav-http-20261111`의 `public-get-candidate.json`, `authenticated/public-authenticated.log`(1,513바이트)·`authenticated/public-modals.log`(1,011바이트)에서 이번 결과를 확인했다. 같은 폴더의 `published-attendance-blue.jpg`·`published-attendance-charcoal.jpg`는 각각 블루 메뉴 펼침·차콜 메뉴 숨김 화면이다.

[공개 데모 후속 배포](../development/worklog-public-updates.md)
