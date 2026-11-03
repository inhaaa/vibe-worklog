# 결재 화면·두 데모 계정 로컬 검증

2026-11-03, Asia/Seoul. [설계](../superpowers/specs/2026-11-03-approval-workspace-design.md)·[계획](../superpowers/plans/2026-11-03-approval-workspace.md)의 개발본과 공개 통합본을 검증했다. 아래는 로컬 실행 결과다. 이후 공개 `3e6e99c`·V16 반영·두 persona60항목·공개 실제 신청→승인 결과는 [별도 보고](public-approval-workspace-deploy-20261103.md)에 기록했다.

## 변경과 계약

- `ApprovalWorkspace.tsx/.css`와 `WorkflowPages.tsx`/테스트·`RequestDetail.css`/`WorkflowTypes.ts`: 결재 대기/본인 완료 카드, 한글 유형·상태·효력·기간/일수·신청 검토, 페이지 범위 건수/빈 상태·이전/다음, 상세의 승인/반려 검토 패널을 적용했다. 기존 공개 시트와 `completed` 없는 관리 목록/fragment는 보존했다. 명시적 타인 신청·현재 APPROVER·승인/반려 상태에서만 완료함 복귀를 제공하며 처리 권한은 계속 서버 `canDecide`를 따른다.
- `AuthPages.tsx`/테스트·`DemoAccess.css`, `App.tsx`/테스트: 소개의 신청자/승인자 정상 로그인 폼과 상단 데모 계정 선택 링크를 추가했다. GET 선택만으로 인증을 바꾸지 않는다. 일반 업무에는 계정 전환 링크를 제공하지 않는다.
- `LocalDemoLogin`·provider·`DemoController`·view resolver·security와 테스트: 고정 admin/approver의 실제 DB 해시·tenant/account/employee·정확 역할을 검사한다. 승인자만 정상 인증 후 `/approvals`로 이동한다. PUBLIC의 단건/일괄·인코딩·인사·계정/역할 공유 보호는 두 UUID에 적용하며 일반 인증/CSRF/현재 세션 경계는 유지했다.
- `LocalDemoApprovalSetup.java`·`TnaApplication`의 명시 분기/신규 테스트: 서울2026-11-03·전용DB/제한된 migration 역할·RLS·고정 신원/감사/배정 부재를 확인한 뒤 계정1/역할2/직접 배정1/감사1의5행만 추가하고 admin 사원 version/수정 정보만 갱신한다. 재실행 무변경·충돌/감사 실패 전체 롤백·동시성·다른 기존 자료 보존을 확인했다. 새 마이그레이션/의존성/저장소 환경 설정 변경은 없다.

## 실행한 자동 검증

| 범위 | 결과 |
| --- | --- |
| 개발 프론트 | 19파일262개, 타입·Vite production 빌드 통과 |
| 공개 통합 프론트 | 22파일346개, 타입·Vite production 빌드 통과 |
| 개발 Java 전체 | JDK25.0.5 offline Maven verify, 단위132 + PostgreSQL 통합566 = 698개, 실패/오류/skip0 |
| 공개 통합 Java 전체 | 같은 Maven verify, 단위153 + PostgreSQL 통합610 = 763개, 실패/오류/skip0 |
| 공개 Python 도구 전체 | JDK25를 자식 실행에 지정한 unittest 74개 통과 |
| 신규 TDD | 결재 표시/전송·완료 복귀, 두 로그인 폼/전환 링크, persona 인증/모델·성공 경로·공유 보호, CLI 단위7건의 기능 부재 RED→GREEN 확인. CLI는 양쪽 각각 단위7 + 독립 PostgreSQL8 선택 통과 후 위 전체에도 포함 |
| 독립 검토 | auth/세션·공유 보호·UI와 실제 승인 서비스/자기 승인·버전/동일 명령까지 연결한 읽기 검토에서 미해결 P1/P2 발견0. CLI와 실제 전송/허용 변화는 별도로 확인 |

프론트 전체를 양쪽 동시에 실행한 첫 시도는 기존 50행/초점·뒤로가기 대기 테스트가 시간 초과/대기 오류로 실패했다. 해당 코드·기대값·시간 한도를 바꾸지 않고 개발 선택16개, 이후 개발/공개 전체를 순서대로 worker1로 실행해 위 결과를 확인했다. 실패 로그도 `target/approval-workspace-frontend-all.log`에 남겼다. Python 첫 시도는 테스트 자식에 프로젝트 JDK를 지정하지 않아 기존 호스트 JDK의 문법 오류가 났고, JDK25 지정 후74개를 통과했다. 호스트 전역 JDK/신뢰 설정을 바꾸지 않았다.

