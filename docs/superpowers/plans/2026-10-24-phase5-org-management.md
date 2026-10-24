# Phase 5 조직·사원 관리 구현 계획

> 실행 방식: `superpowers:subagent-driven-development`, SDD·TDD. 기존 승인 설계와 자율 진행 위임을 따라 독립 파일을 병렬 작업하고 검증·커밋은 주 에이전트가 순차 수행한다.

**목표:** [첫 구현 계약](../../design/org-management.md)에 맞는 조직·사원·소속 관리 한 단위를 완성한다.

**구조:** 기존 TenantTransactions·MyBatis·Thymeleaf를 재사용한다. V2와 관리자 명령 경계, 조직 서비스, 사원 서비스, HTTP 화면을 분리한다. 새 Java 라이브러리는 도입하지 않는다.

## 1. 문서와 실패 검증

- [x] AGENTS·CLAUDE·README의 현재 단계·승인 범위를 Phase5 첫 단위로 갱신한다.
- [x] `OrgSchemaIT`에서 `to_regclass('tna.organization')`이 존재함을 먼저 검증해 V1에서 실패를 확인한다.
- [x] `OrganizationWebIT`에서 담당자 GET `/admin/organizations`의200을 먼저 검증해 미구현403 실패를 확인한다.
- [x] 설계·계획·첫 실패 증거를 검토한다.

## 2. V2·공통 명령 경계

- [x] `src/main/resources/db/migration/V2__organization_management.sql`과 `OrgSchemaIT`에 복합 FK·권한·RLS·중첩·감사 불변 사례를 구현한다.
- [x] `src/main/java/kr/tna/org/OrgData.java`에 조직/사원/배정·폼 레코드를 정의한다.
- [x] `OrgCommands.java`에 고객사 잠금·현재 권한·명령 해시/재시도·감사·롤백을 구현한다. `OrgException.java`는400/403/404/409/422의 안전한 오류만 전달한다.
- [x] `DatabaseFoundationIT`의 새 쓰기 범위·비밀번호 차단·테이블 수 계약을 갱신하고 기존 테스트를 보존한다.

## 3. 업무 서비스와 MyBatis

- [x] `OrganizationAdminMapper.java`·`OrganizationService.java`·`OrganizationServiceIT.java`: 코드 불변, 계층 순환·부모·활성·현재 참조 검사, 명령 원자성.
- [x] `EmployeeAdminMapper.java`·`EmployeeService.java`·`EmployeeServiceIT.java`: 사번 불변, 재직/세션 개정·마지막 담당자, 적용일 소속 이동·이력·버전 검증.
- [x] 서비스 인터페이스는 `list(actor,q,page)`, `get(actor,id)`, `create(actor,form)`, `update(actor,id,form)`, 사원 `assign(actor,id,form)`이다. 목록은51행 조회로50행 페이지와 다음 링크를 판단한다.
- [x] 실제 두 커넥션에서 계층 변경·마지막 담당자 경합을 검사한다. 감사·명령 재시도·실패 전체 롤백을 검증한다.

## 4. 서버 렌더링 관리 화면

- [x] `OrgAdminController.java`, `templates/org/`와 관련 CSS를 추가한다. 최소 역할 라우팅만 SecurityConfiguration에 허용한다.
- [x] GET 목록/상세, POST 생성/변경/이동,303·422 입력 보존·404·409를 `OrganizationWebIT`에서 확인한다.
- [x] Today 화면에 담당자만 관리 링크를 표시하고 이름·사유를 기본 이스케이프한다. CSRF·no-store를 유지한다.

## 5. 완료 검증과 커밋

```powershell
.\mvnw.cmd -B -s target/task-maven-settings.xml verify
docker compose -f compose.yaml -f target/compose.network.yaml build
docker compose -f compose.yaml -f target/compose.network.yaml up --no-build -d
git diff --check
```

- [x] Testcontainers 전체 테스트·독립 리뷰를 통과하고 Docker/Compose V2·HTTP를 확인한다. 테스트용 합성 자료와 빈 로컬 고객 DB를 구분한다.
- [x] README·개발 안내·구현 계약에 완료 범위·실제 테스트·남은 F03/F02를 반영한다.
- 전달 절차: 문서 링크·비밀정보·Git 변경 범위를 검사하고 완성 단위별 커밋·일반 Push한다.
- 전달 절차: 원래 main에 fast-forward 반영하고 로컬/원격 SHA·깨끗한 상태를 확인한다. 로컬 검증 컨테이너는 종료하고 DB 볼륨을 유지한다.

## 실행 기록

- 최초 RED: 조직 테이블 없음·미구현403의 두 실패를 확인했다.
- 리뷰 RED→GREEN: 비활성 기존 부모 유지, 잘못된 Unicode, 감사 직렬화 실패를 먼저 재현하고 수정했다.
- 전체 `verify`: 단위3+통합78=81개 통과, 실패·오류·건너뜀0. 실제 Testcontainers PostgreSQL을 사용했다.
- Docker 새 이미지 빌드·보존 V1 볼륨의 V2 적용·Compose HTTP10개와7개 FORCE RLS·빈 고객/계정을 확인했다.
- 별도 합성 DB의 브라우저에서 로그인→조직→사원→소속 등록/이력을 확인하고390px 모바일을 확인했다.
- 독립 명세·보안 리뷰를 수행하고 부모 선택값 소실을 수정했다. 새 Java 의존성·실제 고객 자료·외부 서버·배포는 없다.
