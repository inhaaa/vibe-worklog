# React 권한·메뉴·내 정보 구현 계획

> **For agentic workers:** `superpowers:subagent-driven-development`로 담당 파일을 나눠 진행한다. 각 단위는 계약 → 실패 테스트 → 구현 → 검증 순서이며 공통 백엔드 빌드와 DB migration 적용은 통합 담당자가 직렬 실행한다.

**Goal:** 대표의 회사 읽기와 관리자 변경을 분리하고 역할별 React 메뉴·권한 관리 진입·본인 정보를 제공한다.

**Architecture:** [업무 확장 설계](../specs/2026-10-26-worklog-service-design.md)의 1단계를 [React 표현 계약](../specs/2026-10-26-react-full-migration.md)으로 구현한다. 기존 계정·명령·원장·CALC-1을 유지하고 회사 조회만 별도 현재 권한·공유 읽기 경계로 추가한다. 일반 앱의 관리자 우회·GET 저장·새 프런트 의존성은 추가하지 않는다.

**Tech Stack:** 현재 고정 React/TypeScript/Vite·Spring Boot·MyBatis·PostgreSQL·Flyway·JUnit/Vitest를 유지한다.

## 1. 승인과 범위

- 2026-10-27 사용자가 1단계 React 구현, 필요한 로컬 DB 변경, 테스트·문서·커밋·일반 Push를 명시 승인했다.
- 공통 `EMPLOYEE`, 관리자 `ATTENDANCE_ADMIN`, 대표 `COMPANY_REPRESENTATIVE`와 결재 기능 `APPROVER`를 분리한다. 대표만으로 설정·결재 권한을 자동 얻지 않는다.
- 조직장·새 결재선·시트 일괄 편집·새 코드·연도별 모달·월간 그래프는 후속이다. 현재 사원별 역할 변경에 대표 선택을 추가하고 전용 권한 목록에서 연결한다.
- 기본 로컬 DB는 보존하며 V11만 추가 적용한다. 테스트는 새로운 전용 PostgreSQL과 합성 자료를 사용한다. 다른 기존 서비스·환경 설정·전역 도구·실제 고객·외부 배포는 변경하지 않는다.

## 2. 선행 스키마·화면 계약

- `V11__company_representative.sql`: `role_grant`의 허용 역할 CHECK에 대표만 추가한다. V1~V10·기존 역할·RLS·DB 역할/권한은 보존한다.
- `CompanyReadAccess.read(actor, Supplier<T>)`: tenant 트랜잭션, 공유 advisory 잠금, 현재 계정·재직·세션 버전·공통 및 관리자/대표 역할을 진입·반환 전에 확인한다. 기존 `OrgCommands`·`BusinessCommands`의 쓰기 허용 역할은 넓히지 않는다.
- `GET /me/profile` → `workspace/profile`, 모델 `profile`: 본인 이름·사번·회사·이메일·재직·현재 조직/일정/지정 결재자·역할·기준 시각. 대상은 principal에 고정한다. 조직장 배정은 다음 단계다.
- `GET /admin/access?q&page` → `org/access-list`: `accessRows`, `q`, `page`, `hasNext`. 행은 employeeId·사번·이름·역할·활성 자격만 제공하고 기존 `/admin/employees/{id}/access`로 연결한다.
- `GET /company/attendance?month&organizationId&employeeId&q&page` → `company/attendance`, 모델 `companyAttendance`: 같은 회사50명 목록과 선택 사원1명의 월별 일 결과. 오래된 값은 현재 합계로 제공하지 않는다.
- `GET /company/requests?year&organizationId&status&page` → `company/requests`, 모델 `companyRequests`: 같은 회사 대상기간 신청50행. 자유 사유·이메일·IP·정정 원문·근거·감사·Excel·변경 폼을 포함하지 않는다.
- 회사 GET은 관리자/대표만, 관리 변경은 관리자만, 실제 결정은 기존 지정 APPROVER만 허용한다. 모든 신규 모델은 React allowlist에 명시한다.

## 3. 담당 파일

| 담당 | 파일 |
| --- | --- |
| 권한·역할 | 신규 V11, `common/tenant/CompanyReadAccess.java`, `common/config/SecurityConfiguration.java`, `WorkspaceWebConfiguration.java`, `org/AccountService.java`, `AccountAdminMapper.java`, `AccountAdminController.java`, 신규 `CompanyAccessWebIT.java` |
| 본인·회사 조회 | 신규 `workspace/ProfileCompanyData.java`, `ProfileCompanyMapper.java`, `ProfileCompanyQueries.java`, `ProfileCompanyController.java`, `worktime/WorktimeService.java`, 신규 `ProfileCompanyWebIT.java` |
| React | `frontend/src/App.tsx`, `App.test.tsx`, `pages/AdminPages.tsx`와 관련 테스트, 신규 `pages/IdentityPages.tsx`, `IdentityPages.test.tsx` |
| 통합·문서 | `common/web/ReactPageViewResolver.java`, 이 계획, 개발·품질 보고, README·PRD·확장 설계의 실제 상태 |

