# main 인사·신청·결재 통합 검증

2026-11-03, Asia/Seoul. [통합 계획](../superpowers/plans/2026-11-03-main-approval-integration.md)에 따라 작업 브랜치 푸시·공개 배포 뒤 빠진 main 통합을 검증했다. 아래 최초 실패와 최종 선택 재검증을 구분한다.

## 범위와 보존

- main 기준은 `1961bf41f7d455a8fb9e03ef9b15c10b68be7692`, 통합한 기능은 `1e0d119636c12c32791cdf3cc0feb0af9e864cfa`다. reset/rebase/force push 없이 두 이력을 일반 merge한다.
- main의 공통 시트·직접 셀 편집/히스토리·정렬/폭·같은 출처 자산을 보존하고 개발의 연간 신청/귀속·업무 메뉴·공통 인사 헤더/등록·신청/결재 화면과 두 로컬 demo persona를 함께 통합한다.
- 충돌7곳은 README/개발 안내/공개 안내, 조직/신청 화면 테스트·Vite 자산 설정, React resolver다. 문서의 양쪽 이력을 유지하고 프론트는 검증된 공개 UI 구성을 참조했다. resolver는 `/app.css` → `/react/app.css` → `/react/sheet.css`를 모두 공급한다.
- 기존 main의 시트 핵심9파일(WorklogSheet/SheetCellEditor/CSS·build helper·package/lock·핵심 관리 화면)은 내용이 동일하다. 프론트 전체는 고정 공개 참조 `63782fe`와 AuthPages2파일/Workflow 테스트1파일만 다르다. AuthPages는 원래 개발 계약을 유지하며 Workflow에 기존 관리 목록/fragment 보존 검사1건을 추가했다.
- V1~V14의 SQL은 동일하며 기존 개발 V15/V16만 추가됐다. 새로운 스키마/의존성/권한·실제 서비스 DB 변경을 만들지 않았다. 공개 전용 프로필/보호 코드는 main에 임의 추가하지 않았다.
- 진행 도중 별도 인사/사번 로그인 작업 `9d17160`이 작업 브랜치에 추가된 것을 확인했다. 이번 merge는 위 완료 기능 커밋으로 고정하며 동시 작업·그 브랜치/파일을 변경하지 않았다. 해당 V17 후속을 이번 통합 결과에 포함하지 않는다.

## 실제 검증

| 검증 | 결과 |
| --- | --- |
| 기존 main 기준 화면 전체 | 16파일251개 통과 |
| CSS 결합 회귀 | app CSS 링크만 임시 제거해 새 포함 검사1건 RED 확인, 원래 resolver 바이트 정확 복구 후 ReactPageViewTest11개 GREEN |
| 통합 타입/production 빌드 | 성공, app.css/sheet.css 두 자산 생성 |
| 통합 화면 전체 | 22파일346개 통과, Vitest worker1 직접 호출 |
| Java 전체 최초 실행 | 단위132 통과, PostgreSQL566개 중565 통과/동시성1 실패·오류/skip0 |
| 동시성 후속 | 최초 선택2개 중1실패, 진단2개 통과; 원본 바이트/5초 조건을 복구한 최종2개 통과·Maven BUILD SUCCESS(14:23:23 KST) |
| Python 도구 전체 | 67개 통과, 프로젝트 JDK25를 자식 실행에만 지정 |
| 독립 검토 | 고정 main/개발/공개 참조 대조에서 미해결 P1/P2 없음, 충돌 index0 |

기준 화면 실행에서 npm wrapper가 worker 인수를 소비한 경고가 있었고 Vite 설정의 기본2 workers로251개가 통과했다. 통합 화면은 Vitest를 직접 호출해 worker1을 명시했다. 기존 큰 chunk 경고는 유지하며 테스트 기대값·시간 한도·보안 설정을 완화하지 않았다. Java와 Python은 전용 JDK25.0.5를 사용하고 호스트 전역 JAVA_HOME/PATH/신뢰는 변경하지 않았다. Python 첫 시도는 로그 경로 생성 전에 출력 경로 오류가 나 테스트가 시작되지 않았고 경로 준비 후 실제67개를 실행했다.

월간 동시성 검사는 두 번째 조회 준비를 기다리는5초 assertion에서 전체/최초 선택 실행이 실패했다. 관련 서비스/매퍼/트랜잭션/테스트는 두 통합 기준과 동일하며 실패 지점에서는 테스트 writer를 아직 시작하지 않았다. 임시 진단 실행은 실제 같은 assertion/잠금 검증2개를 통과했고 월간 읽기7,438ms/4,760ms를 관측했다. 월간은 앞뒤 주를 포함한42일을 조회한다. 호스트 지연과5초 경계의 영향 가능성은 있으나 최초 실패의 구체적인 DB 대기/비동기 예외 원인까지 확정하지 않았다. 진단 코드는 원본 바이트로 복구했고 제품·테스트 조건/5초 한도를 바꾸지 않았다. 최초 실패를 숨기거나 전체 Maven 실행을 일괄 성공으로 표현하지 않는다. 현재 생산 코드/원본 검사 조건에서 최종2개는 통과했으며 대기 경계의 재발 가능성과 최초 실패 원인 미확정은 남는다.

증거는 새 main 통합 작업트리 ignored `target/main-integration-{frontend,build,java,python}.log`, `target/main-integration-concurrency-{recheck,final}.log`, `target/main-integration-concurrency-diagnostic/diagnostic.log`와 최초 실패 XML/요약 보존 디렉터리, `target/main-integration-{source-manifest,preservation-proof}.json`, `target/main-approval-css-regression/{red.log,green.log,restoration-proof.json}`다. 최종 상태·원격 main SHA는 완료 증거에 기록한다. 루트 작업트리의 미추적 기존 사용자 문서는 보존한다.

최종 자료 확인에서 검증 중 소스346파일 무변경, 생성한 프론트9자산과 JAR 내용 동일·기존16개 마이그레이션 포함, 문서21개/로컬 링크226개 유효를 확인했다. 로그에서 식별한 전용 시험 컨테이너6개는 모두 실행 중이 아니었다. 검증 JAR SHA는 `996688f77b36dc9eb1ee76512a6ab41de0a3e538dffddf6c8712ca48921503c3`다. 새 작업트리의 깨끗한 빌드에는 과거 공개 산출물의 미사용 vendor 파일이 없으므로 기존 공개 자산 수와 혼동하지 않는다.

## 공개 데모와 완료 기준

이번 작업은 main 소스 이력 통합이다. 공개 전용 브랜치/설정·서버·DB·데모 자료를 변경하지 않는다. 앞서 실제 배포한 `3e6e99c`·V16의 신청→독립 승인/12→11DAY·공개 보안 결과는 [기존 공개 결과](public-approval-workspace-deploy-20261103.md)를 따른다. main 병합을 새 배포/새 공개 HTTP 검증으로 표현하지 않는다.

검증 후 일반 merge 커밋을 로컬 main에 fast-forward하고 원격 main에 일반 push하는 순서로 반영한다. 원격의 실제 SHA/동일성은 최종 완료 증거와 완료 보고에 기록한다. 앞으로 작업 브랜치 푸시·main 통합·공개 배포의 실제 SHA와 검증 범위를 각각 기록한다. main 통합본에서 별도 수동 브라우저·새 공개 HTTP·장시간 부하/전체 취약점 스캔·실제 고객/메일은 수행하지 않았다. 프론트 생산 코드는 위 고정 공개 참조와 로컬 데모 안내 차이를 구분해 비교했다.
