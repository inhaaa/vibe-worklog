# 휴일·허용 네트워크·원천 출퇴근 계약

2026-10-24 MVP 연속 구현 승인 범위다. [고정 일정](fixed-schedule.md), [보안](security.md), [계산](../rules/calculation.md), [신청](../rules/workflow.md)을 따른다.

## 설정과 원천

- 휴일은 연도별 휴일 표(`tna.holiday`: 날짜·휴일명·구분 PUBLIC/COMPANY/NON_WORKDAY·근거)로 관리한다(`/admin/holidays`). 저장하면 같은 명령 안에서 근태 달력(`calendar_version`+`calendar_day`)에 반영한다. 근태 달력이 없는 구간은 기간(PERIOD) 버전, 이미 있는 날 가운데 종류나 휴일명이 달라진 날은 날짜(DAY) 반영분을 만든다. 휴일 행이 없는 날은 근로일이며, 주말의 기존 비근로일은 근로일과 같게 본다. 기간끼리는 겹칠 수 없고, 한 날짜에는 revision이 가장 큰 버전을 쓴다(뷰 `tna.calendar_effective_day`). 마감 월은 반영하지 않는다. 근태 달력 행은 바꾸거나 지우지 않는다. 미등록 날짜는 계속 미확인이다.
- 허용망은 allowed_network(tenant/id,network cidr,label,valid_from,valid_to,reason,version,created_by)로 관리한다. 등록 즉시부터 유효하며 종료는 별도 감사 명령이다. CIDR은 숫자 IP만 파싱하고 IPv4/IPv6/mapped 비교를 정규화한다. /0과 IPv4 /8보다 넓은 범위, IPv6 /16보다 넓은 범위는 광범위 재확인이 필수다. 고객사 유효 중복 동일 CIDR은 거부한다.
- 직접 출퇴근은 로그인한 본인만 IN/OUT과 commandId를 보낸다. 서버 Instant를 밀리초로 기록하고 서울 날짜로 분류한다. 브라우저 시각/IP/tenant/employee·전달 헤더를 신뢰하지 않는다. 로컬 검증은 remoteAddr만 사용하며 실제 프록시 경계는 배포 전 별도 검증한다.
- attendance_event는 id,employee_id,event_kind,occurred_at/received_at,client_command_id,source,validated_ip,network_id,assigned_work_date를 추가만 한다. IP는 제한 증빙이며 화면·감사·로그·출력에 복제하지 않는다. 별도 명령의 중복·역전·자정 등도 원천 보존하고 정상값으로 선택하지 않는다.

## 사실·검토와 확인

- day_input_revision은 사원/날짜별 input_revision·review_revision·recalculation_required를 관리한다. 원천은 input만 증가한다. 확인은 review만 증가한다.
- work_confirmation은 employee/date/confirmed_input_revision/review_revision,source_fingerprint,실제 근로/휴게일치,확인자/시각,break_snapshot을 불변 저장한다. fingerprint는 실제 사건·일정버전·캘린더버전을 포함하므로 이후 설정 변경 때 옛 증거를 재사용하지 않는다.
- 확인은 정확한 같은날 IN<OUT 한 쌍과 유효 일정·명시 캘린더가 있어야 하며 실제근로·예정휴게 일치를 모두 명시해야 한다. 예정휴게가 실제 출퇴근 구간 밖이면 정정을 안내한다. 사실/검토 개정 불일치는409다. 확인 전 실근로는 null이다.
- 직원 업무는 TenantTransactions의 현재 계정·재직·auth_version·역할 재확인과 기존 tenant 잠금 순서를 사용하는 BusinessCommands 경계를 둔다. OrgCommands의 관리자 요구를 완화하지 않는다.
- Phase 6에서는 본인 조회와 담당자 예외 스냅샷 조회 모두 공통 read 경계의 시작 전·자료 반환 전 권한 확인을 적용한다. 테넌트 잠금 대기 중 계정·필수 역할 회수가 커밋되면 이미 시작한 조회도 403으로 끝내고 스냅샷을 반환하지 않는다. 상세 계약과 결정적 동시성 회귀는 [보안 설계](security.md)의 조회 중 권한 회수 경계를 따른다.
- 새 업무표는 tenant 복합FK·FORCE RLS·최소 INSERT/허용 열 UPDATE만 허용한다. 원천·확인·캘린더는 UPDATE/DELETE를 허용하지 않는다. 모든 명령은 감사·효과를 같은 트랜잭션에서 반영한다.

## 화면과 검증

/admin/holidays(옛 `/admin/calendar`는 303으로 이동), /admin/networks는 담당자 설정, /me/today와 /me/attendance는 본인 기록·확인이다. GET에는 변경 효과가 없다. 공개 JSON API·오프라인·GPS·QR·외부 프록시 설정은 추가하지 않는다.

RLS·권한·CIDR/위조 헤더·재시도·서버 시각·재직 회수·중복/자정·오래된 확인·원자적 감사 롤백을 테스트한다. 정상근로 미확정과 REVIEW_REQUIRED를 구분하고 법정 적합성으로 표현하지 않는다.
