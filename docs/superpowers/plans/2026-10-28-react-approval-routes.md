# React 1단계 결재선 관리 구현 계획

> **For agentic workers:** `superpowers:subagent-driven-development`로 서버 설정·신청 해석·React를 파일별로 나누고, Maven·기본 DB 반영·통합 검증·커밋·일반 Push는 root가 직렬 실행한다.

**목표:** [승인된 확장 설계 6절](../specs/2026-10-26-worklog-service-design.md#6-결재선과-결재)에 따라 사원·조직별 1단계 결재선을 지정하고 조직장 결재를 연결하며 기존 신청·승인·원장 이력을 보존한다.

**구조:** 기존 OrgCommands/BusinessCommands·tenant 잠금·명령 멱등성·감사를 재사용한다. 결재선 정의/불변 버전, 적용일 배정, 제출 당시 스냅샷을 분리한다. React 기존 표·폼·오류 보존·접근성 패턴을 재사용하고 새 라이브러리를 추가하지 않는다.

**기술:** Java25·Spring MVC·MyBatis·Flyway/PostgreSQL·React/TypeScript·Vitest. 기준 `1f9d943`. 사용자 승인 범위는 구현·필요한 로컬 DB·테스트·문서·커밋·일반 Push다.

## 선행 스키마·업무 계약

- V1~V12 보존, V13에 `approval_route`, `approval_route_version`, `approval_route_assignment`, `approval_route_snapshot`을 추가한다. tenant 복합 PK/FK·FORCE RLS·최소 열 권한을 적용한다. 정의는 code/name/active/version, 버전은 route_id/revision/name/approver_kind/employee_id와 생성자·사유·시각을 갖고 수정하지 않는다.
- 결재선 내용 변경은 새 revision이다. code는 저장 후 불변이며 비활성은 신규 신청만 막는다. 이미 배정된 버전을 정의 수정 시 자동 교체하지 않는다. 새 버전을 사용하려면 명시 재배정한다.
- 배정은 route_version_id, scope_kind=EMPLOYEE|ORGANIZATION, 대상 XOR, `[valid_from,valid_to)`, superseded_at, reason/version/생성·변경 메타데이터다. 대상별 구간 중첩 금지. 적용일은 서울 오늘 이후, 같은 시작일 교체는 superseded, 과거 구간은 종료일로 닫고 이후 예약을 보존한다. 해제도 이력 종료다.
- 새 신청의 선택 순서는 명시 사원 배정 → 기존 사원별 지정 승인자 → 오늘 현재 소속 조직 배정이다. 상위 존재 시 비활성·공석·퇴사·APPROVER 회수·자기/작성자 결재를 하위로 우회하지 않고422로 차단한다. 조직장 해석은 신청 대상자의 오늘 소속 조직에 한정하며 상위 조직을 탐색하지 않는다.
- 제출 시 실제 결재자와 적용 배정/버전·현재 소속 배정/조직·조직장 배정/버전을 snapshot으로 고정한다. 신청 ID와 snapshot ID를 미리 생성한다. 신청→snapshot의 `(tenant_id,route_snapshot_id,id)` FK만 DEFERRABLE INITIALLY DEFERRED, snapshot→신청은 일반 FK다. 다른 신청의 snapshot 재사용 불가. 기존 approver_assignment_id/version은 모두 존재하거나 모두NULL이며 새 snapshot과 정확히 하나만 허용한다.
- 기존 신청 행은 일괄 보정하지 않는다. 대기 건은 결재선/조직장/소속 변경에도 당시 실제 결재자를 유지한다. 기존 명시 재할당은 current_approver_id와 신청 version/이력만 변경하고 최초 snapshot/legacy 참조는 보존한다. 결정 시 현재 계정·역할·재직·독립성과 신청 version을 재검증한다.
- 본인 프로필은 실제 선택 결재선과 결재자/차단 사유를 보여준다. 신청 상세는 최초 경로와 실제 현재 담당·재할당 이력을 구분한다. `내가 처리한 결재`는 본인 APPROVED/REJECTED 결정 이력에 근거하며 철회는 포함하지 않는다.

## HTTP·화면 계약

| 경로 | 계약 |
| --- | --- |
| GET `/admin/approval-routes?q&page` | 관리자 전용 정의 시트50행과 선택 버전 이력 |
| POST `/admin/approval-routes/bulk` | `commandId`, `reason`, `rows` JSON 변경1~50행 원자 저장. CREATE/UPDATE/DEACTIVATE, 고정 code·expectedVersion·clientRowId 검증 |
| GET `/admin/approval-route-assignments?scopeKind&employeeId&organizationId` | 관리자 전용 대상 선택·불변 버전 선택·적용일 배정/해제와 이력 |
| POST `/admin/approval-route-assignments`, `/admin/approval-route-assignments/end` | scopeKind/employeeId?/organizationId?/routeVersionId?/validFrom/expectedVersion/reason/commandId. 대상 employee 또는 organization의 aggregate version 사용 |
| GET `/approvals`, `/approvals/completed` | 현재 지정 대기함·본인이 처리한 결재 이력 |
| 기존 신청·결정·재할당 POST | 현재 CSRF·원장·동시성·명령 재전송 유지 |

정의 시트에 조회/입력/선택 삭제/저장/취소를 제공한다. 삭제는 비활성 예정이며 409/422에 편집·삭제 예정·commandId/행 ID·오류를 보존한다. 배정 화면은 조회·새 배정·저장·해제·취소와 변경 사유·구간 이력을 제공한다. 미저장 이동 확인·전송 잠금은 공통 App 계약을 따른다.

## 실행 단위와 검증

### 1. 기준·설정 서버 (담당 approval_admin)

- [x] Java548·React136 기준 회귀를 기록한다.
- [x] 신규 관리 API 부재·기간/배치/최소권한에 대한 실패 테스트를 먼저 실행한다.
- [x] V13·정의 시트 배치·불변 버전·배정/해제 서비스를 구현한다.
- [x] 50/51행·전체 롤백·409·멱등성·재전송 권한회수·tenant·중첩/미래 경계·RLS/최소권한을 검증한다.

### 2. 제출 해석·기존 이력 (담당 approval_workflow)

- [x] 사원/legacy/조직 우선순위와 신규 snapshot 부재의 RED를 기록한다.
- [x] 신규 제출3종에 같은 resolver를 적용하고 request/snapshot을 원자 저장한다.
- [x] 공석·비활성·권한/재직 회수·자기/대리작성자·대표 독립성·다른 tenant를 검증한다.
- [x] 대기 자동이동 없음·명시 재할당·기존 승인/원장·FK XOR/다른신청 snapshot 차단과 완료 이력 조회를 검증한다.

### 3. React (담당 approval_frontend)

- [x] 정의 시트·배정 폼·이력·메뉴·완료 결재·프로필/상세의 실패 테스트를 먼저 실행한다.
- [x] 기존 흰색·파란 테마와 표/폼 재사용, 레이블·키보드·오류·취소·이동 보존을 구현한다.
- [x] 409/422 보존·비활성 의미·고정 코드·모바일 내부 스크롤과 역할 메뉴를 검증한다.

### 4. 통합·로컬·문서 (root)

- [x] 보안 matcher·React allowlist·감사 안전 투영과 migration 기대 목록을 갱신한다.
- [x] 명세 및 코드 품질 독립 리뷰 후 지적을 해결하고 재검토한다.
- [x] 전체 Java·React·타입/Vite 빌드, 기존 신청/승인/정정/취소·tenant·동시성 회귀를 실행한다.
- [x] 기본 DB V12의 전체 기존 업무35개 행·권한/RLS·container/volume 기록과 백업 후 별도 migrate로 V13 적용, 기존 자료 불변을 대조한다.
- [x] 새 app/migrate 빌드·로컬 health·분리 합성 HTTP 업무 흐름·PC/390px 실제 화면을 확인한다.
- [x] README·PRD·상세 설계·개발 안내·품질 보고서에 실제 결과와 미실시를 기록한다.
- [x] git diff/status·신규 파일·문서 링크·비밀/target 제외를 검증한다. 사용자 요청한 커밋·main fast-forward·일반 Push와 원격 SHA/깨끗한 상태의 마지막 확인은 완료 보고에 기록한다.

Maven은 기존 전용 JDK25 자식 환경에서 `./mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`를 직렬 실행한다. 프론트는 `npm --prefix frontend test`, `npm --prefix frontend run build`다. 근거·덤프·스크린샷은 Git 제외 target에만 둔다. 실제 메일·운영 배포·새33분 부하·취약점 전수 검사는 이번 기능 검증과 구분한다.
