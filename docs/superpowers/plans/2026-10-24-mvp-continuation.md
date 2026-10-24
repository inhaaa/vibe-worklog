# 고정 근무부터 MVP 연결 구현 계획

> 실행 방식: superpowers:subagent-driven-development. 사용자의 질문 없는 자율 진행 승인에 따라 계약을 먼저 기록하고 단계별로 검증한다.

**목표:** 최대2시간 범위에서 기존 MVP 업무 흐름을 순서대로 연결한다. 미완료 단위를 완료로 표시하지 않는다.

**구조:** 기존 Java25·Spring Boot·MyBatis·PostgreSQL과 관리 명령을 재사용한다. 직원 업무 경계는 현재 권한을 재확인하는 별도 트랜잭션으로 분리한다.

## 작업 단위

- [x] 고정 일정: `schedule/ScheduleData`, `ScheduleMapper`, `ScheduleService`, `ScheduleController`, V4·화면·스키마/서비스/웹 테스트. 계약은 [고정 일정](../../design/fixed-schedule.md).
- [x] 날짜별 휴일 근거·허용 CIDR·서버 시각 출퇴근·원천 보존·실제 휴게 확인.
- [x] 기초 연차 원장·종일 신청·1단계 결정·취소·정정과 효과/개정 원자성.
- [ ] 근로시간 계산·일/주/월 결과·미확정 표시·일반 검토용 Excel.
- [ ] 로컬 전체 흐름·권한/테넌트·Docker·문서·최종 잔여 수용 기준 검토.

## 각 단위의 절차

1. 명세에서 스키마·서비스·HTTP를 정의하고 담당 파일을 나눈다.
2. 실패 테스트를 먼저 실행한 뒤 구현하고 필요한 회귀를 추가한다.
3. `mvnw.cmd -B -s target/task-maven-settings.xml verify`로 단위/통합 테스트의 실패·오류·건너뜀을 확인한다.
4. 문서 링크·민감값·`git diff --check`·변경 범위를 확인하고 해당 단위만 커밋·일반 Push한다. 기본 main에도 fast-forward 반영한다.
5. 남은 시간을 확인하고 다음 단위로 이어간다. 종료 직전 미완성 단위를 무리하게 공개하지 않는다. 외부 서버·배포·실제 이메일 발송은 수행하지 않는다.

## 초기 실패 검증

```java
assertNotNull(admin.queryForObject("SELECT to_regclass('tna.schedule_template')::text", String.class));
```

```powershell
.\mvnw.cmd -B -s target/task-maven-settings.xml '-Dit.test=FixedScheduleSchemaIT' verify
```

V3에는 일정 테이블이 없으므로 위 명세 검증은 실패해야 한다. 이후 스키마·불변 버전·중첩 거부를 실제 PostgreSQL로 검증한다.

고정 일정 실행: 최초 schema NULL·GET403·서비스 부재 RED 후 신규27개+기존130개 검증 통과. 독립 리뷰 완료. 이미지·Compose는 마지막 통합 검증에서 재확인한다.

원천 출퇴근 실행: V5·설정/현재 직원 경계·원천·본인 확인을226개 테스트(단위30+통합196)로 검증했다. 실패 원인의 IP 단일 숫자 허용·MyBatis UUID 매핑을 수정했다. 이후 연차/승인 단위로 이어간다.

연차·승인 실행: V6·원장·신청·취소·정정·독립 승인·명시 재할당을268개 전체 테스트로 검증했다. 독립 리뷰의 일반 취소 승인 시작 경계·정정 증거 설정 버전 결속 누락을 실제 RED 후 수정했다.