Java 파일의 패키지 기준은 `src/main/java/kr/tna/`, 통합 테스트는 `src/test/java/kr/tna/`다. 공유 파일을 여러 담당자가 동시에 편집하지 않는다.

## 4. 검증·개발 순서

### A. 기준 회귀

- [x] 변경 전 Git 상태·커밋과 기존 전체 Java/프런트/Python 결과를 확보한다.
- [x] 프로젝트 전용 JDK25를 자식 프로세스에서만 사용하고 새 DB fixture의 loopback·자동 정리 경계를 유지한다.

### B. 백엔드 실패 회귀

- [x] 신규 `CompanyAccessWebIT`에서 현재 미제공 권한 목록·대표 역할 저장의 실패를 확인한다.
- [x] 신규 `ProfileCompanyWebIT`에서 본인 정보·대표 회사 조회 실패를 확인한다.
- [x] 회사 읽기 사이 역할 회수/세션 버전 변경, 타 회사 객체·풀 컨텍스트 재사용·공유 읽기/독점 변경을 검증한다.

대표의 관리 POST와 임의 승인은 기존403을 유지해야 하며 새 정상 GET과 함께 확인한다.

```java
// 신규 대표 전용 세션의 기대 행위. 실제 검증은 담당 통합 테스트의 합성 자료를 사용한다.
mvc.perform(get("/company/attendance").session(representativeSession))
        .andExpect(status().isOk());
mvc.perform(post("/admin/calculations").session(representativeSession).with(csrf()))
        .andExpect(status().isForbidden());
```

### C. 구현과 React 실패 회귀

- [x] V11·현재 회사 읽기 경계·역할 관리/목록·본인/회사 DTO·조회·allowlist를 구현한다.
- [x] App 행동 테스트로 공통·관리자·대표·APPROVER의 독립 메뉴와 합집합을 먼저 실패시킨다.
- [x] 프로필·회사 읽기·권한 목록·대표 체크박스를 구현하고 Vitest·타입·빌드를 통과시킨다.
- [x] 기존 폼·CSRF·303/409/422·세션 오류와 안전한 페이지 데이터 계약을 보존한다.

### D. 통합·로컬 반영

- [x] 관련 Java 회귀 후 전체 Java·React·Python 회귀를 확인한다. 동시 Maven 실행은 하지 않는다.
- [x] 기본 DB의 컨테이너·볼륨·테이블 행/권한을 먼저 기록하고 백업을 보존한다.
- [x] 새 로컬 app/migrate를 빌드하고 기존 DB에 V11을 적용한다. DB 컨테이너·볼륨은 재생성하지 않는다.
- [x] 본인·관리자·대표·결재 기능의 실제 HTTP와 React 화면을 확인하고 기존 DB 자료 보존을 대조한다.
- [x] 변경·검증·미실시 범위를 문서화한다. Git 게시는 아래 절의 검증 후 명시 파일만 대상으로 수행하고 완료 보고의 원격 SHA로 확인한다.

## 5. 실행 명령과 수용 조건

기존 ignored Maven 설정을 사용하며 비밀값을 출력하지 않는다. Java/Python 명령은 프로젝트 JDK25의 자식 환경에서 실행한다.

```powershell
.\mvnw.cmd -B -o -s target/local-maven-settings.xml '-Dfrontend.skip=true' verify
npm --prefix frontend test
npm --prefix frontend run build
python -B -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

권한·조회 테스트는 대표 설정/재집계/감사/Excel 접근 거부, 지정되지 않은 결재 거부, 역할 회수와 옛 세션, 다른 회사 조회 차단, GET 무변경, 본인 대상 고정, 미배정·미산정·오래됨,50행·필터·기간·민감값 비노출을 포함한다. React는 메뉴·조건/페이지 이동·내 정보 진입·대표 체크와 기존 APPROVER 보존을 검증한다.

최종 수치·로컬 적용 결과는 [품질 보고서](../../quality/identity-access.md)에 기록했다. Java515개·React113개·실제 HTTP51개·PC/모바일9회와 기존 DB 보존을 확인했다. 이번 기능의 회귀·로컬 반영을 새33분 부하·새 취약점 전수 검사·외부 운영 수용으로 확대하지 않는다.
