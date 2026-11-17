import { useState } from 'react';
import type { FormHTMLAttributes } from 'react';
import { PageHeader } from '../components/PageHeader';
import { ErrorNotice, Field, Form, FormTable, SelectField, TextAreaField, fmtDateTime } from '../ui';

type State = 'PLANNED' | 'CONFIRMED' | 'WITHDRAWN';
type Action = 'CREATE' | 'EDIT' | 'CONFIRM' | 'WITHDRAW';
interface Row {
  id: string; state: State; version: number; displayName: string; joinedOn: string;
  leftOn?: string | null; organizationId?: string | null; scheduleVersionId?: string | null;
  organizationCode?: string | null; organizationName?: string | null; scheduleTemplateName?: string | null;
  scheduleRevision?: number | null; employeeId?: string | null; employeeNo?: string | null;
  decidedByName?: string | null; decidedAt?: string | null; decisionReason?: string | null;
}
interface Command {
  displayName?: string; joinedOn?: string; leftOn?: string | null; organizationId?: string | null;
  scheduleVersionId?: string | null; employeeNo?: string | null; expectedVersion?: number | null;
  expectedOrganizationVersion?: number | null; expectedTemplateVersion?: number | null;
  commandId?: string; reason?: string;
}
interface Model {
  today: string; q?: string; state?: State | null; from?: string | null; to?: string | null;
  page: number; hasNext?: boolean; rows: Row[]; detail?: Row | null; form: Command;
  organizations: { id: string; code: string; name: string; version: number }[];
  scheduleVersions: { id: string; templateName: string; revision: number; validFrom: string; templateVersion: number }[];
  action?: Action | null; publicDemo?: boolean; possibleDuplicate?: boolean;
  inviteEligible?: boolean; retrySameCommand?: boolean;
}

const path = '/admin/employees/onboarding';
const states: Record<State, string> = { PLANNED: '입사 예정', CONFIRMED: '확정', WITHDRAWN: '철회' };

function commandFor(m: Model, action: Action): Command {
  const row = m.detail;
  const initial = row ? {
    displayName: row.displayName, joinedOn: row.joinedOn, leftOn: row.leftOn,
    organizationId: row.organizationId, scheduleVersionId: row.scheduleVersionId, expectedVersion: row.version,
  } : { joinedOn: m.today };
  return m.action === action ? { ...initial, ...m.form, commandId: m.retrySameCommand ? m.form.commandId : undefined } : initial;
}

function CommandForm({ command, children, ...props }: FormHTMLAttributes<HTMLFormElement> & { command: Command }) {
  const [commandId, setCommandId] = useState(() => command.commandId ?? crypto.randomUUID());
  return <Form {...props} onChange={event => {
    if (event.target instanceof HTMLElement && event.target.getAttribute('name')) setCommandId(crypto.randomUUID());
    props.onChange?.(event);
  }}>
    <input type="hidden" name="commandId" value={commandId} />
    {children}
  </Form>;
}

function OrganizationOptions({ model }: { model: Model }) {
  return model.organizations.map(org => <option key={org.id} value={org.id}>{org.code} · {org.name}</option>);
}

function ScheduleOptions({ model }: { model: Model }) {
  return model.scheduleVersions.map(schedule => <option key={schedule.id} value={schedule.id}>
    {schedule.templateName} · 버전 {schedule.revision} · {schedule.validFrom}부터
  </option>);
}

