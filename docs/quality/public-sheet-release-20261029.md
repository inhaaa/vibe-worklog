# 공개 공통 시트·V14 통합 후보

기준일: 2026-10-29, Asia/Seoul. 이 문서는 공개 배포 전의 별도 통합 후보와 로컬 검증을 기록한다. 서버 배포·마이그레이션 완료 보고가 아니다. 실제 후속 배포는 [공개 갱신 절차](../development/worklog-public-updates.md)를 따른다.

## 소스와 보안 보존

- 기존 공개 브랜치 `2ac6ea5`에 검증된 개발 `origin/main`의 `8004848`을 통합했다. 공개 런타임의 마지막 완료 기록 `efd13ea`·V13과 개발본 V14·공통 시트를 구분한다. [이전 공개 배포 결과](public-menu-deploy-20261028.md)의 수치를 이번 후보 검증으로 재사용하지 않는다.
- 후보는 별도 `codex/public-sheet-release` 브랜치다. 기존 공개 worktree·서버 소스와 다른 개발 worktree의 작성 중인 변경은 수정하거나 포함하지 않았다. 최초 통합 `3894948` 뒤 확정 UI `bbd525c`를 충돌 없이 merge한 커밋은 `951e98f`다.
- `public-demo` 단독 활성화·운영자 시작 거부·Secure 쿠키·CSRF와 현재 세션/권한·tenant/RLS를 유지했다. 공유 데모 사원의 단건 변경·역할·초대와 일괄 변경 보호도 보존했다.
- 코드·근태코드 관리 경로의 관리자 접근 조건과 `OrgBulkService.employees`의 UUID 기반 공유 사원 검사를 함께 확인했다. 기존 공개 보안 파일을 개발본으로 대체하지 않았다.

## 통합 검증과 보완

첫 선택 검증에서 단위10개·V13→V14 기존 자료/권한 보존1개·공개 HTTP14개는 통과했으나 공개 전용 테스트의 정리 helper2개가 V14 기초4테이블을 남겨 FK 오류와 이후 연쇄 오류14건을 냈다. main의 기존 로컬 데모 정리 방식과 대조하고 두 공개 helper에도 자식→부모 정리를 추가했다. 제품 코드·스키마 제약·원래 assertion은 완화하지 않았다.

최종 선택 재검증은 단위10개·실제 PostgreSQL 통합29개=39개, 실패/오류/skip0·Maven `verify` 종료0으로 통과했다. 통합29개는 공개/local 공유 계정 보호9개·공개 bootstrap5개·공개 HTTP14개·V13→V14 자료/권한 보존1개다. 원시 RED 로그는 ignored `target/public-sheet-verify-red.log`, 재검증 로그는 `target/public-sheet-verify.log`에 보존한다. `python -B -m unittest scripts.test_public_demo_deploy -v`의 공개 배포 파일 계약7개와 `git diff --check`도 통과했다.

검증은 프로젝트 JDK25와 기존 Maven 설정을 자식 프로세스에만 지정하고 기본 Testcontainers 경로의 새 소유 PostgreSQL 컨테이너·loopback 임의 포트를 사용했다. 기본 앱/DB나 외부 DB를 지정하지 않았다. 종료 후 RED/GREEN의 소유 DB·Ryuk 컨테이너4개가 모두 자동 정리돼 존재하지 않음을 확인했다. 선택 Java 범위는 `PublicDemoProfileTest`, `PublicDemoBootstrapTest`, `PublicDemoIdentityGuardIT`, `LocalDemoIdentityGuardIT`, `PublicDemoSecurityIT`, `PublicDemoBootstrapIT`, `CodeMigrationUpgradeIT`다.

## 클릭 편집 기준선과 실행 JAR

`bbd525c`의 박스 없는 셀 표시·선택/키보드 편집·공통 편집기와 조직/사원·결재선·코드 시트를 통합했다. 후보의 공통 프론트37파일은 원본 개발 worktree와 LF 정규화 SHA256 manifest가 같다(`02b12701155ca237651b52c2c4bb006ee9b98958e94334fd0982c2425afe8f19`). 차이는 기존 공개 안내의 `AuthPages.tsx`와 테스트 두 파일뿐이며 후보의 해당 단일 worker 회귀9개를 통과했다. 공통 프론트 전체 테스트는 원본의 최종 실행 결과를 따르며 후보에서 중복 실행하지 않았다.

후보에서 `npm ci`·TypeScript/Vite production build·프로젝트 JDK25의 `package -Dfrontend.skip=true -DskipTests`를 순서대로 완료했다. 이 package는 이미 생성한 dist를 포함하며 테스트 재실행을 뜻하지 않는다. `app.js`·`sheet.css`·라이선스·SVG4개·WOFF2 한 개 총8파일의 집합과 모든 바이트가 JAR의 `BOOT-INF/classes/static/react/`와 일치한다. CSS의 data URL 부재와 외부 자산 참조, `application-public-demo.yml`·공유 사원 guard 클래스 포함도 확인했다.

이 기준선 JAR의 SHA256은 `b65bcc7a10b49a32b79a677c96d4ddfa56336b91c5014a40c0ccf03aa4d94933`이다. ignored `target/vibe-attendance-public-sheet-bbd525c.jar`와 `public-sheet-artifact-proof-bbd525c.json`, `public-sheet-source-proof-bbd525c.json`에 당시 artifact와 대조 근거를 보존했다. npm 설치·build·공개 AuthPages·package 로그도 같은 target에 보존한다.

이 UI 통합 전후 Java main/test·main resources·pom의 Git 객체가 `3894948`과 모두 동일함을 확인했다. 따라서 위39개 보안/PG 검증의 Java 범위는 유지되며 새 전체 Java 회귀를 실행했다고 표현하지 않는다. 독립 검토에서 발견된 셀 로컬 검증의 `aria-invalid`·오류 설명 연결 보완은 현재 원본에서 진행 중이며 아직 이 기준선에 포함하지 않았다. 그 후속 커밋과 최종 전체 프론트 결과를 반영하기 전 이 artifact를 최종 공개 배포 대상으로 확정하지 않는다.

## 서버 반영 전 남은 조건

현재 정상 SSH 자격과 연결된 Hostinger Webconsole이 없어 서버 명령을 실행하지 않았다. 신규 관리 자격·socket 연결·방화벽 변경으로 우회하지 않았다. 기존 공개 버전과 PriceSys·demo·portfolio는 변경하지 않았다.

V14는 신규4테이블과 `approval_request.attendance_type_version_id`의 NOT NULL/FK를 포함한다. 공개 서버의 현재 SHA·스키마를 먼저 읽고 WorkLog 전용 백업→독립 복원→V14 시험과 기존 열/행·ACL/RLS 대조→WorkLog 앱만 중지·최종 백업→별도 migration 역할→후보 앱의 순서로 진행해야 한다. V14 후 과거 앱은 정책 버전 없는 신청 INSERT와 호환되지 않으므로 구 앱만 무조건 되돌리지 않는다. bootstrap·sample-data는 재실행하지 않는다.

최초39개 Maven 검증 시점에는 dist가 없었으며 그때 생성한 JAR을 배포 대상으로 사용하지 않았다. 이후 위 기준선에서 프론트를 실제 빌드해 JAR 포함까지 검증했다. 접근성 후속 커밋·최종 공통 프론트 결과와 app/migrate 이미지의 revision·ID 검증은 남아 있다. 후보 전체 Java 회귀·새 이미지 취약점/장시간 부하·서버 백업/복원·공개 HTTPS/PC·모바일 검증도 이번 선택 검증의 통과로 확대하지 않는다.
