# WorkLog React 프론트엔드 전환 설계 초안

> 후속 결정: 2026-10-26 사용자가 전체 화면 즉시 전환을 승인했다. 아래 직원 목록 우선 초안은 검토 이력이며, 실제 작업은 [전체 React 전환 계약](2026-10-26-react-full-migration.md)을 따른다.

## 1. 상태와 결정 제안

- 작성일: 2026-10-26, Asia/Seoul. 조사 기준: `625858d`의 소스와 기존 설계.
- 사용자는 Thymeleaf의 익숙함 문제와 최근 프론트엔드 선택지를 문의한 뒤 React 방향을 제안했다.
- 권장 조합은 **Spring Boot + React + TypeScript + Vite**다. Java 업무 서비스·MyBatis·PostgreSQL·RLS를 재사용하고 화면과 JSON HTTP 경계를 추가한다.
- 이 문서는 사용자 검토 전 초안이다. React 전환 구현·설정 변경·DB 작업·Commit·Push·배포를 수행했다는 뜻이 아니다. 필요한 설정 변경과 전용 로컬 DB 검증은 실행 전 사용자 확인 대상으로 명시한다.
- 기존 [아키텍처](../../design/architecture.md)와 [업무 확장 설계](2026-10-26-worklog-service-design.md)의 Thymeleaf 유지 결정은 현재 구현 기준으로 남긴다. 본 초안 승인 후 해당 기술 선택만 갱신하며, 확장 기능의 구현 승인을 함께 가정하지 않는다.

## 2. 대안과 선택 이유

| 방식 | 이점 | 부담 | 제안 |
| --- | --- | --- | --- |
| 별도 경로에서 화면별 React 전환 | 기존 업무를 사용하면서 API·보안·화면을 검증할 수 있음 | 전환 중 두 화면 기술을 관리 | 채택 권장 |
| 전체 화면 일괄 전환 | 완료 후 하나의 화면 기술로 통일 | 인증·신청·승인·집계 전체의 회귀 범위가 한꺼번에 증가 | 첫 작업에서 제외 |
| Thymeleaf 화면 안에 React 컴포넌트 삽입 | 작은 위젯을 적은 비용으로 개선 | 템플릿 학습 부담이 계속 남고 화면 상태의 소유자가 나뉨 | 단순 위젯에 한해 후속 판단 |

Java 백엔드와 React·Vue의 연결 방식은 JSON HTTP라는 점에서 같다. 이번 React 권장은 백엔드 언어와의 독점적 호환성이 아니라 사용자가 제안한 방향과 화면 개발 경험을 위한 선택이다. Next.js의 서버 렌더링이나 별도 프론트 실행 서버는 첫 전환의 요구사항에 포함하지 않는다.

## 3. 첫 구현 단위

직원 목록의 조회 흐름을 React로 옮겨 첫 연결을 검증한다.

- React 진입: `GET /app/employees`.
- 기능: 이름·사번 검색, 현재 소속 필터, 활성 상태 필터, 50명 페이지 이동, 현재 페이지 통계, 현재 페이지 직원 선택·조회.
- 디자인: 기존 흰색·파란색 업무 테마와 반응형 표·카드, 키보드·레이블·오류 안내를 유지한다.
- 사원 등록·수정·계정 관리는 기존 `/admin/employees` 계열 화면으로 명확하게 연결한다. 첫 React 화면은 변경 요청을 보내지 않는다.
- 기존 로그인·로그아웃·초대·복구·운영자 화면과 업무 경로는 유지한다. React 화면에도 기존 로그인과 로그아웃 경로를 연결한다.
- 권한: 현재 고객사의 유효한 `ATTENDANCE_ADMIN`. 향후 대표 역할이나 확장 설계의 권한을 앞당겨 구현하지 않는다.

## 4. HTTP·데이터 계약

### 조회 요청

`GET /api/admin/employees?q=&page=0&organizationId=&status=all&employeeId=`

