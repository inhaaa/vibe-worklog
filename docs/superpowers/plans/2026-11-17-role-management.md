# 권한별 조회와 사원 부여·해제 구현 계획

**목표** 담당자가 시스템 설정에서 권한을 선택하고, 같은 시트에서 해당 권한의 조회·부여·해제를 처리한다. [승인된 상세 명세](../specs/2026-11-17-role-management-design.md)

**기반** 검증된 조직관리 개발 `4624c1a`를 시작점으로 사용한다. 조회에는 `OrgCommands` 공유 읽기, 저장에는 고객사 독점 명령과 행별 감사 재생, 화면에는 React `WorklogSheet`를 재사용한다. 기존 Java·MyBatis·PostgreSQL·React·TypeScript로 구현한다.

## 공통 인터페이스

- `org/access-list`의 `data.roleDirectory`는 `roles`, `role`, `members`, `q`, `page`, `hasNext`, `total`을 제공한다. `roles` 원소는 `code`, `label`, `editable`, `memberCount`다. `members` 원소는 `employeeId`, `employeeNo`, `employeeName`, `organizationName`, `version`, `available`, `editable`, `restriction`이다.
- `GET /admin/access/roles/{role}/candidates?q=…&page=…`는 JSON `rows`, `page`, `hasNext`를 반환한다. `rows`는 동일한 사원 행 형식이다. 조회 역할은 `EMPLOYEE`, `ATTENDANCE_ADMIN`, `COMPANY_REPRESENTATIVE`, `APPROVER`이며 기본 선택은 `ATTENDANCE_ADMIN`이다.
- `POST /admin/access/roles/{role}/members/bulk`의 JSON은 `commandId`, `reason`, `rows`를 보낸다. 행은 `clientRowId`, `employeeId`, `expectedVersion`, `operation`(`GRANT`·`REVOKE`)으로 구성한다. 결과에는 `affectedSelf`, 행별 `clientRowId`·`employeeId`·`version`을 포함한다. 409/422는 `message`, `errors`(행의 `clientRowId`와 `message`) 및 409의 최신 행 상태 `latest`를 제공한다.
- `RoleManagementPage`는 역할 선택·조회 조건·시트 배치, `RoleMembersSheet`는 사원 시트·후보·초안·저장을 맡는다. 통합에서 `IdentityPages.tsx`가 두 화면을 연결하고 신규 GET·POST를 공통 보안 설정에 등록한다.

## 작업 A. 권한별 조회

- [ ] `RoleDirectoryWebIT`와 `RoleManagementPage.test.tsx`에서 기본 선택, 역할별 집합·전체 수, 사번·이름 검색, 50행 페이지와 검색 문맥, 오늘 소속과 사용 제한, 권한·사원 격리를 먼저 재현한다.
- [ ] `RoleDirectoryService`, `RoleDirectoryMapper`, `AccountAdminController`, `ReactPageViewResolver`에 역할별 조회 모델·후보 JSON을 연결한다. 고정 역할과 기본값을 검증하고 조회 데이터에 불필요한 계정·감사 원문을 포함하지 않는다. 기존 사원 상세는 유지한다.
- [ ] `RoleManagementPage.tsx`에 PC 좌우 및 모바일 상하 배치, 검색·페이지 문맥을 구현한다. 시트 렌더링 자리에는 통합 시 `RoleMembersSheet`를 연결한다.

## 작업 B. 시트의 사원 추가·권한 해제

- [ ] `RoleMembershipWebIT`와 `RoleMembersSheet.test.tsx`에서 초안·취소, 대상 역할만 저장, 단건·혼합 요청 오류 롤백, 마지막 유효 담당자 해제와 후임 교체, 감사·세션·재전송, 버전 충돌과 오류 복구를 먼저 재현한다.
- [ ] `RoleMembershipController`·`RoleMembershipService`에서 사유·행·버전·계정 자격을 검사하고 선택한 권한만 원자적으로 갱신한다. 유효 담당자 조건은 전체 저장 결과로 정하고 감사·세션 회수를 함께 처리한다.
- [ ] `RoleMembersSheet.tsx`에 후보 다중 선택, 현재 권한 행의 해제 예정, 취소, 강제 이동 예방, 변경 건수·사유, 저장 오류·최신 상태 비교 및 자기 역할 변경 안내를 구현한다.

## 통합과 확인

- [ ] 두 작업을 별도 작업트리에서 구현해 `codex/role-management-20261117`에 통합한다. 개발과 공개 데모 공유 사원 정책, 권한 없는 관리자 경로, 미저장 탐색과 자기 권한 변경 후 세션 처리를 검증한다.
- [ ] 전체 프런트엔드, TypeScript·빌드, Java 단위 및 관련 PostgreSQL 통합을 실행한다. 타 고객사와 CSRF, 단건 역할·결재자 적격성·대기 신청 스냅샷, 공개 보호를 확인한다.
- [ ] 격리 합성 PostgreSQL 기반 PC와 390px Chrome에서 담당자가 권한을 선택하고 사원을 검색·추가·해제·저장하는 흐름을 검증한다. 공통 조회, 다른 권한 보존, 오류 복구와 재로그인 메시지를 확인하고 실제 결과를 기록한다.