최종 로그는 양쪽 ignored `target/approval-workspace-{frontend,build,java}-final.log`, 공개 `target/approval-workspace-python-jdk25-final.log`다. 공개 Vite의 기존 큰 chunk 경고는 유지하며 임계값을 완화하지 않았다. Java는 이번에 전체를 실제 재실행했고 JAR도 함께 생성했다.

## 실제 화면·DB 흐름

새 owned `worklog_demo` PostgreSQL(384MiB/0.5CPU·loopback), `local,local-demo` 전용 앱18098에서 두 고정 persona·가상 사진/인사·재직·평일 일정/합성 달력·초기12DAY를 준비했다. 기존 개발/공개 DB·실제 개인정보는 사용하지 않았다. 일반 서비스의 자기 기초 등록 금지를 완화하지 않고 초기 합성 fixture를 별도로 준비했다. CLI 추가1회/재실행 무변경과 실제 dist=JAR=제공 JS/CSS SHA를 확인했다.

1. CUA로 정상 신청자 입장 → 업무 메뉴 → 11-04 하루/묶음/사유 → 서버 미리보기 → 실제 제출 → 접수 → 상세를 수행했다. 신청자에게 승인 버튼은 없었다.
2. 상단 데모 계정 전환 → 별도 승인자 정상 로그인 → 지정 대기함 → 신청 검토 → 검토 사유/실제 승인 → 저장된 승인 상세 → 본인 처리 완료함을 확인했다. 승인 후 결정 버튼은 없어지고 완료함 복귀가 표시됐다.
3. 신청자로 다시 정상 로그인해 본인 목록의 승인/현재 효력 유효, 사용 예정1DAY·대기0·오늘 사용 가능11DAY를 확인했다. 미래 승인 사용을 오늘까지의 사용으로 바꾸지 않았다. 미분류 귀속도 추정 확정하지 않았다.

| 시점 | 신청 | 처리 이벤트 | 원장 | 감사 | 연차 효력 | 잔여 |
| --- | --- | --- | --- | --- | --- | --- |
| 미리보기 전/후 | 0 | 0 | 1 | 2 | 0 | 12DAY |
| 실제 제출 후 | 1 PENDING | 1 SUBMITTED | 1 | 3 | 0 | 12DAY |
| 실제 승인 후 | 1 APPROVED | SUBMITTED + APPROVED | OPENING + USE -1 | 4 | 1 | 11DAY |

실제 requestId는 `ae58d812-59c1-4364-b43c-e7d51e3dd792`다. READ ONLY 사후 검증으로 제출/승인의 독립 actor·실제 사유/감사·USE가 정확1건임을 확인했고 원천 출퇴근은 그대로0이다. 대기 예약/차감, 가짜 접수·승인 화면, 중복 차감은 없었다.

PC1440/모바일390에서 확인·대기함·결재 검토·완료함·신청자 결과와 모바일 계정 선택의12회 관측에 가로 넘침0, 신규 결재·계정 전환 동작44px·콘솔 오류/경고0을 확인했다. 모바일 상세 전체 페이지 캡처는 도구가 실패해 보이는 영역 캡처로 저장했으며 승인 요청은 그 실패 후 화면 상태를 확인하고 한 번만 실행했다. 제품을 수정하거나 권한을 우회하지 않았다. helper의 초기 읽기 감사 시각 열 오기는 `occurred_at`으로 수정했고 실패한 SELECT는 자료를 바꾸지 않았다.

증거는 개발 작업트리 ignored `target/approval-workspace-ui-20261103/`의 `actual-approval-flow-proof.json`, `browser-proof.json`, `counts-{before-preview,after-preview,after-submit,after-approve}.json`, `ready-verification.json`과 `01-demo-personas-desktop.jpg`, `05-approval-inbox-{desktop,mobile}.jpg`, `06-approval-review-{desktop,mobile}.jpg`, `07-approved-detail-desktop.jpg`, `08-approval-completed-{desktop,mobile}.jpg`, `10-applicant-result-{desktop,mobile}.jpg`다. 전용 앱/DB 종료·포트 해제와 브라우저 뷰포트 복구를 확인했다.

## 범위와 남은 구분

V16 스키마·DB 권한/일반 인증·승인 계산 규칙·원천 기록은 변경하지 않았다. 새 장시간 부하·전체 취약점 스캔·실제 고객/개인정보/메일·상용 수용은 이번 범위가 아니다. 실제 공개 배포의 새 백업/독립 복원·허용5행/기존 자료와 서비스 보존·두 persona·실제 신청→승인은 위 별도 보고에서 검증 완료를 확인한다. CLI는 해당 고정일의 명시적 유지보수 도구이며 이후 날짜에 재실행하거나 자동 시작하지 않는다.
