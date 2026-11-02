import React from 'react';
import type { PageProps } from '../types';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import {
  Form,
  Field,
  Check,
  SelectField,
  TextAreaField,
  ErrorNotice,
  fmtDate,
  fmtTime,
  fmtDateTime,
} from '../ui';
import type {
  EmployeeChoice,
  Bucket,
  RequestView,
  RequestDetailModel,
  MineModel,
  ListModel,
  ClockModel,
  ClockEvent,
  CorrectionModel,
  BalancesModel,
  LedgerModel,
  LedgerEntry,
  MonthModel,
  DayView,
  PoliciesModel,
  ResultModel,
  AuditModel,
  AuditRow,
  AuditDetail,
  DaySnapshot,
} from './WorkflowTypes';

const hidden = (name: string, value: string | number | boolean | null | undefined) => (
  <input type="hidden" name={name} value={value == null ? '' : String(value)} />
);
const bucketPeriod = (b: Bucket) =>
  `${fmtDate(b.usableFrom)} ~ ${b.usableTo == null ? '종료 없음' : fmtDate(b.usableTo)}`;
const EmployeeOptions = ({ employees }: { employees: EmployeeChoice[] }) => (
  <>
    {employees.map((e) => (
      <option key={e.id} value={e.id}>
        {e.employeeNo} · {e.displayName}
      </option>
    ))}
  </>
);
const Reason = ({ label = '사유', value }: { label?: string; value?: string }) => (
  <TextAreaField label={label} name="reason" value={value} maxLength={500} required />
);
const Reasons = ({ reasons }: { reasons?: string[] }) => <>{(reasons ?? []).join(' · ')}</>;
const Summary = ({ entries }: { entries: Record<string, string> }) => (
  <dl>
    {Object.entries(entries ?? {}).map(([key, value]) => (
      <React.Fragment key={key}>
        <dt>{key}</dt>
        <dd>{value}</dd>
      </React.Fragment>
    ))}
  </dl>
);
const rawMillis = (value: number | null | undefined) => (value == null ? '미확정' : value);
function previousDay(iso: string): string {
  const value = new Date(`${iso}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() - 1);
  return value.toISOString().slice(0, 10);
}
function storedIntervals(value: string | null): string {
  if (value == null) return '미확인';
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed)) return '확인 필요';
    const intervals: string[] = [];
    for (const interval of parsed as unknown[]) {
      if (
        typeof interval !== 'object' ||
        interval == null ||
        !('startAt' in interval) ||
        !('endAt' in interval)
      )
        return '확인 필요';
      const { startAt, endAt } = interval;
      if (
        typeof startAt !== 'string' ||
        typeof endAt !== 'string' ||
        !/T.*(?:Z|[+-]\d\d:\d\d)$/.test(startAt) ||
        !/T.*(?:Z|[+-]\d\d:\d\d)$/.test(endAt)
      )
        return '확인 필요';
      const start = Date.parse(startAt),
        end = Date.parse(endAt);
      if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) return '확인 필요';
      intervals.push(`${fmtDateTime(startAt)} ~ ${fmtDateTime(endAt)}`);
    }
    return intervals.length ? intervals.join('; ') : '입력된 휴게 없음';
  } catch {
    return '확인 필요';
  }
}

function RequestRows({ requests }: { requests: RequestView[] }) {
  const columns = [
    sheetColumn<RequestView>({ field: 'targetEmployeeName', headerName: '대상', minWidth: 170, pinned: 'left' }, request => request.targetEmployeeName),
    sheetColumn<RequestView>({ field: 'kind', headerName: '종류', minWidth: 170 }, request => <a href={`/requests/${request.id}`}>{request.kind}</a>),
    sheetColumn<RequestView>({ field: 'fromDate', headerName: '기간', minWidth: 230, valueGetter: ({ data }) => data ? `${fmtDate(data.fromDate)} ~ ${fmtDate(data.toDate)}` : '' }, request => `${fmtDate(request.fromDate)} ~ ${fmtDate(request.toDate)}`),
    sheetColumn<RequestView>({ colId: 'state', headerName: '상태·효력', minWidth: 190, valueGetter: ({ data }) => data ? `${data.status} / ${data.effectStatus}` : '' }, request => `${request.status} / ${request.effectStatus}`),
    sheetColumn<RequestView>({ field: 'approverName', headerName: '승인자', minWidth: 150, flex: 1 }, request => request.approverName),
  ];
  return <WorklogSheet label="근태 신청 목록" rows={requests} columns={columns} getRowId={request => request.id} emptyMessage="신청이 없습니다." />;
}
function Mine({ model: m }: { model: MineModel }) {
  const f = m.form;
  return (
    <>
      <h1>내 근태 신청</h1>
      <p>
        <a href="/me/requests/correction">출퇴근·실제 휴게 정정 신청</a>
      </p>
      <section className="panel">
        <h2>연차 잔여와 대기량</h2>
        <p>정수 DAY 기초 잔여이며 자동 발생이 아닙니다. 대기는 예약·차감하지 않습니다.</p>
        <ul>
          {m.buckets.map((b) => (
            <li key={b.id}>
              {bucketPeriod(b)} · 현재 잔여 {b.balanceDays} / 사용 가능 {b.availableDays} / 대기{' '}
              {b.pendingDays} DAY
            </li>
          ))}
        </ul>
      </section>
      <section className="panel">
        <h2>종일 연차 신청</h2>
        <Form action="/me/requests/leave" method="post" className="admin-form">
          {hidden('commandId', f.commandId)}
          {f.predecessorRequestId && hidden('predecessorRequestId', f.predecessorRequestId)}
          <SelectField label="기초 묶음" name="bucketId" value={f.bucketId ?? ''} required>
            <option value="">선택하세요</option>
            {m.buckets.map((b) => (
              <option key={b.id} value={b.id}>
                {bucketPeriod(b)} / 사용 가능 {b.availableDays} DAY
              </option>
            ))}
          </SelectField>
          <Field label="시작일" name="from" type="date" value={f.from} required />
          <Field label="종료일 (포함)" name="to" type="date" value={f.to} required />
          <Reason value={f.reason} />
          <p className="hint">
            민감정보를 적지 마세요. 일정·휴일·유효기간은 승인 때 다시 검증합니다.
          </p>
          <button>연차 신청 제출</button>
        </Form>
      </section>
      <section className="panel">
        <h2>신청 이력</h2>
        <RequestRows requests={m.requests} />
      </section>
    </>
  );
}
function RequestDetailPage({ model: m }: { model: RequestDetailModel }) {
  const r = m.request,
    d = m.detail;
  const versionFields = (
    <>
      {hidden('expectedVersion', r.version)}
      {hidden('commandId', m.pageCommand)}
    </>
  );
  return (
    <>
      <h1>신청 상세</h1>
      <p>시각 기준: Asia/Seoul (서울)</p>
      <section className="panel">
        <h2>
          {r.targetEmployeeName} · {r.kind}
        </h2>
        <p>
          {fmtDate(r.fromDate)} ~ {fmtDate(r.toDate)} / {r.quantityDays} DAY
        </p>
        <p>
          {r.status} · 현재 효력 {r.effectStatus} / v{r.version}
        </p>
        <p>{r.reason}</p>
        <p>현재 결재 담당 {r.approverName}</p>
        {d.attendancePolicy && <section className="t-request-route">
          <h3>제출 당시 근태정책</h3>
          <dl>
            <dt>근태코드·버전</dt><dd>{d.attendancePolicy.code} · {d.attendancePolicy.name} · v{d.attendancePolicy.revision}</dd>
            <dt>정책 적용 시작</dt><dd>{fmtDate(d.attendancePolicy.effectiveFrom)}</dd>
            <dt>단위</dt><dd>{d.attendancePolicy.unit === 'DAY' ? '종일(DAY)' : d.attendancePolicy.unit === 'HOUR' ? '시간(HOUR)' : '해당 없음'}</dd>
            <dt>유급 구분</dt><dd>{d.attendancePolicy.payKind === 'PAID' ? '유급' : d.attendancePolicy.payKind === 'UNPAID' ? '무급' : '미확인'} (정책 메타데이터)</dd>
            <dt>차감 규칙</dt><dd>{d.attendancePolicy.deductionRule === 'LEAVE_DAY' ? '승인 시 연차 DAY 차감' : '차감 없음'}</dd>
            <dt>정책 근거</dt><dd>{d.attendancePolicy.basis}</dd>
          </dl>
          <p className="hint">제출 당시 불변 정책입니다. 이후 비활성·새 버전을 소급하지 않습니다. 근무 인정시간은 산정 미지원입니다.</p>
        </section>}
        {d.routeSnapshot ? <section className="t-request-route">
          <h3>제출 당시 결재선</h3>
          <dl>
            <dt>결재선 버전</dt><dd>{d.routeSnapshot.routeCode} · {d.routeSnapshot.routeName} · v{d.routeSnapshot.revision}</dd>
            <dt>적용 범위</dt><dd>{d.routeSnapshot.scopeKind === 'EMPLOYEE' ? '사원별' : '조직별'}</dd>
            <dt>결재 방식</dt><dd>{d.routeSnapshot.approverKind === 'ORGANIZATION_HEAD' ? '대상자의 현재 조직장' : '지정 사원'}</dd>
            {d.routeSnapshot.organizationName && <><dt>제출 당시 소속</dt><dd>{d.routeSnapshot.organizationName}</dd></>}
            <dt>최초 결재 담당</dt><dd>{d.routeSnapshot.resolvedApproverName}</dd>
            <dt>해석 시각 (서울)</dt><dd>{fmtDateTime(d.routeSnapshot.resolvedAt)}</dd>
          </dl>
          <p className="hint">제출 당시 해석은 보존됩니다. 이후 명시 재할당은 처리 이력에서 확인하세요.</p>
        </section> : <p className="hint">제출 당시 기존 사원별 지정 승인자를 사용한 신청입니다.</p>}
        {r.kind === 'CANCEL_LEAVE' && <p>취소 심사 중에는 원승인·차감이 유지됩니다.</p>}
        {r.originalRequestId && (
          <p>
            <a href={`/requests/${r.originalRequestId}`}>원신청</a>
          </p>
        )}
        <ul>
          {d.days.map((day, i) => (
            <li key={`${day.leaveDate}-${i}`}>
              {fmtDate(day.leaveDate)} · {day.quantityDays} DAY · {day.zeroReason}
            </li>
          ))}
        </ul>
        {d.correction && (
          <>
            <h3>요청한 정정</h3>
            <p>
              출근 {d.correction.inAction}
              {d.correction.inAction === 'SET' && ` ${fmtDateTime(d.correction.requestedInAt)}`} /
              퇴근 {d.correction.outAction}
              {d.correction.outAction === 'SET' && ` ${fmtDateTime(d.correction.requestedOutAt)}`}
            </p>
            <p>
              실제 휴게{' '}
              {d.correction.breaksConfirmed ? storedIntervals(d.correction.actualBreaks) : '미확인'}
            </p>
            <p>원천 기록은 보존하며 승인된 최신 정정 효과만 적용합니다.</p>
          </>
        )}
      </section>
      <section className="panel">
        <h2>결정·변경</h2>
        {m.canDecide && (
          <>
            <Form action={`/requests/${r.id}/approve`} method="post" className="admin-form">
              {versionFields}
              <Reason label="승인 검토 사유" />
              <button>독립 승인</button>
            </Form>
            <Form action={`/requests/${r.id}/reject`} method="post" className="admin-form">
              {versionFields}
              <Reason label="반려 사유" />
              <button className="secondary">반려</button>
            </Form>
          </>
        )}
        {m.canWithdraw && (
          <Form action={`/requests/${r.id}/withdraw`} method="post" className="admin-form">
            {versionFields}
            <Reason label="철회 사유" />
            <button className="secondary">대기 신청 철회</button>
          </Form>
        )}
        {m.canCancel && (
          <Form action={`/requests/${r.id}/cancellations`} method="post" className="admin-form">
            {versionFields}
            <Reason label="취소 요청 사유" />
            <p>
              예정 시작 전 본인 요청만 일반 취소를 지원합니다. 지난 날짜는 담당자 예외 경로가
              필요합니다.
            </p>
            <button className="secondary">취소 심사 요청</button>
          </Form>
        )}
        {m.admin && r.status === 'PENDING' && (
          <Form action={`/admin/requests/${r.id}/reassign`} method="post" className="admin-form">
            <h3>대기 건 명시 재할당</h3>
            {versionFields}
            <SelectField label="새 승인자" name="approverEmployeeId" required>
              {(m.candidates ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.displayName}
                </option>
              ))}
            </SelectField>
            <Reason label="재할당 사유" />
            <button>재할당 저장</button>
          </Form>
        )}
        {m.canCancelException && (
          <Form
            action={`/admin/requests/${r.id}/cancellation-exceptions`}
            method="post"
            className="admin-form"
          >
            <h3>과거·퇴사자 예외 취소</h3>
            {versionFields}
            <Reason label="검토 근거와 예외 취소 사유" />
            <p>다른 적격 승인자의 독립 승인이 필요합니다.</p>
            <button>예외 취소 심사 요청</button>
          </Form>
        )}
      </section>
      <section className="panel">
        <h2>처리 이력</h2>
        <ol>
          {d.history.map((h, i) => (
            <li key={i}>
              {fmtDateTime(h.createdAt)} · {h.eventKind} · {h.fromStatus} → {h.toStatus} ·{' '}
              {h.reason}
            </li>
          ))}
        </ol>
      </section>
    </>
  );
}
function Correction({ model: m }: { model: CorrectionModel }) {
  const f = m.form;
  return (
    <>
      <h1>출퇴근·실제 휴게 정정 신청</h1>
      <p>시각 기준: Asia/Seoul (서울)</p>
      {m.exceptional && (
        <p className="notice">
          담당자 예외 신청입니다. 작성자·대상자와 다른 적격 승인자의 독립 승인이 필요합니다.
        </p>
      )}
      <section className="panel">
        <h2>{fmtDate(m.day.workDate)} · 현재 원천</h2>
        <ul>
          {m.day.events.map((e) => (
            <li key={e.id}>
              {e.kind} · {m.eventTimes?.[e.id] ?? fmtDateTime(e.occurredAt)}
            </li>
          ))}
        </ul>
        <p>원천은 수정·삭제하지 않으며 승인된 정정 효과를 추가합니다.</p>
        <Form
          action={m.exceptional ? '/admin/requests/exceptions' : '/me/requests/correction'}
          method="post"
          className="admin-form"
        >
          {hidden('targetEmployeeId', f.targetEmployeeId)}
          {hidden('workDate', f.workDate)}
          {hidden('baseInputRevision', f.baseInputRevision)}
          {hidden('sourceFingerprint', f.sourceFingerprint)}
          {hidden('commandId', f.commandId)}
          {hidden('exceptional', m.exceptional)}
          {f.predecessorRequestId && hidden('predecessorRequestId', f.predecessorRequestId)}
          <SelectField label="출근 변경" name="inAction" value={f.inAction}>
            {m.actions.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </SelectField>
          <Field
            label="SET 출근 시각 (서울)"
            name="requestedInAt"
            type="datetime-local"
            step="0.001"
            value={f.requestedInAt}
          />
          <SelectField label="퇴근 변경" name="outAction" value={f.outAction}>
            {m.actions.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </SelectField>
          <Field
            label="SET 퇴근 시각 (서울)"
            name="requestedOutAt"
            type="datetime-local"
            step="0.001"
            value={f.requestedOutAt}
          />
          <p className="hint">
            KEEP은 현재 값을 유지하고 SET은 제안 시각, CLEAR는 누락 상태로 정정합니다. SET이 아니면
            시각을 비우세요.
          </p>
          <Check
            name="presenceAsWork"
            label="제안 구간의 실제 근로를 확인했습니다"
            checked={f.presenceAsWork}
          />
          <Check
            name="breaksConfirmed"
            label="실제 휴게를 확인했습니다 (없으면 아래 비움)"
            checked={f.breaksConfirmed}
          />
          <TextAreaField
            label="실제 휴게 구간 (시작/종료, 쉼표 구분)"
            name="actualBreaks"
            value={f.actualBreaks}
          />
          <Field
            label="변경할 현재 승인 정정 ID (있으면)"
            name="replacesRequestId"
            value={f.replacesRequestId ?? ''}
          />
          <Reason label="정정 사유와 사실 근거" value={f.reason} />
          <p className="hint">
            민감정보를 적지 마세요. 확인되지 않은 실제 근로는 정상0시간으로 간주하지 않습니다.
          </p>
          <button>정정 심사 요청</button>
        </Form>
      </section>
    </>
  );
}
function Balances({ model: m }: { model: BalancesModel }) {
  const f = m.form;
  return (
    <>
      <h1>기초 잔여 관리</h1>
      <Form action="/admin/leave-balances" method="get">
        <SelectField label="사원" name="employeeId" value={m.employee.id}>
          <EmployeeOptions employees={m.employees} />
        </SelectField>
        <button>조회</button>
      </Form>
      <p>
        <a href="/admin/requests">고객사 신청·예외 현황</a> ·{' '}
        <a href={`/admin/requests/exceptions/new?employeeId=${m.employee.id}`}>예외 정정 신청</a>
      </p>
      <section className="panel">
        <h2>{m.employee.displayName} · 기초 묶음</h2>
        <ul>
          {m.buckets.map((b) => (
            <li key={b.id}>
              <a href={`/admin/leave-balances/buckets/${b.id}`}>
                {bucketPeriod(b)} / 잔여 {b.balanceDays} DAY / 대기 {b.pendingDays} DAY
              </a>
            </li>
          ))}
        </ul>
      </section>
      <section className="panel">
        <h2>근거 있는 기초 등록</h2>
        <p>
          자동 발생이 아닙니다. 본인의 기초 등록·증감은 다른 담당자가 처리합니다. 소수 단위는
          지원하지 않습니다.
        </p>
        <Form action="/admin/leave-balances/entries" method="post" className="admin-form">
          {hidden('employeeId', f.employeeId)}
          {hidden('commandId', f.commandId)}
          <Field label="기초 기준일" name="baseDate" type="date" value={f.baseDate} required />
          <Field
            label="사용 가능 시작일"
            name="usableFrom"
            type="date"
            value={f.usableFrom}
            required
          />
          <Field
            label="사용 가능 종료일 (포함)"
            name="usableTo"
            type="date"
            value={f.usableTo ?? ''}
          />
          <Field
            label="기초 일수 (정수 DAY)"
            name="quantityDays"
            type="number"
            min="0"
            step="1"
            value={f.quantityDays ?? ''}
            required
          />
          <Field
            label="발생 근거 참조"
            name="sourceReference"
            maxLength={500}
            value={f.sourceReference}
            required
          />
          <Reason label="등록 사유" value={f.reason} />
          <button>기초 원장 등록</button>
        </Form>
      </section>
    </>
  );
}
function Ledger({ model: m }: { model: LedgerModel }) {
  const f = m.adjustment;
  const columns = [
    sheetColumn<LedgerEntry>({ field: 'entryKind', headerName: '종류', minWidth: 160, pinned: 'left' }, entry => entry.entryKind),
    sheetColumn<LedgerEntry>({ field: 'signedQuantityDays', headerName: 'DAY 증감', minWidth: 135, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, entry => entry.signedQuantityDays),
    sheetColumn<LedgerEntry>({ field: 'effectiveOn', headerName: '기준일', minWidth: 150 }, entry => fmtDate(entry.effectiveOn)),
    sheetColumn<LedgerEntry>({ colId: 'basis', headerName: '근거·사유', minWidth: 280, flex: 1, valueGetter: ({ data }) => data ? `${data.sourceReference} · ${data.reason}` : '' }, entry => `${entry.sourceReference} · ${entry.reason}`),
  ];
  return (
    <>
      <h1>연차 원장</h1>
      <p><a href={`/admin/leave-balances/buckets/${m.bucketId}/attributions`}>귀속연도 관리</a> · 분류는 원장 금액을 변경하지 않습니다.</p>
      <section className="panel">
        <p>추가한 이력은 덮어쓰지 않습니다. 사용·복원은 원효력을 참조합니다.</p>
        <WorklogSheet label="연차 원장" rows={m.ledger} columns={columns} getRowId={entry => entry.id} scope="all" />
      </section>
      <section className="panel">
        <h2>증감 조정</h2>
        <Form
          action={`/admin/leave-balances/buckets/${m.bucketId}/adjustments`}
          method="post"
          className="admin-form"
        >
          {hidden('expectedVersion', f.expectedVersion)}
          {hidden('commandId', f.commandId)}
          <Field
            label="조정 DAY (감액은 음수)"
            name="quantityDays"
            type="number"
            step="1"
            value={f.quantityDays ?? ''}
            required
          />
          <Field
            label="효력 기준일"
            name="effectiveOn"
            type="date"
            value={f.effectiveOn}
            required
          />
          <Field
            label="근거 참조"
            name="sourceReference"
            value={f.sourceReference}
            maxLength={500}
            required
          />
          <Reason label="조정 사유" value={f.reason} />
          <button>조정 원장 추가</button>
        </Form>
      </section>
    </>
  );
}

function Month({ model: m }: { model: MonthModel }) {
  const view = m.overview,
    summary = view.summary;
  const columns = [
    sheetColumn<DayView>({ field: 'workDate', headerName: '근무일', minWidth: 145, pinned: 'left' }, day => fmtDate(day.workDate)),
    sheetColumn<DayView>({ colId: 'state', headerName: '상태·최신성', minWidth: 230, valueGetter: ({ data }) => data ? `${data.result.status} / ${data.current ? '최신' : '미산정·재집계 필요'}` : '' }, day => `${day.result.status} / ${day.current ? '최신' : '미산정·재집계 필요'}`),
    sheetColumn<DayView>({ colId: 'actualTime', headerName: '실근로 (시:분:초.밀리초)', minWidth: 210, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter', valueGetter: ({ data }) => data?.result.actualWorkMillis ?? null, getQuickFilterText: ({ data }) => data ? m.actualTimes?.[data.workDate] ?? '미확정' : '' }, day => m.actualTimes?.[day.workDate] ?? '미확정'),
    sheetColumn<DayView>({ colId: 'actualWorkMillis', field: 'result.actualWorkMillis', headerName: '실근로 ms', minWidth: 150, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, day => rawMillis(day.result.actualWorkMillis)),
    sheetColumn<DayView>({ colId: 'confirmedBreakMillis', field: 'result.confirmedBreakMillis', headerName: '휴게 ms', minWidth: 135, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, day => rawMillis(day.result.confirmedBreakMillis)),
    sheetColumn<DayView>({ colId: 'over8hMillis', field: 'result.over8hMillis', headerName: '일8시간 초과 ms', minWidth: 175, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, day => rawMillis(day.result.over8hMillis)),
    sheetColumn<DayView>({ colId: 'leaveDays', field: 'result.leaveDays', headerName: '휴가 DAY', minWidth: 130, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, day => day.result.leaveDays),
    sheetColumn<DayView>({ colId: 'evidence', headerName: '근거·예외·버전', minWidth: 310, flex: 1, valueGetter: ({ data }) => data ? `${data.result.reasons.join(' · ')} · ${data.ruleVersion} · 결과 v${data.resultRevision} · ${data.id ?? ''} · 사실 ${data.result.inputRevision} / 검토 ${data.result.reviewRevision}` : '' }, day => <div className="reason-cell">
      <p><Reasons reasons={day.result.reasons} /></p>
      <p>{day.ruleVersion} · 결과 v{day.resultRevision} · {day.id}</p>
      {day.id && <p><a href={`/worktime/results/${day.id}`}>계산 근거 상세</a></p>}
      <p>사실 {day.result.inputRevision} / 검토 {day.result.reviewRevision}</p>
    </div>),
  ];
  const periodFields = (
    <>
      {hidden('employeeId', m.employeeId)}
      {hidden('from', m.from)}
      {hidden('to', m.to)}
    </>
  );
  return (
    <>
      <h1>일·주·월 근로시간 검토</h1>
      <p>
        법정 적합성·급여 확정 자료가 아닌 검토용 결과입니다. 미확정은0시간으로 바꾸지 않으며, 확인된
        부분합과 구분합니다.
      </p>
      <Form
        action={m.admin ? '/admin/attendance' : '/me/worktime'}
        method="get"
        className="admin-form"
      >
        <Field label="기준 월 (서울)" name="month" type="month" value={view.month} required />
        {m.admin && (
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        )}
        <button>조회</button>
      </Form>
      <section className="panel">
        <h2>월 요약</h2>
        <p>
          확인된 실근로 부분합 {m.totalTime ?? '미확정'} / 미확정 {summary.result.unconfirmedDays}일
        </p>
        <p>{summary.current ? '입력·규칙 최신' : '미산정 또는 재집계 필요'}</p>
        <p>
          <Reasons reasons={summary.result.reasons} />
        </p>
        <p>
          실제 휴게·일정·캘린더·법 적용 검토가 없는 결과는 확인이 필요합니다. 법정 최종 가산 연장은
          자동 확정하지 않습니다.
        </p>
      </section>
      <section className="panel">
        <h2>일 결과</h2>
        <WorklogSheet label="일별 계산 결과" rows={view.days} columns={columns} getRowId={day => day.workDate} scope="all" />
      </section>
      <section className="panel">
        <h2>월 경계를 포함한 주간 참고량</h2>
        <ul>
          {view.weeks.map((w) => (
            <li key={w.from}>
              {w.from} ~ {previousDay(w.to)} · 확인 부분합 {w.result.confirmedWorkMillis} ms /
              미확정 {w.result.unconfirmedDays}일 / 주40시간 초과{' '}
              {rawMillis(w.result.over40hMillis)} ms / {w.current ? '최신' : '재집계 필요'}
            </li>
          ))}
        </ul>
      </section>
      {m.admin && (
        <section className="panel">
          <h2>수동 재집계·검토용 Excel</h2>
          <p>
            <a href={`/admin/work-policies?employeeId=${m.employeeId}`}>주 기산점·적용 대상 검토</a>{' '}
            · <a href="/admin/audit">감사 이력</a>
          </p>
          <Form action="/admin/calculations" method="post" className="admin-form">
            {periodFields}
            {hidden('commandId', m.calculateCommand)}
            <Field label="재집계 사유" name="reason" maxLength={500} required />
            <button>월과 경계 주 재집계</button>
          </Form>
          <Form action="/admin/reports/exports" method="post" className="admin-form">
            {periodFields}
            {hidden('commandId', m.exportCommand)}
            <Field label="검토용 출력 사유" name="reason" maxLength={500} required />
            <p>
              결과 ID·입력/규칙 버전·필터를 고정한 검토 스냅샷입니다. 자유 사유·이메일·IP는 포함하지
              않습니다.
            </p>
            <button>검토용 XLSX 다운로드</button>
          </Form>
        </section>
      )}
    </>
  );
}
function Policies({ model: m }: { model: PoliciesModel }) {
  const p = m.policyForm,
    a = m.assessmentForm;
  return (
    <>
      <h1>적용 대상·주 기산점 검토</h1>
      <p>
        법률 적합성을 자동 인증하지 않습니다. 근거를 확인한 적용 대상과 기간만 명시하며 민감한 보호
        사유는 수집하지 않습니다. 변경은 새 불변 버전입니다.
      </p>
      <Form action="/admin/work-policies" method="get">
        <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
          <EmployeeOptions employees={m.employees} />
        </SelectField>
        <button>조회</button>
      </Form>
      <section className="panel">
        <h2>사업장 주 기산점</h2>
        <ul>
          {m.policies.map((r) => (
            <li key={r.id}>
              v{r.revision} · {fmtDate(r.validFrom)} ~{' '}
              {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)} · 월요일 일치검토{' '}
              {String(r.mondayWeekReviewed)} · {r.basis}
            </li>
          ))}
        </ul>
        <Form action="/admin/work-policies" method="post" className="admin-form">
          {hidden('commandId', p.commandId)}
          <Field label="적용 시작일" name="from" type="date" value={p.from} required />
          <Field label="종료일 (포함)" name="to" type="date" value={p.to} required />
          <Check
            label="실제 회사의 주 기산점이 월요일인 것을 확인했습니다"
            name="mondayWeekReviewed"
            checked={p.mondayWeekReviewed}
          />
          <TextAreaField label="검토 근거" name="basis" value={p.basis} maxLength={500} required />
          <Field label="등록 사유" name="reason" value={p.reason} maxLength={500} required />
          <button>기산점 검토 버전 저장</button>
        </Form>
      </section>
      <section className="panel">
        <h2>사원별 일반 규정 적용 검토</h2>
        <ul>
          {m.assessments.map((r) => (
            <li key={r.id}>
              v{r.revision} · {fmtDate(r.validFrom)} ~{' '}
              {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)} · 일반규정 검토{' '}
              {String(r.generalRuleReviewed)} · {r.basis}
            </li>
          ))}
        </ul>
        <Form action="/admin/work-assessments" method="post" className="admin-form">
          {hidden('employeeId', a.employeeId)}
          {hidden('commandId', a.commandId)}
          <Field label="적용 시작일" name="from" type="date" value={a.from} required />
          <Field label="종료일 (포함)" name="to" type="date" value={a.to} required />
          <Check
            label="성인 통상 고정 근무의 일반 규정 적용을 검토했습니다"
            name="generalRuleReviewed"
            checked={a.generalRuleReviewed}
          />
          <p>
            단시간·보호 대상·특례·적용 제외 등 별도 검토가 필요하거나 미확인이라면 체크하지 마세요.
            구체적인 민감정보를 적지 않습니다.
          </p>
          <TextAreaField
            label="최소 검토 근거 참조"
            name="basis"
            value={a.basis}
            maxLength={500}
            required
          />
          <Field label="등록 사유" name="reason" value={a.reason} maxLength={500} required />
          <button>적용 검토 버전 저장</button>
        </Form>
      </section>
    </>
  );
}
function EventTable({ events, label }: { events: ClockEvent[] | null; label: string }) {
  const columns = [
    sheetColumn<ClockEvent>({ field: 'id', headerName: '사건 ID', minWidth: 200, pinned: 'left' }, event => event.id),
    sheetColumn<ClockEvent>({ field: 'kind', headerName: '종류', minWidth: 100 }, event => event.kind),
    sheetColumn<ClockEvent>({ field: 'occurredAt', headerName: '발생 시각 (서울)', minWidth: 240, flex: 1, valueGetter: ({ data }) => data ? fmtDateTime(data.occurredAt) : '' }, event => fmtDateTime(event.occurredAt)),
  ];
  return <WorklogSheet label={label} rows={events ?? []} columns={columns} getRowId={event => event.id} scope="all" />;
}
function FrozenClock({ clock: c }: { clock: DaySnapshot }) {
  const confirmation = c.confirmation;
  return (
    <>
      <section className="panel">
        <h2>당시 일정·휴일</h2>
        <dl>
          <dt>일정 버전 ID</dt>
          <dd>{c.scheduleVersionId ?? '일정 미확인'}</dd>
          <dt>휴일 캘린더 버전 ID</dt>
          <dd>{c.calendarVersionId ?? '캘린더 미확인'}</dd>
          <dt>일자 종류</dt>
          <dd>{c.dayKind}</dd>
        </dl>
        {c.schedule && (
          <>
            <p>{c.schedule.workday ? '소정 근무일' : '소정 비근무일'}</p>
            <p>
              예정 시작·종료 {fmtTime(c.schedule.startTime)} / {fmtTime(c.schedule.endTime)}
            </p>
            <h3>예정 휴게</h3>
            <ul>
              {c.schedule.breaks.map((b, i) => (
                <li key={i}>
                  {fmtTime(b.startTime)} ~ {fmtTime(b.endTime)}
                </li>
              ))}
            </ul>
            <p>예정 휴게를 실제 확인 휴게로 자동 간주하지 않습니다.</p>
          </>
        )}
      </section>
      <section className="panel">
        <h2>당시 원천과 유효 출퇴근</h2>
        <p>
          원천은 보존하며 승인된 정정의 유효 사건을 별도로 보여 줍니다. IP·인증 원문은 표시하지
          않습니다.
        </p>
        <h3>원천 사건</h3>
        {c.events.length === 0 && <p>저장 당시 원천 사건 없음</p>}
        <EventTable events={c.events} label="당시 원천 출퇴근" />
        <h3>유효 사건</h3>
        {!c.effectiveEvents?.length && <p>저장 당시 유효 사건 없음 또는 근거 확인 필요</p>}
        <EventTable events={c.effectiveEvents} label="당시 유효 출퇴근" />
        <h3>실제 근로·휴게 확인</h3>
        {confirmation ? (
          <>
            <dl>
              <dt>확인 ID</dt>
              <dd>{confirmation.id}</dd>
              <dt>확인 대상 사실·검토 개정</dt>
              <dd>
                {confirmation.confirmedInputRevision} / {confirmation.reviewRevision}
              </dd>
              <dt>실제 근로·휴게 일치 판단</dt>
              <dd>
                {String(confirmation.presenceAsWork)} / {String(confirmation.breaksMatch)}
              </dd>
              <dt>확인 시각</dt>
              <dd>{fmtDateTime(confirmation.confirmedAt)}</dd>
            </dl>
            {confirmation.breaks.length === 0 && <p>확인된 휴게 구간 없음</p>}
            <ul>
              {confirmation.breaks.map((b, i) => (
                <li key={i}>
                  {fmtDateTime(b.startAt)} ~ {fmtDateTime(b.endAt)}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p>저장된 실제 근로·휴게 확인 근거 없음</p>
        )}
      </section>
    </>
  );
}
function Result({ model: m }: { model: ResultModel }) {
  const d = m.detail,
    day = d.day,
    run = d.run;
  const times = m.times ?? {
    소정근로: '미확정',
    체류: '미확정',
    '확인 실근로': '미확정',
    '확인 휴게': '미확정',
    '일정 밖': '미확정',
    '일8시간 초과 참고': '미확정',
    야간: '미확정',
    휴일: '미확정',
    '법정 최종 연장': '미확정',
  };
  return (
    <>
      <nav className="actions" aria-label="상세에서 목록으로">
        {m.workspacePrincipal?.roles.includes('EMPLOYEE') && (
          <a className="t-btn secondary" href={`/me/worktime?month=${m.month}`}>
            내 근로시간
          </a>
        )}
        {m.admin && (
          <a
            className="t-btn secondary"
            href={`/admin/attendance?employeeId=${day.employeeId}&month=${m.month}`}
          >
            사원 월 현황
          </a>
        )}
      </nav>
      <h1>계산 근거 상세</h1>
      <p>
        이 결과 ID에 저장된 계산 당시의 근거입니다. 현재 입력으로 바꾸거나 재집계하지 않습니다.
        법률·급여 확정 자료가 아닌 검토용 결과입니다.
      </p>
      <p>
        시각 기준: Asia/Seoul (UTC+09:00) · 시간량: 시:분:초.밀리초. 날짜와 실제 발생 시각은
        구분합니다.
      </p>
      <section className="panel">
        <h2>결과와 최신성</h2>
        <p>
          {day.employeeNo} · {day.employeeName} / {fmtDate(day.workDate)}
        </p>
        <p>사번·이름은 현재 사원 정보이며 아래 업무 근거는 계산 당시 값입니다.</p>
        <dl>
          <dt>결과 ID</dt>
          <dd>{day.id}</dd>
          <dt>결과 개정·규칙</dt>
          <dd>
            v{day.resultRevision} · {day.ruleVersion}
          </dd>
          <dt>상태</dt>
          <dd>{day.result.status}</dd>
          <dt>사실·검토 개정</dt>
          <dd>
            {day.result.inputRevision} / {day.result.reviewRevision}
          </dd>
          <dt>계산 시각</dt>
          <dd>{fmtDateTime(day.calculatedAt)}</dd>
        </dl>
        <p>{day.current ? '현재 입력 근거와 일치' : '현재 입력 근거가 변경됨 · 재집계 필요'}</p>
        <p>
          {d.latestVersion ? '이 날짜의 최신 계산 버전' : '과거 계산 버전 · 이후 계산 결과 있음'}
        </p>
        {!d.latestVersion && d.latestResultId && (
          <p>
            <a href={`/worktime/results/${d.latestResultId}`}>최신 계산 결과</a>
          </p>
        )}
        {!d.completeEvidence && (
          <p className="notice error" role="status">
            저장된 근거가 없거나 일부를 확인할 수 없습니다. 현재 원천으로 대신 채우지 않았습니다.
            담당자 확인이 필요합니다.
          </p>
        )}
        <ul>
          {day.result.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      </section>
      <section className="panel">
        <h2>저장된 산정 결과</h2>
        <Summary entries={times} />
        <dl>
          <dt>종일 연차</dt>
          <dd>{day.result.leaveDays} DAY</dd>
        </dl>
        <p>
          미확정은 실제0시간과 다릅니다. 야간·휴일·초과 참고량을 더해 총 실근로로 만들지 않습니다.
        </p>
      </section>
      {run && (
        <section className="panel">
          <h2>계산 실행</h2>
          <dl>
            <dt>실행 ID</dt>
            <dd>{run.id}</dd>
            <dt>실행 상태·엔진</dt>
            <dd>
              {run.status} · {run.engineVersion}
            </dd>
            <dt>요청한 기간 (양끝 포함)</dt>
            <dd>
              {fmtDate(run.fromDate)} ~ {fmtDate(run.toDate)}
            </dd>
            <dt>경계 주 포함 기간 (양끝 포함)</dt>
            <dd>
              {fmtDate(run.expandedFrom)} ~ {fmtDate(run.expandedTo)}
            </dd>
            <dt>요청 계정 ID</dt>
            <dd>{run.requestedBy}</dd>
            <dt>실행 시작·종료 시각</dt>
            <dd>
              {fmtDateTime(run.startedAt)} / {fmtDateTime(run.finishedAt)}
            </dd>
          </dl>
        </section>
      )}
      {d.clock ? (
        <FrozenClock clock={d.clock} />
      ) : (
        <section className="panel">
          <h2>당시 일정·휴일</h2>
          <p>저장 당시 일정·휴일 근거 확인 필요</p>
        </section>
      )}
      <section className="panel">
        <h2>당시 정책·적용 검토·재직 근거</h2>
        <p>원문 자유 근거·민감한 보호 사유는 표시하지 않습니다.</p>
        {d.policy ? (
          <>
            <h3>주 기산점 검토</h3>
            <p>
              {d.policy.id} · v{d.policy.revision}
            </p>
            <p>
              {fmtDate(d.policy.validFrom)} ~{' '}
              {d.policy.validTo == null ? '종료일 없음' : fmtDate(d.policy.validTo)} (종료일이
              있으면 미포함)
            </p>
            <p>
              {d.policy.mondayWeekReviewed ? '월요일 주 기산점 일치 검토됨' : '주 기산점 미확인'}
            </p>
          </>
        ) : (
          <p>저장 당시 주 기산점 검토 미확인</p>
        )}
        {d.assessment ? (
          <>
            <h3>일반 규정 적용 검토</h3>
            <p>
              {d.assessment.id} · v{d.assessment.revision}
            </p>
            <p>
              {fmtDate(d.assessment.validFrom)} ~{' '}
              {d.assessment.validTo == null ? '종료일 없음' : fmtDate(d.assessment.validTo)}{' '}
              (종료일이 있으면 미포함)
            </p>
            <p>
              {d.assessment.generalRuleReviewed ? '일반 규정 적용 검토됨' : '일반 규정 적용 미확인'}
            </p>
          </>
        ) : (
          <p>저장 당시 일반 규정 적용 미확인</p>
        )}
        {d.employment ? (
          <>
            <h3>재직기간</h3>
            <p>
              {fmtDate(d.employment.joinedOn)} ~{' '}
              {d.employment.leftOn == null
                ? '당시 재직 종료일 미등록'
                : fmtDate(d.employment.leftOn)}{' '}
              (마지막 재직일이 있으면 포함)
            </p>
          </>
        ) : (
          <p>저장 당시 재직기간 근거 확인 필요</p>
        )}
      </section>
      <section className="panel">
        <h2>당시 신청·효력 참조</h2>
        <h3>승인 연차 효력 ID</h3>
        {d.leaveEffects.length === 0 && <p>저장된 승인 연차 참조 없음 또는 근거 확인 필요</p>}
        <ul>
          {d.leaveEffects.map((id) => (
            <li key={id}>{id}</li>
          ))}
        </ul>
        <h3>대기 정정 신청 ID</h3>
        {d.pendingCorrections.length === 0 && <p>저장된 대기 정정 참조 없음 또는 근거 확인 필요</p>}
        <ul>
          {d.pendingCorrections.map((id) => (
            <li key={id}>{id}</li>
          ))}
        </ul>
      </section>
    </>
  );
}

function Audit({ model: m }: { model: AuditModel }) {
  const columns = [
    sheetColumn<AuditRow>({ field: 'at', headerName: '서울 시각', minWidth: 230, pinned: 'left', valueGetter: ({ data }) => data ? fmtDateTime(data.at) : '' }, row => fmtDateTime(row.at)),
    sheetColumn<AuditRow>({ field: 'action', headerName: '행위·상세', minWidth: 180 }, row => <a href={`/admin/audit/${row.id}`}>{row.action}</a>),
    sheetColumn<AuditRow>({ colId: 'target', headerName: '대상', minWidth: 260, valueGetter: ({ data }) => data ? `${data.targetKind} · ${data.targetId}` : '' }, row => `${row.targetKind} · ${row.targetId}`),
    sheetColumn<AuditRow>({ field: 'actorAccountId', headerName: '처리 계정', minWidth: 220 }, row => row.actorAccountId),
    sheetColumn<AuditRow>({ field: 'reason', headerName: '사유', minWidth: 240, flex: 1 }, row => row.reason),
  ];
  return (
    <>
      <h1>고객사 감사 이력</h1>
      <p>
        열람 자체를 감사합니다. 현재 담당자 권한으로 소속 고객사의 감사만 확인합니다. 시각 기준은
        Asia/Seoul (UTC+09:00)입니다.
      </p>
      <p>
        이전·다음은 조회 시점 원장 기준이며 전체 원장의 고정 스냅샷을 보장하지 않습니다. 동일
        시각에는 감사 ID로 순서를 구분합니다.
      </p>
      <section className="panel">
        <h2>조회된 기록 {m.audits.length}건 (최대50건)</h2>
        {m.audits.length === 0 && (
          <p>이 범위에 감사 기록이 없습니다. 최신 목록에서 다시 확인할 수 있습니다.</p>
        )}
        <WorklogSheet label="고객사 감사 목록" rows={m.audits} columns={columns} getRowId={row => row.id} />
        <nav aria-label="감사 페이지">
          {m.page.previousCursor && (
            <a href={`/admin/audit?after=${m.page.previousCursor}`}>이전 페이지 (더 최근)</a>
          )}{' '}
          <a href="/admin/audit">최신 목록</a>{' '}
          {m.page.nextCursor && (
            <a href={`/admin/audit?before=${m.page.nextCursor}`}>다음 페이지 (더 과거)</a>
          )}
        </nav>
      </section>
      <p>
        비밀번호·토큰·이메일·IP와 저장 JSON 원문은 제공하지 않습니다. 상세에서는 제한된 전후 업무
        요약을 확인할 수 있습니다.
      </p>
    </>
  );
}
function AuditDetailPage({ audit: a }: { audit: AuditDetail }) {
  return (
    <>
      <h1>감사 상세</h1>
      <p>같은 고객사의 기록이며 열람 자체도 감사에 남깁니다. 시각 기준: Asia/Seoul (UTC+09:00).</p>
      <section className="panel">
        <h2>행위와 추적 정보</h2>
        <dl>
          <dt>감사 ID</dt>
          <dd>{a.id}</dd>
          <dt>행위</dt>
          <dd>{a.action}</dd>
          <dt>대상</dt>
          <dd>
            {a.targetKind} · {a.targetId}
          </dd>
          <dt>처리 계정 ID</dt>
          <dd>{a.actorAccountId}</dd>
          <dt>서울 시각</dt>
          <dd>{fmtDateTime(a.at)}</dd>
          <dt>명령 ID</dt>
          <dd>{a.commandId}</dd>
          <dt>요청 추적 ID</dt>
          <dd>{a.requestTraceId}</dd>
          <dt>처리 사유</dt>
          <dd className="reason-cell">{a.reason}</dd>
        </dl>
      </section>
      <section className="panel">
        <h2>변경 전 업무 요약</h2>
        {Object.keys(a.beforeSummary ?? {}).length === 0 && (
          <p>표시 가능한 업무 요약이 없습니다.</p>
        )}
        <Summary entries={a.beforeSummary} />
      </section>
      <section className="panel">
        <h2>변경 후 업무 요약</h2>
        {Object.keys(a.afterSummary ?? {}).length === 0 && <p>표시 가능한 업무 요약이 없습니다.</p>}
        <Summary entries={a.afterSummary} />
      </section>
      <p>
        버전·상태·역할·기간·수량·참조 ID 등 알려진 업무 필드만 타입을 검증해 표시합니다. 자유 형식
        이름·코드는 값 생략으로 표시하고, 비밀번호·토큰·이메일·IP·자유 근거·미등록 필드와 JSON
        원문은 보여 주지 않습니다. 요약은 전체 저장 원문의 재현이 아닙니다.
      </p>
      <p>
        <a href="/admin/audit">최신 감사 목록으로</a>
      </p>
    </>
  );
}

export function AttendanceRecord({ data }: { data: Record<string, unknown> }): React.ReactNode {
  const m = data as unknown as ClockModel,
    d = m.day,
    f = m.confirmationForm;
  const columns = [
    sheetColumn<ClockEvent>({ field: 'kind', headerName: '원천 사건', minWidth: 150, pinned: 'left', valueGetter: ({ data }) => data ? data.kind === 'IN' ? '출근' : '퇴근' : '' }, event => event.kind === 'IN' ? '출근' : '퇴근'),
    sheetColumn<ClockEvent>({ field: 'occurredAt', headerName: '서버 저장 시각 (서울)', minWidth: 250, flex: 1, valueGetter: ({ data }) => data ? m.eventTimes?.[data.id] ?? fmtDateTime(data.occurredAt) : '' }, event => m.eventTimes?.[event.id] ?? fmtDateTime(event.occurredAt)),
  ];
  const changedEvents =
    d.effectiveEvents != null &&
    (d.events.length !== d.effectiveEvents.length ||
      d.events.some((e, i) => {
        const effective = d.effectiveEvents?.[i];
        return (
          e.id !== effective?.id ||
          e.kind !== effective?.kind ||
          e.occurredAt !== effective?.occurredAt
        );
      }));
  return (
    <section className="panel">
      <h2>출근 기록·실제 근로 확인</h2>
      <p>
        {fmtDate(d.workDate)} · Asia/Seoul · 사실 v{d.inputRevision} / 검토 v{d.reviewRevision}
      </p>
      {d.schedule == null ? (
        <p className="notice">근무 일정 미배정 · 확인 필요</p>
      ) : (
        <p>
          {d.schedule.workday
            ? `예정 근무 ${fmtTime(d.schedule.startTime)}~${fmtTime(d.schedule.endTime)}`
            : '소정 비근로일'}
        </p>
      )}
      <p>캘린더: {d.dayKind}</p>
      <p>
        체류시간을 실제 근로로 자동 확정하지 않습니다. 중복·역전·자정 통과 기록은 정정 검토가
        필요합니다.
      </p>
      <WorklogSheet label="원천 출퇴근 기록" rows={d.events} columns={columns} getRowId={event => event.id} scope="all" />
      {changedEvents && (
        <div className="notice">
          <h3>승인 정정 반영값 · 원천은 별도 보존</h3>
          <ul>
            {d.effectiveEvents?.map((e) => (
              <li key={e.id}>
                {e.kind} · {m.eventTimes?.[e.id] ?? fmtDateTime(e.occurredAt)}
              </li>
            ))}
          </ul>
        </div>
      )}
      {d.confirmation && (
        <p className="notice">현재 사실의 실제 근로·휴게 일치 확인이 저장되었습니다.</p>
      )}
      <Form action="/me/work-confirmations" method="post" className="admin-form">
        {hidden('workDate', f.workDate)}
        {hidden('baseInputRevision', f.baseInputRevision)}
        {hidden('expectedReviewRevision', f.expectedReviewRevision)}
        {hidden('sourceFingerprint', f.sourceFingerprint)}
        {hidden('commandId', f.commandId)}
        <h3>퇴근 후 본인 확인</h3>
        <p>
          원천 한 쌍·같은 날짜·일정·캘린더가 유효해야 합니다. 실제가 다르면 확인하지 말고 정정
          신청을 사용하세요.
        </p>
        {d.schedule && (
          <ul>
            {d.schedule.breaks.map((b, i) => (
              <li key={i}>
                예정 휴게 {fmtTime(b.startTime)} ~ {fmtTime(b.endTime)}
              </li>
            ))}
          </ul>
        )}
        <label className="checkbox-label">
          <input
            type="checkbox"
            name="presenceAsWork"
            value="true"
            defaultChecked={f.presenceAsWork}
            required
          />
          출퇴근 사이에서 휴게를 제외한 실제 근로가 일치합니다
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            name="breaksMatch"
            value="true"
            defaultChecked={f.breaksMatch}
            required
          />
          실제 휴게가 표시된 예정 휴게와 일치합니다
        </label>
        <button type="submit">현재 사실 확인 저장</button>
      </Form>
    </section>
  );
}

export function WorkflowPages(props: PageProps): React.ReactNode | null {
  const { page, data } = props;
  let content: React.ReactNode;
  switch (page) {
    case 'request/mine':
      if (data.annual) return null;
      content = <Mine model={data as unknown as MineModel} />;
      break;
    case 'request/list': {
      const m = data as unknown as ListModel;
      content = (
        <>
          <h1>{m.title}</h1>
          {m.completed !== undefined && <><nav className="actions" aria-label="결재 목록"><a href="/approvals" aria-current={!m.completed ? 'page' : undefined}>결재 대기함</a><a href="/approvals/completed" aria-current={m.completed ? 'page' : undefined}>내가 처리한 결재</a></nav><p>{m.completed ? '본인이 승인하거나 반려한 신청입니다. 철회는 처리한 결재에 포함하지 않습니다.' : '현재 본인에게 지정된 대기 신청입니다.'}</p></>}
          <section className="panel">
            <RequestRows requests={m.requests} />
          </section>
          {m.page !== undefined && <nav className="actions pagination" aria-label="결재 페이지"><span>{m.page + 1}페이지</span>{m.page > 0 && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page - 1}`}>이전</a>}{m.hasNext && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page + 1}`}>다음</a>}</nav>}
        </>
      );
      break;
    }
    case 'request/rows':
      content = <RequestRows requests={(data as unknown as ListModel).requests} />;
      break;
    case 'request/detail':
      content = <RequestDetailPage model={data as unknown as RequestDetailModel} />;
      break;
    case 'request/correction':
      content = <Correction model={data as unknown as CorrectionModel} />;
      break;
    case 'request/balances':
      content = <Balances model={data as unknown as BalancesModel} />;
      break;
    case 'request/ledger':
      content = <Ledger model={data as unknown as LedgerModel} />;
      break;
    case 'worktime/month':
      content = <Month model={data as unknown as MonthModel} />;
      break;
    case 'worktime/policies':
      content = <Policies model={data as unknown as PoliciesModel} />;
      break;
    case 'worktime/result':
      content = <Result model={data as unknown as ResultModel} />;
      break;
    case 'worktime/audit':
      content = <Audit model={data as unknown as AuditModel} />;
      break;
    case 'worktime/audit-detail':
      content = <AuditDetailPage audit={data.audit as AuditDetail} />;
      break;
    case 'clock/day':
      content = <AttendanceRecord data={data} />;
      break;
    case 'clock/history':
      content = (
        <>
          <header className="t-pagehead"><div><p className="t-eyebrow">ATTENDANCE</p><h1>출퇴근 기록</h1><p className="t-muted">출퇴근 시각과 실제 근로를 확인하세요.</p></div></header>
          {(data as unknown as ClockModel).isToday && (data as unknown as ClockModel).day.workDate === data.today && <section className="t-clock-actions t-card" aria-label="오늘 출퇴근">
            <div><span className="t-clock-indicator" aria-hidden="true" /><strong>오늘의 근무</strong><p className="t-muted">서울 현재 시각으로 기록됩니다.</p></div>
            <div className="t-clock-buttons">{(['IN', 'OUT'] as const).map(kind => <Form key={kind} action="/me/attendance/events">
              {hidden('eventKind', kind)}{hidden('commandId', kind === 'IN' ? data.inCommand as string : data.outCommand as string)}
              <button className={`t-btn${kind === 'IN' ? ' primary' : ''}`} disabled={!(kind === 'IN' ? data.inCommand : data.outCommand)}>{kind === 'IN' ? '출근 기록' : '퇴근 기록'}</button>
            </Form>)}</div>
          </section>}
          <Form action="/me/attendance" method="get" className="t-attendance-filter search-form">
            <Field
              label="기준일"
              name="date"
              type="date"
              value={(data as unknown as ClockModel).day.workDate}
              required
            />
            <button className="t-btn">조회</button>
            <a className="t-btn" href="/me/attendance">오늘</a>
          </Form>
          <AttendanceRecord data={data} />
        </>
      );
      break;
    default:
      return null;
  }
  return (
    <div className="t-legacy-page content admin-shell">
      <ErrorNotice message={typeof data.error === 'string' ? data.error : undefined} />
      {content}
    </div>
  );
}
