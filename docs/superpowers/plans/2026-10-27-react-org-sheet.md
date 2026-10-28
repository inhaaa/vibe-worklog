# React 조직·사원 시트와 조직장 구현 계획

> **For agentic workers:** `superpowers:subagent-driven-development`로 담당 파일을 나누고, 통합 검증과 로컬 반영·커밋·Push는 root가 직렬 실행한다.

**목표:** 승인된 [확장 설계 2단계](../specs/2026-10-26-worklog-service-design.md)의 조직·사원 원자적 일괄 편집, 조직장 적용일 이력, 관리자·대표용 조직도를 React에 연결한다.

**구조:** 기존 모듈형 모놀리스·MyBatis·OrgCommands·CompanyReadAccess를 사용한다. 단건 관리·소속 이동·계정 초대와 기존 신청의 결재자는 보존한다. 새 라이브러리·계산 정책·실제 메일·외부 배포는 추가하지 않는다.

**기술:** 기존 Java25·Spring MVC·PostgreSQL/Flyway·React/TypeScript·Vitest. 기준 커밋 `717e36f`, 사용자 요청은 필요한 로컬 DB 변경·테스트·문서·커밋·일반 Push까지 포함한다.

## 1. 스키마와 인터페이스

- V1~V11은 보존한다. V12에 `organization_head_assignment`를 추가한다. tenant/id 복합 PK와 조직·사원·처리자 복합 FK, `[valid_from,valid_to)`, superseded_at, 사유·version·생성/변경 메타데이터를 둔다. superseded되지 않은 같은 조직의 기간 중첩은 exclusion으로 차단한다. FORCE RLS·명시 tenant SQL·최소 SELECT/INSERT/허용 열 UPDATE만 적용하며 DELETE를 허용하지 않는다.
- 조직장 변경은 조직의 version을 aggregate 예상 버전으로 사용한다. 적용일은 서울 오늘 이후이며 같은 회사의 활성·현재 재직 사원이어야 하고 적용일도 재직 기간 안이어야 한다. 해당 조직의 소속이나 로그인 계정·APPROVER를 요구하거나 자동 부여하지 않는다.
- 적용일 d에 걸친 구간만 닫고, 같은 시작일 교체는 이전 행을 superseded 처리한다. 새 구간은 기존 종료일 또는 다음 미래 구간의 시작일까지이며 이후 예약은 보존한다. 종료도 d에 걸친 배정만 종료한다. 과거 일자 변경·원본 삭제·기존 신청 결재자 이동은 하지 않는다.
- 조직장 재직 적격성과 결재 자격은 별도 표시한다. 미배정·퇴사/비활성·재지정 필요를 표시하고 다른 사람을 추정하지 않는다.

| HTTP | 계약 |
| --- | --- |
| GET `/admin/organizations`, `/admin/employees` | 기존 검색·50행·필터와 단건 API 보존, 시트 모델 추가 |
| POST `/admin/organizations/bulk`, `/admin/employees/bulk` | form `commandId`, `reason`, `rows` JSON array, 조회 문맥. 변경분1~50행만 원자 처리 |
| GET/POST `/admin/organization-heads` | 조직 선택·배정/교체. form organizationId/employeeId/validFrom/expectedVersion/reason/commandId |
| POST `/admin/organization-heads/end` | 동일 계약에서 employeeId 없이 종료 |
| GET `/company/organizations?organizationId&q&page` | 관리자·대표 전사 조직 트리와 선택 조직의 오늘 소속 직원50명. q는 직원 검색 |

행 DTO는 `clientRowId`, `id?`, `expectedVersion?`, `operation=CREATE|UPDATE|DEACTIVATE`와 해당 업무 필드만 허용한다. 조직은 code/name/parentId/active, 사원은 employeeNo/displayName/joinedOn/leftOn/active다. 저장 후 코드·사번은 불변이다. 신규 조직의 부모는 저장된 조직 ID를 선택한다. JSON 중복 키·알 수 없는 필드·중복 행/ID·51행을 거부한다. 별도 시트 업무를 섞지 않는다.

`organizationSheet`/`employeeSheet` 모델은 commandId/reason/rows/latestRows/errors를 제공한다. rows는 최신 페이지에 제출 편집을 겹친 전체 표시 행이며 페이지에서 사라진 편집 행도 유지한다. latestRows는 현재 DB 원본이고 errors는 clientRowId/field/message다. 성공303 재조회, 409/422는 편집·삭제 예정·명령/행 ID를 유지한다. 최신값 비교·명시 재확인 후 새 명령, 결과가 불명확한 같은 내용 재시도는 원래 명령을 사용한다.

목록의 `organizationHeadsById`는 조직별 현재 조직장 이름·재직/결재 적격성을, `employeeContext`는 페이지 사원별 현재 조직·조직장·적용 근무를 읽기 전용으로 제공한다. 소속/근무는 기존 적용일 배정 화면으로 연결하고 시트 저장으로 이력을 덮어쓰지 않는다. `clientRowId`는 UUID 문자열로 제한해 감사 결과 매핑에 자유 문구가 들어가지 않게 한다.

조직장 page/model은 `org/organization-heads`/`organizationHeads`, 조직도는 `company/organizations`/`companyOrganizations`다. 본인 프로필은 현재 조직장 이름·재직 적격성·결재 자격을 추가한다. 회사 읽기에는 이메일·자유 사유·계정/세션·감사 원문을 제공하지 않는다.

## 2. 배치 원자성과 재시도

