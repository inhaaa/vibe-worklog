# 공개 공통 시트·V14 통합 후보

기준일: 2026-10-29, Asia/Seoul. 이 문서는 공개 배포 전의 별도 통합 후보와 로컬 검증을 기록한다. 서버 배포·마이그레이션 완료 보고가 아니다. 실제 후속 배포는 [공개 갱신 절차](../development/worklog-public-updates.md)를 따른다.

## 소스와 보안 보존

- 기존 공개 브랜치 `2ac6ea5`에 검증된 개발 `origin/main`의 `8004848`을 통합했다. 공개 런타임의 마지막 완료 기록 `efd13ea`·V13과 개발본 V14·공통 시트를 구분한다. [이전 공개 배포 결과](public-menu-deploy-20261028.md)의 수치를 이번 후보 검증으로 재사용하지 않는다.
- 후보는 별도 `codex/public-sheet-release` 브랜치다. 기존 공개 worktree·서버 소스와 다른 개발 worktree의 작성 중인 변경은 수정하거나 포함하지 않았다. 후속 셀 표시·클릭 편집 개선은 아직 이 기준선에 포함하지 않는다.
- `public-demo` 단독 활성화·운영자 시작 거부·Secure 쿠키·CSRF와 현재 세션/권한·tenant/RLS를 유지했다. 공유 데모 사원의 단건 변경·역할·초대와 일괄 변경 보호도 보존했다.
- 코드·근태코드 관리 경로의 관리자 접근 조건과 `OrgBulkService.employees`의 UUID 기반 공유 사원 검사를 함께 확인했다. 기존 공개 보안 파일을 개발본으로 대체하지 않았다.

## 통합 검증과 보완

첫 선택 검증에서 단위10개·V13→V14 기존 자료/권한 보존1개·공개 HTTP14개는 통과했으나 공개 전용 테스트의 정리 helper2개가 V14 기초4테이블을 남겨 FK 오류와 이후 연쇄 오류14건을 냈다. main의 기존 로컬 데모 정리 방식과 대조하고 두 공개 helper에도 자식→부모 정리를 추가했다. 제품 코드·스키마 제약·원래 assertion은 완화하지 않았다.

최종 선택 재검증은 단위10개·실제 PostgreSQL 통합29개=39개, 실패/오류/skip0·Maven `verify` 종료0으로 통과했다. 통합29개는 공개/local 공유 계정 보호9개·공개 bootstrap5개·공개 HTTP14개·V13→V14 자료/권한 보존1개다. 원시 RED 로그는 ignored `target/public-sheet-verify-red.log`, 재검증 로그는 `target/public-sheet-verify.log`에 보존한다. `python -B -m unittest scripts.test_public_demo_deploy -v`의 공개 배포 파일 계약7개와 `git diff --check`도 통과했다.

검증은 프로젝트 JDK25와 기존 Maven 설정을 자식 프로세스에만 지정하고 기본 Testcontainers 경로의 새 소유 PostgreSQL 컨테이너·loopback 임의 포트를 사용했다. 기본 앱/DB나 외부 DB를 지정하지 않았다. 종료 후 RED/GREEN의 소유 DB·Ryuk 컨테이너4개가 모두 자동 정리돼 존재하지 않음을 확인했다. 선택 Java 범위는 `PublicDemoProfileTest`, `PublicDemoBootstrapTest`, `PublicDemoIdentityGuardIT`, `LocalDemoIdentityGuardIT`, `PublicDemoSecurityIT`, `PublicDemoBootstrapIT`, `CodeMigrationUpgradeIT`다.

## 서버 반영 전 남은 조건

현재 정상 SSH 자격과 연결된 Hostinger Webconsole이 없어 서버 명령을 실행하지 않았다. 신규 관리 자격·socket 연결·방화벽 변경으로 우회하지 않았다. 기존 공개 버전과 PriceSys·demo·portfolio는 변경하지 않았다.

V14는 신규4테이블과 `approval_request.attendance_type_version_id`의 NOT NULL/FK를 포함한다. 공개 서버의 현재 SHA·스키마를 먼저 읽고 WorkLog 전용 백업→독립 복원→V14 시험과 기존 열/행·ACL/RLS 대조→WorkLog 앱만 중지·최종 백업→별도 migration 역할→후보 앱의 순서로 진행해야 한다. V14 후 과거 앱은 정책 버전 없는 신청 INSERT와 호환되지 않으므로 구 앱만 무조건 되돌리지 않는다. bootstrap·sample-data는 재실행하지 않는다.

이번 Maven 검증은 `frontend.skip=true`이며 새 후보의 `frontend/dist`를 만들지 않았다. 따라서 생성된 검증 JAR을 최종 배포 앱으로 사용하지 않는다. 최종 UI 커밋 통합 뒤 프론트 테스트·타입·production build·정적 자산/라이선스 포함과 최종 app/migrate 이미지의 revision·ID 검증이 남아 있다. 후보 전체 Java 회귀·새 이미지 취약점/장시간 부하·서버 백업/복원·공개 HTTPS/PC·모바일 검증도 이번 선택 검증의 통과로 확대하지 않는다.
