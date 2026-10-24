# 계정 초대·역할·승인자 배정 구현 계획

> 실행: `superpowers:subagent-driven-development`. 사용자의 자율 진행 위임과 명시적 다음 단위 요청을 따라 문서→테스트→구현→검증→커밋·Push를 진행한다.

**목표:** [계정 접근 계약](../../design/account-access.md)에 따른 로컬 모의 초대·수락·역할·당일 승인자 배정을 완성한다.

**구조:** 기존 OrgCommands·TenantTransactions·PasswordPolicy를 재사용하고 Java 의존성을 추가하지 않는다. 비인증 수락은 별도 auth 트랜잭션이며 일반 업무 서비스와 경계를 분리한다.

## 1. 계약·최초 실패

- [x] 계정 접근 계약을 검토하고 AGENTS·CLAUDE·README·PRD에 다음 단위를 반영한다.
- [x] `AccountAccessSchemaIT`의 account_token 존재 검증, `AccountAccessWebIT`의 담당자 접근 GET200을 먼저 실행해 누락 테이블·미구현 경로 실패를 확인한다.

## 2. 스키마·인터페이스

- [x] `V3__account_access.sql`, `AccountAccessSchemaIT`: 계정 생성 열·역할 active·토큰·승인자 구간·RLS·최소 grants를 추가한다. V1/V2는 보존한다.
- [x] `org/AccountData.java`: Account·InvitationForm·RolesForm·ApproverAssignment·ApproverForm의 필드 계약을 정의한다.
- [x] AuthStore·OrgCommands·EmployeeAdminMapper의 현재 역할 조회에 active 필터를 적용한다. 기존 스키마 테스트의 단계별 권한 계약을 갱신한다.

## 3. 서비스 병렬 구현

- [x] `org/AccountAdminMapper.java`, `AccountService.java`, `common/auth/InvitationDelivery.java`, `InvitationAcceptance.java`, `AccountServiceIT.java`: pending 계정·재발급·모의 전달·역할·마지막 담당자·수락 원자성.
- [x] `org/ApproverMapper.java`, `ApproverService.java`, `ApproverServiceIT.java`: 오늘 지정/교체/해제·이력·현재 적격성·버전/명령 경합.
- [x] `AccountAccessSchemaIT`, `AccountServiceIT`, `ApproverServiceIT`를 실제 Testcontainers PostgreSQL에서 검증한다. 원문 토큰·해시·비밀번호가 업무 감사에 포함되지 않음을 확인한다.

## 4. 화면·HTTP

- [x] `org/AccountAdminController.java`, `common/auth/InvitationController.java`, `templates/org/access.html`, `templates/invite.html`, `AccountAccessWebIT.java`를 추가한다.
- [x] 사원 상세에서 접근 관리 링크를 제공하고 SecurityConfiguration·OrgErrorAdvice의 범위를 필요한 경로만 확장한다. 공개 수락에 CSRF·속도 제한·안전한 오류·수동 로그인 연결을 적용한다.
- [x] 계정 초대/재발급·수락·역할·승인자·422/409·CSRF·교차 tenant·세션 회수의 웹 흐름과 모의 전달을 검증한다.

## 5. 검증·전달

```powershell
.\mvnw.cmd -B -s target/task-maven-settings.xml verify
docker compose -f compose.yaml -f target/compose.network.yaml build
docker compose -f compose.yaml -f target/compose.network.yaml up --no-build -d
git diff --check
```

- [x] 독립 명세·보안 리뷰를 하고 발견한 결함은 실패 재현→수정→회귀 검증한다.
- [x] 전체 테스트·이미지 빌드·보존 V2 볼륨의 V3·로컬 HTTP를 확인하고 실제 결과를 README·개발 안내·PRD에 반영한다.
- 전달 절차: 문서 형식/링크·민감값·Git diff를 검사해 이번 단위를 커밋·일반 Push하고 main에 fast-forward 반영한다. 외부 서버·배포·메일 발송은 하지 않는다.

## 실행 결과 (2026-10-24)

- 최초 account_token 미존재·미구현 GET404와 서비스 동작 부재를 실제 실패로 확인한 뒤 구현했다. 독립 리뷰에서 오류 폼의 자기 지정 선택값 유실과 공개 수락 실패 보안 로그 누락을 재현 테스트로 확인하고 수정했다.
- Java25 Maven `verify`: 단위3+통합127=전체130개, 실패·오류·건너뜀0. 기존78개 통합 회귀와 신규 스키마9·계정 서비스14·승인자12·계정 웹14를 포함한다.
- Docker 앱/migrate 최종 이미지 빌드·Compose 기동 성공. 보존 V2 볼륨에 V3 적용·migrate 종료0, 앱 health UP. 최소3역할·FORCE RLS9·빈 고객/계정·loopback·uid10001·읽기 전용·비밀정보 분리를 확인했다.
- Compose HTTP14개와 별도 합성 DB 브라우저의 초대 발급→수락→수동 로그인→역할 저장→당일 승인자 저장·이력을 확인했다. 계정 관리390px 모바일은 페이지 전체 가로 넘침이 없었다.
- 새로운 라이브러리는 추가하지 않았다. 문서·링크·Git diff·민감값을 검토해 현재 단위를 전달한다. 다음 근무 템플릿 단위는 시작하지 않는다.