고객사 독점 잠금 → 현재 관리자 재검증 → 같은 명령 재전송 검사 → 모든 대상/버전/최종 상태 검증 → 업무·세션 회수·감사 → 커밋 순서다. 기존 단건 독립 트랜잭션을 반복 호출하지 않는다.

조직은 배치 최종 그래프의 순환·활성 부모/자식·오늘/미래 소속을 검증한다. 사원은 최종 재직 상태에 따른 마지막 영구 관리자 보호와 기존 재직 변경 세션 회수를 검증한다. 실패하면 앞 행·감사·auth_version까지 모두 롤백한다.

루트 감사 after_state에 batchId와 clientRowId/id/version 결과 매핑을 남겨 성공 재전송의 동일 결과를 복원한다. 입력 해시는 정규화 행 순서·ID·버전·업무 입력·사유만 포함한다. 행별 감사는 별도 commandId와 같은 batchId의 request_trace_id로 연결한다. 감사 화면은 허용된 식별자·버전·건수만 표시한다. 이미 성공했어도 현재 관리자 자격이 없으면 재전송을 허용하지 않는다.

## 3. 담당 파일과 TDD

### A. 기준 회귀·배치 백엔드

담당: `OrgCommands`, 신규 `BulkData`, `OrgBulkService`, `OrgBulkController`와 필요한 Mapper/기존 목록 Controller 연결, `OrgBulk*IT`.

- [x] 변경 전 Java515개와 기존 React113개 통과를 기록한다. React 기준113개는 신규 시트10개 RED와 함께 확인했다.
- [x] 미제공 bulk POST/원자적 저장에 대한 실패를 확인한다.
- [x] 50행/51행·고정값·최종 순환·동시 마지막 관리자·마지막 행 실패·세션 롤백·멱등성/상이 입력·타 회사·unknown 필드를 검증한다.
- [x] 원자적 서비스와 실패 시트 모델을 구현하고 관련 회귀를 통과시킨다.

### B. 조직장·조직도 백엔드

담당: 신규 `OrganizationHeadData/Mapper/Service/Controller`, V12, 전용 IT, `ProfileCompany*`의 본인 조직장 조회.

- [x] 조직장 GET/POST 부재와 스키마 부재의 실패를 분리해 확인한다.
- [x] 조직장 배정·동일일 교체·미래 경계/예약 보존·종료·중첩·버전·타 회사·퇴사·겸직·역할 무변경·조회 중 회수·RLS/최소 권한을 검증한다.
- [x] 기존 회사 공유 읽기와 본인 정보에 조직장 상태를 연결한다.

### C. React 시트·트리·배정

담당: 신규 `OrgSheetPages.tsx/test`, `App.tsx/test`, 필요한 Admin/Identity 연결과 기존 `static/app.css`.

- [x] 입력/셀 수정/복수 선택 삭제/일괄 저장/취소·코드 불변·미저장 이동 확인을 먼저 실패시킨다.
- [x] 409/422 편집·삭제/ID 유지, 최신값 비교·필드 오류 초점, 503 재시도를 검증한다.
- [x] 접근 가능한 native table·details/summary로 조직도와 조직장 폼을 구현한다. 모바일 표 스크롤은 내부에 한정한다.
- [x] 관리자/대표 조직도·관리자 조직장 메뉴, 본인 조직장 상태와 기존 화면 회귀를 통과시킨다.

### D. 통합·로컬 반영·게시

root 담당: 보안 matcher·React allowlist·오류 advice·감사 안전 투영·migration 검증·문서·통합/실행.

- [x] 새 페이지 allowlist와 감사 배치 요약의 실패를 확인하고 명시 필드만 허용한다.
- [x] 전체 Java·React/타입/빌드, 기존 도구에 영향을 주는 회귀를 실행한다. 동시 Maven 실행은 하지 않는다.
- [x] 기본 DB의 업무34개 테이블·RLS/ACL·container/volume과 Flyway V11을 기록하고 custom 백업한다.
- [x] app/migrate를 빌드하고 별도 migrate로 V12를 적용한다. DB container/volume·기존 행은 보존한다.
- [x] 분리된 합성 DB의 실제 HTTP와 PC/390px 브라우저에서 시트 저장/오류·조직장·조직도·대표 제한을 확인한다.
- [x] 현재 검증·미실시 범위를 PRD/README/설계/개발 안내/품질 보고서에 반영한다.
- [x] git diff/status·문서 링크·커밋 대상을 검증한다. 이후 요청한 커밋·일반 Push를 수행하고 원격 SHA/깨끗한 작업 트리는 완료 보고에서 확인한다.

실행 명령은 기존 프로젝트 JDK25의 자식 환경에서 `./mvnw.cmd -B -o -s target/local-maven-settings.xml -Dfrontend.skip=true verify`, `npm --prefix frontend test`, `npm --prefix frontend run build`를 사용한다. 로그·백업·스크린샷은 Git 제외 `target/org-sheet-20261027/`에 둔다. 새 장시간 부하·전체 취약점 전수검사·외부 배포·운영 수용은 이번 기능 검증으로 대신하지 않는다.

## 4. 실제 결과

최종 Java548개·React136개·HTTP80개·PC/390px18회 관측을 확인했다. V12와 기존34개 업무 행/권한·DB 컨테이너/이미지/볼륨을 보존했고, 새 스키마의 합성 논리 복원을 검증했다. CSS 모바일 실패375/553px를375/375px로 수정한 뒤 관련 Java12개·재패키지/이미지 재빌드·HTTP/실제 기본 앱 화면을 재검증했다. 상세 성공/실패 이력과 미실시 범위는 [품질 보고서](../../quality/org-sheet-heads.md)를 따른다.