function PlannedForm({ model: m }: { model: Model }) {
  const row = m.detail;
  const f = commandFor(m, row ? 'EDIT' : 'CREATE');
  return <section className="wl-section">
    <h2>{row ? '예정 정보 수정' : '입사 예정 등록'}</h2>
    <CommandForm action={row ? `${path}/${row.id}/changes` : path} command={f}>
      {row && <input type="hidden" name="expectedVersion" value={row.version} />}
      <FormTable columns={2}>
        <Field label="이름" name="displayName" value={f.displayName ?? ''} maxLength={120} required />
        <Field label="입사 예정일" name="joinedOn" type="date" value={f.joinedOn ?? m.today} min={m.today} required />
        <Field label="마지막 재직일" name="leftOn" type="date" value={f.leftOn ?? ''} />
        <SelectField label="최초 소속" name="organizationId" value={f.organizationId ?? ''}>
          <option value="">확정할 때 지정</option><OrganizationOptions model={m} />
        </SelectField>
        <SelectField label="최초 근무" name="scheduleVersionId" value={f.scheduleVersionId ?? ''}>
          <option value="">확정할 때 지정</option><ScheduleOptions model={m} />
        </SelectField>
        <TextAreaField label="변경 사유" name="reason" value={f.reason ?? ''} required maxLength={500} />
      </FormTable>
      {row && f.expectedVersion != null && f.expectedVersion !== row.version && <label>
        <input type="checkbox" name="" required />현재 등록값을 비교했고 편집값을 저장하겠습니다.
      </label>}
      <button className="t-btn primary" type="submit">{row ? '예정 정보 저장' : '입사 예정 등록'}</button>
    </CommandForm>
  </section>;
}

function ConfirmForm({ model: m, row }: { model: Model; row: Row }) {
  const f = commandFor(m, 'CONFIRM');
  const [organization, setOrganization] = useState(f.organizationId ?? '');
  const [schedule, setSchedule] = useState(f.scheduleVersionId ?? '');
  const org = m.organizations.find(choice => choice.id === organization);
  const work = m.scheduleVersions.find(choice => choice.id === schedule);
  const expired = row.joinedOn < m.today;
  return <section className="wl-section">
    <h2>입사 확정 검토</h2>
    <p>회사 입사일 {row.joinedOn}. 소속과 근무는 같은 날부터 적용됩니다.</p>
    {expired && <p role="status">예정일이 지났습니다. 입사 예정일을 수정한 뒤 확정하세요.</p>}
    <CommandForm action={`${path}/${row.id}/confirm`} command={f}>
      <input type="hidden" name="expectedVersion" value={row.version} />
      <input type="hidden" name="expectedOrganizationVersion" value={org?.version ?? ''} />
      <input type="hidden" name="expectedTemplateVersion" value={work?.templateVersion ?? ''} />
      <FormTable columns={2}>
        <SelectField label="확정할 소속" name="organizationId" value={organization} required onChange={event => setOrganization(event.target.value)}>
          <option value="">선택</option><OrganizationOptions model={m} />
        </SelectField>
        <SelectField label="확정할 근무 버전" name="scheduleVersionId" value={schedule} required onChange={event => setSchedule(event.target.value)}>
          <option value="">선택</option><ScheduleOptions model={m} />
        </SelectField>
        <Field label="사번 (비우면 자동 발급)" name="employeeNo" value={f.employeeNo ?? ''} maxLength={40} />
        <TextAreaField label="확정 사유" name="reason" value={f.reason ?? ''} required maxLength={500} />
      </FormTable>
      {m.possibleDuplicate && <p role="status">같은 이름과 입사일의 사원 또는 예정자가 있습니다.</p>}
      <label><input type="checkbox" required />입사일과 최초 소속·근무 버전을 확인했습니다.</label>{' '}
      <button className="t-btn primary" type="submit" disabled={expired || !org || !work}>입사 확정</button>
    </CommandForm>
  </section>;
}