- 기존 `EmployeeService.directory`의 입력·검증·페이지 경계를 따른다. `page`는 0부터, 검색어는 120자 이내, `status`는 `all|active|inactive`다. 빈 선택 필드는 URL에서 생략한다.
- `employeeId`를 생략하면 현재 페이지 첫 직원을 선택한다. 지정한 직원은 현재 페이지에 포함되어야 한다. 빈 결과의 선택 직원은 `null`이다. 프론트는 검색·필터·페이지를 변경할 때 이전 선택 ID를 제거하며, 검색·필터 변경 시 첫 페이지로 돌아간다.
- 기존 `employeeNo,id` 정렬과 51번째 행으로 `hasNext`를 판단하는 조회를 재사용한다. 전체 건수 조회를 새로 추가하지 않는다.
- 다른 고객사의 조직·직원이나 현재 페이지 밖의 선택은 기존과 같이 404다. 형식 오류·잘못된 필터·페이지는 400이다.
- 테넌트는 인증 주체에서만 결정한다. 요청에 `tenantId`를 받지 않는다.

### 조회 응답

```typescript
type EmployeeStatus = 'all' | 'active' | 'inactive';

interface EmployeeRow {
  id: string;
  employeeNo: string;
  displayName: string;
  organizationName: string | null;
  joinedOn: string; // YYYY-MM-DD, 업무 날짜
  leftOn: string | null;
  active: boolean;
}

interface EmployeesResponse {
  filters: {
    q: string;
    page: number;
    organizationId: string | null;
    status: EmployeeStatus;
  };
  employees: EmployeeRow[];
  organizations: { id: string; name: string }[];
  selectedEmployeeId: string | null;
  hasNext: boolean;
  pageMetrics: { employeeCount: number; activeCount: number; inactiveCount: number };
}

interface ApiError {
  code: string;
  message: string;
}
```

직원 DTO는 현재 화면의 최소 필드만 포함한다. 날짜는 브라우저 시간대로 변환하지 않고 업무 날짜 그대로 표시한다. 현재 소속은 서버가 서울 기준 오늘로 판정한다. 활성 여부를 재직 여부나 퇴사 판정으로 바꾸지 않는다. 통계는 현재 페이지 기준이며 회사 전체 합계로 표시하지 않는다.

### 인증·오류·캐시

- 고객사 세션 쿠키를 유지한다. 프론트 전환을 이유로 JWT나 브라우저 저장소 토큰을 도입하지 않는다.
- `/api/**`에서 미인증·만료는 401 `UNAUTHENTICATED`, 권한 부족은 403 `FORBIDDEN`, 입력 오류는 400 `INVALID_REQUEST`, 범위 밖은 404 `NOT_FOUND`, 저장소 장애는 503 `SERVICE_UNAVAILABLE` JSON으로 반환한다. 로그인 HTML이나 redirect를 JSON 성공 응답으로 처리하지 않는다.
- 필터 단계의 세션 검증 장애도 API에서는 같은 503 계약을 지킨다. SQL·스택·자유 요청값을 오류 응답에 포함하지 않는다.
- `OrgCommands.read`의 현재 관리자 확인, 공유 읽기 경계, 반환 전 재검증과 SQL 테넌트 조건·RLS를 재사용한다. 여러 조회를 합친 DTO도 반환 전 현재 권한을 확인하며 조회 중 쓰기 잠금 승격·저장을 하지 않는다.
- API 데이터·인증 오류·사용자별 앱 응답에는 `Cache-Control: no-store`를 적용한다. 해시가 붙은 공개 JS/CSS와 개인정보 응답의 캐시 정책을 구분한다.
- 401은 기존 로그인으로 전체 이동하고 403·503은 구분해서 안내한다. 401·403·로그아웃 때 표시 중인 직원 자료를 지우고 브라우저 저장소·서비스워커에 직원 자료를 저장하지 않는다. 검색 조건 변경 시 이전 요청을 취소하거나 오래된 응답을 무시해 최신 조건과 다른 명단이 표시되지 않게 한다. 로딩 중·조회 실패·빈 결과를 구분한다.
- CSRF 보호는 로그인·로그아웃을 포함해 유지한다. 로그아웃 폼에 쓸 토큰은 인증된 `GET /api/csrf`에서 `token`, `parameterName`, `headerName`만 받고 no-store를 적용한다. 이후 변경 API에서도 토큰 전달·로그인 이후 갱신 계약을 검증한다.

## 5. 소스·빌드·실행 경계

