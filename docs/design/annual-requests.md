# 본인 연도별 현황·모달 신청 — 5단계 계약

기준일: 2026-10-28, Asia/Seoul. 승인된 [확장 설계8절](../superpowers/specs/2026-10-26-worklog-service-design.md#8-연도별-조회와-신청-모달)을 React로 구현한다. 현재 정책·원장·독립 결재·근태코드 버전은 유지한다.

## 조회·수치

- `/me/requests?year&typeId&status&page&authoredPage`는 principal 본인 대상의 연도별 현황을 먼저 보여준다. 기본은 서울 올해, year는1900~9998, 기간은1월1일 포함~다음해1월1일 제외다. 기준은 대상 휴가일/근무일이며 제출일이 아니다.
- 연차 사용/예정은 현재 APPROVED·EFFECTIVE인 LEAVE의 날짜별 DAY를 선택 연도로 자른다. 오늘까지 사용, 내일부터 예정이며 예정도 이미 차감됐다. 대기는 LEAVE_DAY/PENDING의 DAY이고 취소 대기는 원연차 효력을 유지한다. 승인 후 취소·대체 효력은 기존 규칙을 따른다.
- 목록은 대상 기간이 연도와 교차하는 본인 신청50행, 근태유형/상태 필터는 목록에만 적용한다. 유형별 연간 신청 건수·상태 건수와 연차 DAY를 분리한다. 대리 작성한 타인 건은 별도50행 작성 이력이며 본인 집계에 넣지 않는다. 모든 상태는 조회 시점 현재 상태다.
- 현재 사용 가능은 선택 연도와 무관하게 오늘 사용 가능한 모든 본인 묶음의 현재 잔여 합이다. 선택 연말 잔여로 표시하지 않는다.
- 선택 연도의 등록·부여·이월·조정량은 최신 귀속이 CONFIRMED인 묶음의 OPENING, ADJUSTMENT, GRANT, CARRY_IN 합이다. 귀속연도 원장 잔액에는 이후 사용·복원·소멸도 반영한다. 직원은 오늘 사용 가능 잔여에서 개인 원장으로 이동해 적용일과 증감 수량을 확인한다. [회사 회계연도와 개인 원장](../superpowers/plans/2026-11-21-annual-leave-ledger.md)
- 미분류 묶음 수를 표시하고 base_date로 귀속연도를 추정하지 않는다. 미분류가 있으면 확인된 기초·잔액은 확인된 부분합이며 확인 자료가 없으면 귀속 미확인이다. 연도에 속한 신청/확정 귀속 묶음이 없으면 `NO_REGISTERED_DATA`, 있으면 `REGISTERED_DATA_ONLY`로 구분한다. 등록 자료의0과 연간 전체0·자동 발생0을 동일하게 표현하지 않는다.
- 조회는 공유 tenant 잠금·현재 EMPLOYEE·반환 전 권한 재확인 안에서 일관된 DTO를 만든다. tenant/employee는 입력으로 고를 수 없다. 관리자/대표도 이 경로에서는 본인만 조회한다.

## 귀속연도 이력·V15

- `leave_bucket_attribution`: tenant_id/id/bucket_id/revision/attendance_type_id/classification_status(CONFIRMED,UNCLASSIFIED)/entitlement_year?/basis/created_by/created_at. 복합 FK·유일한 bucket/revision·FORCE RLS·최소 SELECT/INSERT, UPDATE/DELETE 금지다. 기존 자료를 자동 분류하거나 원장을 변경하지 않는다.
- 관리자는 `/admin/leave-balances/buckets/{id}/attributions`에서 원장과 귀속 이력·근거를 조회하고 POST로 새 revision을 추가한다. expectedRevision(없으면0)·commandId·reason·basis, 확정 시 entitlementYear와 묶음 전체가 단일 귀속연도라는 확인을 요구한다. 미분류 revision은 연도NULL로 분류를 해제한다. tenant 독점 잠금·현재 관리자·멱등성·감사를 재사용한다. 실제 묶음은 기존 시스템 연차에만 연결하며 사용자 초안에 임의 매핑하지 않는다. 본인 묶음의 관리자 분류도 기존 독립 관리 원칙에 따라 차단한다.

## 신청 동작·호환

- 기본 화면에는 바로 제출하는 폼 대신 `신청` 버튼이 있다. native dialog에서 연차/정정을 선택하고 서버가 지원하는 유형만 신청한다. 아직 미지원 사용자 초안은 선택 불가다.
- 작성 context GET `/me/requests/new?year&kind&date&predecessorRequestId`는 쓰지 않는다. `request/mine`의 annual/compose DTO를 제공하고 mode=PAGE는 동일한 React 일반 작성 페이지다. native dialog 미지원 시 일반 폼으로 표시한다. [ADR-0003](../adr/0003-react-frontend.md)의 후속 결정에 따라 화면에는 JavaScript가 필요하며 최초 확장 설계의 무JS 전제를 새로 지원하지 않는다.
- 모달에는 제목/설명·입력 레이블·최초 초점·키보드 이동·Escape·입력 손실 확인·닫은 뒤 신청 버튼 초점 복귀를 제공한다. 정정은 일자 조회로 현재 사실 개정/fingerprint/유효 정정 참조를 서버에서 얻는다. 연차와 정정의 기존 서로 다른 DTO를 유지하며 UUID를 사용자에게 직접 입력시키지 않는다.
- CSRF POST `/me/requests/preview`는 공유 읽기로 예상 DAY·현재 잔여·실제 결재 경로/적격성·정책/오류를 보여준다. 신청·이벤트·감사·원장을 저장하지 않는다. 미리보기는 참고이며 최종 제출/승인에서 재검증한다. 미리보기 후 내용을 바꾸면 다시 확인한다.
- 기존 `/me/requests/leave`, `/me/requests/correction` POST를 사용한다. year 문맥을 보낸 새 UI는 성공303으로 선택 연도 현황에 복귀하고 생성 건 상세·기간 밖 대상연도 안내를 제공한다. year가 없는 기존 클라이언트의 상세303과 기존 일반 정정 GET은 호환 유지한다. 재신청은 소유한 반려/철회 원신청의 predecessor 검증을 유지한다.
-409/422는 year·kind·입력·commandId·모달 열림·오류를 유지하고 첫 오류/요약에 초점을 둔다.409는 최신값 확인 뒤 명시 재시도,503은 같은 명령/내용 보존, 제출 중 잠금·인증 만료 시 URL에 사유를 옮기지 않는다. 임의 다른 사원/tenant·지원 밖 유형·기초 묶음은 서버가 차단한다.

## 검증 경계

연도/윤일/오늘 경계·휴일0DAY·취소심사/완료·현재 잔여와 귀속 잔액·미분류/빈 자료·필터/50행·대리 작성 분리·귀속 revision/동시성·tenant/RLS/현재 권한·미리보기 무쓰기·실제 결재 경로·모달 접근성/입력 보존·기존 POST 호환을 테스트한다. V14→V15 자료/권한 보존·실제 HTTP·PC/모바일·문서·커밋·일반 Push를 수행한다. 월간 현황6단계·새 휴가 계산·자동 발생/이월/소멸은 포함하지 않는다. 공개 반영은 기존 전용 작업과 충돌을 피하고 실제 수행 여부를 별도 기록한다.
