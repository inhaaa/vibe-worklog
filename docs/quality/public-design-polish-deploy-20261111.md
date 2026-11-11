# 추가 6개 커밋 공개 배포 결과

2026-11-11 13:55:36 서울 시각의 서버 final 기록에서 `8a731a7ec124bb0f3e2035e0199aa47ceaba9f58` 배포를 확인했다. 이후 공개 HTTP와 PC 홈·인사 이력·필터·신청 모달 동작, 실제 브라우저·관리 콘솔 로그아웃과 마지막 서버 재검사, 임시 도구 종료까지 확인했다.

## 배포 대상과 보존 범위

- 일반 제품 통합: `ac2db65cfbf66061d9eeba21c65a9300694308b3`. 원본 `a65111c..512d5ac`의 완료 커밋 6개를 반영했다.
- 공개 실행 소스: `8a731a7ec124bb0f3e2035e0199aa47ceaba9f58`. 기존 공개 보안과 시간 정밀도·대상 사원 표시·저장 중 사유 고정을 보존했다. 후속 문서 커밋은 실행 소스와 구분한다.
- 이미지: `sha256:b470aa7c4495e63bc3ab2e9dcd6d130d0337d890f721e49181b2a10eecd711cb`.
- 서버 JAR SHA-256: `43d95688d9c7b82f2f6ea0e2456a6e9215ac1c702690abfda24e7edf58f5e5de`. 이미지 ID와 JAR 전체 해시의 서버 비교를 모두 통과했다.
- 변경은 JAR 639개 항목 중 React `app.js`·`app.css`·`sheet.css` 3개이며 나머지 636개는 동일하다. 폰트 92개, Java·DB·설정·의존성·공개 인증 및 권한 경계를 보존했다.
- 범위는 `/srv/worklog`·Compose `worklog-demo`·앱 `127.0.0.1:18081`이다. 기존 52개 컨테이너, Caddy와 OS/JRE 12개 이미지 층을 보존했다. DB는 V17·48개 표이며 스키마·권한과 앱 정지 전후 자료가 정확히 일치했다. 새 마이그레이션이나 초기 자료 재생성은 없었다.

## 확인한 결과

| 구분 | 이번 실행 결과 |
| --- | --- |
| 서버 배포 | before·build·artifact·app·final 5단계 PASS. final: `2026-11-11T04:55:36.201856+00:00` |
| 기존 공개 서비스 | WorkLog·demo·portfolio HTTP 200, PriceSys HTTP 401 유지 |
| 프런트엔드 | 일반 42파일·512개, 공개 42파일·522개 통과. 양쪽 TypeScript·Vite 빌드 통과 |
| Java 단위 | 165개, 실패·오류·건너뜀 0 |
| 배포 가드 | 17개 통과. 정확한 3개 UI 자산 변경과 나머지 JAR 항목 보존 확인 |
| 배포 후 공개 GET | 새 실행 소스 기준 109/109 통과, exit 0 |
| 인증·보안 HTTP | 34개 통과, 로그인 POST 3회·업무 변경 POST 0회, 로그아웃 확인, exit 0 |
| 모달 조회 HTTP | 19개 통과·1개 건너뜀, 로그인 POST 1회·업무 변경 POST 0회, 로그아웃 확인, exit 0 |

실제 PC 브라우저의 1280×720 화면에서 블루·차콜 홈과 새 평면 패널을 확인했다. 홈에는 근무 09~18·연차 11·대기 0이 표시됐고 가로 넘침은 없었다. 차콜 신청 모달의 X·Escape 닫기와 블루 신청 모달의 X 닫기 후 `/me/requests/new` 호출 버튼으로 초점이 복원됐다. 두 테마의 로컬 화면 파일을 저장했다.

블루 사원 목록의 현재 페이지 필터에 담당자를 입력해 5명에서 1명으로 좁혀지고 원복되는 것을 확인했다. 인사 정보의 2026-11-02 재직 이력과 합성 사유가 표시됐고 이력 패널의 그림자는 없었으며 하단 닫기로 복귀했다. 신청 목록에는 승인된 1일 연차의 대상 담당자와 가상 승인자가 표시됐다. 일치하지 않는 검색어로 결과가 0개가 된 뒤 지우면 1개로 복원됐다. 브라우저 오류는 `logs=[]`, UI 업무 변경 POST는 0회였다.

테마를 블루로 복원하고 가상 계정의 로그아웃 안내와 관리 콘솔의 로그인 프롬프트 복귀를 확인했다. 화면 확인 후 서버 읽기 전용 재검사에서 `FINAL_READONLY_PASS`로 이미지·보존 조건·사이트 HTTP 200/401을 다시 확인했다. 로컬 전송·화면 확인 도구를 종료한 뒤 `HELPER_LISTENERS_ABSENT_PASS`와 `HELPER_PROCESSES_ABSENT_PASS`를 확인했다.

## 검증 한계

모바일 화면은 이번에 재실행하지 않았다. 계산 근거 모달은 접근 가능한 사원의 현재·직전 월에 저장된 계산이 없어 1개를 건너뛰었으며 미검증이다. 이전 공개본에서 실행한 PostgreSQL 통합 634개는 이번에 재실행하지 않았고 위 테스트 수에 포함하지 않는다. 새 장시간 부하·전체 취약점 검사·실고객 운영 검증도 실행하지 않았다. 기존 500KB 초과 번들 경고는 유지한다.

## 증거

Git 제외 로컬 `target/public-polish-ops-20261111/commands.md`·`preparation-proof.json`은 배포 준비 증거이며 준비 시점의 미실행 표기를 보존한다. 서버 단계 증거는 `/srv/worklog/.local/public-polish-20261111`에 있다. 배포 후 결과는 로컬 `target/public-polish-http-20261111/public-get-candidate.json`·`public-get-candidate.log`, `authenticated/public-authenticated.log`·`authenticated/public-modals.log`에 보존했다. 두 인증 검사 원문 로그는 실제 파일 존재와 내용도 확인했다. 화면은 같은 폴더의 `published-attendance-blue.jpg`·`published-attendance-charcoal.jpg`에 있다.

[통합 검증](design-polish-integration-20261111.md) · [공개 데모 후속 배포](../development/worklog-public-updates.md)