- `frontend/`에 React·TypeScript 소스와 프로젝트별 npm 의존성·잠금 파일을 둔다. 정확한 버전·Node 요구사항·라이선스는 구현 전 공식 자료로 확인하고 고정한다. 전역 패키지를 설치하지 않는다.
- React 빌드 산출물을 Maven 패키징 시 Spring Boot 정적 자원에 포함해 `/app/` 아래에서 제공한다. Node는 개발·빌드 도구이며 애플리케이션 실행에 별도 Node 서버를 요구하지 않는다.
- 개발 중 Vite를 사용한다면 loopback에서 실행하고 필요한 API·로그인 경로만 기존 로컬 Spring으로 프록시한다. 쿠키가 같은 출처에서 동작하도록 맞추며 CORS·CSRF·CSP를 포괄 완화하지 않는다. 완성 검증은 패키징된 같은 출처 앱에서 수행한다.
- 서버의 앱 HTML 진입 처리는 첫 화면의 명시 경로에 한정한다. API·정적 자원·알 수 없는 경로를 앱 HTML로 변환하는 광범위 fallback을 만들지 않는다.
- 첫 화면에 필요한 React 상태와 브라우저 `fetch`를 사용한다. 전역 상태·그리드·차트·라우팅 라이브러리는 필요가 생긴 단위에서 판단한다.
- 예상 설정 변경: `frontend/package.json`, npm 잠금 파일, TypeScript/Vite 설정, Maven 패키징 연동과 Spring 보안의 명시 경로·API 오류 분기. 실제 변경 목록은 구현 계획에서 확정한다. DB 스키마 변경은 첫 단위에 필요하지 않다.

## 6. 구현·검증 순서와 수용 기준

1. 이 HTTP·DTO·권한 계약을 합의한 후 구현 계획을 작성한다. TDD로 API·인증 실패 계약 테스트를 먼저 작성하고 실패를 확인한다.
2. 기존 서비스를 호출하는 최소 JSON API를 구현한다. 실제 전용 PostgreSQL에서 현재 관리자·일반 직원·타 고객사·권한 회수·서비스 장애·검색/페이징을 검증한다.
3. React 조회 화면의 필터·선택·오래된 응답·오류/빈 결과 테스트를 먼저 작성하고 화면을 연결한다. typecheck·테스트·프로덕션 빌드를 실행한다.
4. 실제 패키지에서 로그인→React 직원 조회→기존 상세/등록 연결→로그아웃, 새로고침·좁은 화면·직접 경로 진입·세션 만료를 검증한다. CSP 위반과 API 대신 HTML이 반환되는 오류도 확인한다.
5. 기존 Java 기본·웹/보안 회귀와 테넌트 격리 테스트를 실행한다. 실제 수행한 범위만 문서·README·ADR에 반영하고 미실시 DB·컨테이너·부하 검증은 구분한다.

첫 단위의 완료는 React 직원 조회와 기존 업무의 공존 검증이다. 전체 프론트 전환이나 아직 미구현인 업무 확장 기능의 완료로 표시하지 않는다. 다음 단위는 이 결과를 검토한 뒤 개인 홈·달력, 신청·승인, 관리 화면 순서의 적합성을 판단한다.

## 7. 근거와 이번 검증 범위

- 로컬 근거: [직원 Controller](../../../src/main/java/kr/tna/org/OrgAdminController.java), [직원 서비스](../../../src/main/java/kr/tna/org/EmployeeService.java), [직원 화면](../../../src/main/resources/templates/org/employees.html), [관리 명령 경계](../../../src/main/java/kr/tna/org/OrgCommands.java), [보안 설정](../../../src/main/java/kr/tna/common/config/SecurityConfiguration.java), [세션 검증 필터](../../../src/main/java/kr/tna/common/auth/SessionValidationFilter.java).
- 공식 자료 조회일: 2026-10-26. [React 기존 앱 연결](https://react.dev/learn/add-react-to-an-existing-project), [React Vite 구성과 고려사항](https://react.dev/learn/build-a-react-app-from-scratch), [Vite 백엔드 연동](https://vite.dev/guide/backend-integration), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
- 이번 작업은 초안과 링크·기존 계약 일관성 검토다. 코드·설정·DB·컨테이너를 변경하거나 코드 테스트·빌드·전환 성능 측정을 실행하지 않았다.