function Detail({ model: m, row }: { model: Model; row: Row }) {
  const withdraw = commandFor(m, 'WITHDRAW');
  const planned = row.state === 'PLANNED';
  return <>
    <section className="wl-section">
      <h2>{row.displayName}</h2>
      <dl>
        <dt>상태</dt><dd>{planned && row.joinedOn < m.today ? '예정일 경과' : states[row.state]}</dd>
        <dt>입사일</dt><dd>{row.joinedOn}</dd>
        <dt>마지막 재직일</dt><dd>{row.leftOn ?? '미지정'}</dd>
        <dt>소속</dt><dd>{row.organizationName ?? '미지정'}</dd>
        <dt>근무</dt><dd>{row.scheduleTemplateName ? `${row.scheduleTemplateName} · 버전 ${row.scheduleRevision}` : '미지정'}</dd>
        {row.decidedAt && <><dt>처리 시각</dt><dd>{fmtDateTime(row.decidedAt)}</dd></>}
        {row.decidedByName && <><dt>처리자</dt><dd>{row.decidedByName}</dd></>}
        {row.decisionReason && <><dt>처리 사유</dt><dd>{row.decisionReason}</dd></>}
      </dl>
    </section>
    {row.state === 'CONFIRMED' && row.employeeId && <section className="wl-section">
      <h2>최초 발령</h2>
      <p>입사 · {row.joinedOn} · 사번 {row.employeeNo}</p>
      <a className="t-btn" href={`/admin/employees/${row.employeeId}`}>사원 상세</a>{' '}
      {row.joinedOn > m.today && <p>계정 초대는 입사일부터 할 수 있습니다.</p>}
      {m.inviteEligible && <a className="t-btn" href={`/admin/employees/${row.employeeId}/access`}>계정 초대</a>}
    </section>}
    {planned && !m.publicDemo && <>
      <PlannedForm model={m} />
      <ConfirmForm model={m} row={row} />
      <section className="wl-section">
        <h2>입사 예정 철회</h2>
        <CommandForm action={`${path}/${row.id}/withdraw`} command={withdraw}>
          <input type="hidden" name="expectedVersion" value={row.version} />
          <TextAreaField label="철회 사유" name="reason" value={withdraw.reason ?? ''} required maxLength={500} />
          <button className="t-btn" type="submit">입사 예정 철회</button>
        </CommandForm>
      </section>
    </>}
  </>;
}

function List({ model: m }: { model: Model }) {
  const pageHref = (page: number) => `${path}?${new URLSearchParams({ q: m.q ?? '', state: m.state ?? '',
    from: m.from ?? '', to: m.to ?? '', page: String(page) })}`;
  return <>
    <section className="wl-section">
      <Form action={path} method="get" className="wl-querybar" role="search" aria-label="입사 예정 검색">
        <Field label="이름" name="q" type="search" value={m.q ?? ''} maxLength={120} />
        <SelectField label="상태" name="state" value={m.state ?? ''}>
          <option value="">전체</option>{Object.entries(states).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </SelectField>
        <Field label="입사일 시작" name="from" type="date" value={m.from ?? ''} />
        <Field label="입사일 종료" name="to" type="date" value={m.to ?? ''} />
        <button className="t-btn" type="submit">조회</button>
      </Form>
      {m.rows.length === 0 ? <p>조회된 입사 예정이 없습니다.</p> : <div className="table-scroll"><table>
        <thead><tr><th scope="col">이름</th><th scope="col">입사일</th><th scope="col">상태</th><th scope="col">최초 소속</th></tr></thead>
        <tbody>{m.rows.map(row => <tr key={row.id}>
          <td><a href={`${path}/${row.id}`}>{row.displayName}</a></td><td>{row.joinedOn}</td>
          <td>{row.state === 'PLANNED' && row.joinedOn < m.today ? '예정일 경과' : states[row.state]}</td>
          <td>{row.organizationName ?? '미지정'}</td>
        </tr>)}</tbody>
      </table></div>}
      <nav className="actions pagination" aria-label="입사 예정 목록 페이지">
        {m.page > 0 && <a href={pageHref(m.page - 1)}>이전</a>}
        {m.hasNext && <a href={pageHref(m.page + 1)}>다음</a>}
      </nav>
    </section>
    {!m.publicDemo && <PlannedForm model={m} />}
  </>;
}

export function OnboardingPage({ data }: { data: Record<string, unknown> }) {
  const m = data.onboarding as Model;
  return <>
    <PageHeader title="입사 예정 관리" actions={<>
      <a className="t-btn" href="/admin/employees">인사기본</a>
      {m.detail && <a className="t-btn" href={path}>입사 예정 목록</a>}
    </>} />
    <ErrorNotice message={data.error as string | null | undefined} />
    {m.detail ? <Detail key={`${m.detail.id}:${m.form.commandId}`} model={m} row={m.detail} /> : <List key={m.form.commandId} model={m} />}
  </>;
}
