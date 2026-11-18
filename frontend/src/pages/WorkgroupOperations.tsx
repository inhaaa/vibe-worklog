import { useState } from 'react';
import type { FormEvent } from 'react';
import type { CsrfToken } from '../types';
import type { WorkTimeCatalog } from './WorkTimeCodesTab';
import { useReviewedCommand, useScheduleQuery } from './workgroupApi';
import type { AssignmentReview, Employee, Group, GroupReview, Weekday } from './workgroupApi';

type Props = { date: string; today: string; csrf: CsrfToken; group?: Group; onBusy: (busy: boolean) => void; onSaved: (id: string) => void };
const labels = ['월', '화', '수', '목', '금', '토', '일'];
function hours(minutes: number) {
  const h = Math.floor(minutes / 60), m = minutes % 60;
  return [h ? h + '시간' : '', (m || !h) ? m + '분' : ''].filter(Boolean).join(' ');
}
function weekDate(date: string, index: number) {
  const result = new Date(date + 'T00:00:00Z');
  result.setUTCDate(result.getUTCDate() + index - (result.getUTCDay() + 6) % 7);
  return result.toISOString().slice(0, 10);
}
function shift(day?: Weekday) {
  if (!day) return '편성 없음';
  if (!day.codeVersionId) return '비근로일';
  const time = (v: string | null) => v?.slice(0, 5);
  const breaks = day.breaks.map(b => time(b.startTime) + '–' + time(b.endTime)).join(', ');
  return day.code + ' · ' + time(day.startTime) + '–' + time(day.endTime) + (breaks ? ' · 휴게 ' + breaks : '') + ' · 소정 ' + hours(day.minutes);
}
function People({ title, rows }: { title: string; rows: Employee[] }) {
  return <section><h4>{title} · {rows.length}명</h4>{rows.length > 0 && <ul>{rows.map(p =>
    <li key={p.id}>{p.number} {p.name} {p.organization && '· ' + p.organization}</li>)}</ul>}</section>;
}
function GroupComparison({ date, preview }: { date: string; preview: GroupReview }) {
  return <>
    <p>적용 시작 {date} · 주간 소정 {hours(preview.weeklyMinutes)}</p>
    <div className="table-scroll" tabIndex={0} role="region" aria-label="개정 전후 시간표"><table>
      <thead><tr><th>날짜</th><th>변경 전</th><th>변경 후</th></tr></thead><tbody>{labels.map((label, i) =>
        <tr key={label}><th scope="row">{weekDate(date, i)} ({label}){weekDate(date, i) < date && ' · 적용 전'}</th>
          <td>{shift(preview.beforeDays.find(d => d.weekday === i + 1))}</td><td>{shift(preview.days.find(d => d.weekday === i + 1))}</td></tr>)}</tbody>
    </table></div>
    <div className="wg-impact"><People title="자동 반영" rows={preview.affected} /><People title="당일 다른 조 이동" rows={preview.departing} /><People title="이후 전입 예약" rows={preview.incoming} /></div>
  </>;
}
function Feedback({ errors }: { errors: string[] }) {
  return errors.length ? <ul role="alert" className="wg-errors">{errors.map((err, i) => <li key={i}>{err}</li>)}</ul> : null;
}
export function GroupEditor({ date, today, csrf, group, onSaved, onBusy, onRefresh }: Props & { onRefresh?: () => void }) {
  const catalog = useScheduleQuery<WorkTimeCatalog>('/codes?date=' + date);
  const [name, setName] = useState(group?.name || '');
  const [code, setCode] = useState(group?.code || '');
  const [days, setDays] = useState(() => labels.map((_, i) => group?.days.find(d => d.weekday === i + 1)?.codeVersionId || ''));
  const [reason, setReason] = useState('');
  const [replace, setReplace] = useState(false);
  const action = useReviewedCommand<GroupReview>('/groups', csrf, onSaved, onBusy);
  const versions = (catalog.data?.codes || []).flatMap(c => c.versions.map(v => ({ ...v, code: c.code, latest: c.versions.find(x => x.effectiveFrom <= date)?.id === v.id })));
  const applicable = versions.filter(v => v.latest && v.enabled);
  const reserved = group?.revisions.find(r => r.validFrom === date);
  function prepare(event: FormEvent) {
    event.preventDefault();
    void action.preview({ id: group?.id ?? null, code, name, validFrom: date, reason,
      days: days.map((id, index) => ({ weekday: index + 1, codeVersionId: id || null })),
      expectedVersion: group?.version ?? null, replaceRevisionId: replace ? reserved?.id : null });
  }
  return <section className="wl-section wg-panel" aria-label="근무조 편성">
    <h2>{group ? group.code + ' · ' + group.name : '새 근무조'}</h2>
    {group && <details><summary>개정 이력 ({group.revisions.length})</summary><ul>{group.revisions.map(r =>
      <li key={r.id}>{r.validFrom} · {r.revision}차 · {r.reason}</li>)}</ul></details>}
    {group && <p>{group.revision == null ? '이 날짜에 적용할 개정이 없습니다.' : date + ' 기준 ' + group.revision + '차 · ' + group.validFrom + '부터 적용'}</p>}
    {catalog.error && <div role="alert">{catalog.error} <button type="button" className="t-btn" onClick={catalog.refresh}>코드 다시 조회</button></div>}
    <form onSubmit={prepare}><fieldset disabled={action.busy || action.uncertain || date < today}>
      <div className="wg-fields"><label>근무조 코드<input required pattern="[A-Z0-9]{2,8}" maxLength={8} value={code}
        readOnly={!!group} onChange={e => { action.clear(); setCode(e.target.value.toUpperCase()); }} /></label>
        <label>근무조명<input required maxLength={120} value={name} onChange={e => { action.clear(); setName(e.target.value); }} /></label></div>
      <div className="wg-days">{labels.map((l, i) => {
        const selected = versions.find(v => v.id === days[i]);
        const options = selected && !applicable.some(v => v.id === selected.id) ? [selected, ...applicable] : applicable;
        return <label key={l}><span>{l}요일 <small>{weekDate(date, i)}</small></span>
          <select aria-label={l + '요일 근무시간코드'} value={days[i]} onChange={e => { action.clear(); setDays(days.map((id, index) => index === i ? e.target.value : id)); }}>
            <option value="">비근로일</option>{options.map(v => <option key={v.id} value={v.id} disabled={!v.latest || !v.enabled}>{v.code} · {v.name} ({v.revision}차)</option>)}</select>
          {selected && <small>{selected.startTime?.slice(0, 5)}–{selected.endTime?.slice(0, 5)} · 소정 {hours(selected.scheduledMinutes)}</small>}
        </label>;
      })}</div>
      <label>변경 사유<textarea required maxLength={500} rows={2} value={reason} onChange={e => { action.clear(); setReason(e.target.value); }} /></label>
      {reserved && <label className="wg-check"><input type="checkbox" checked={replace} onChange={e => { action.clear(); setReplace(e.target.checked); }}/>
        {date}의 {reserved.revision}차 개정 교체</label>}
      <button type="submit" className="t-btn" disabled={!catalog.data || !code || !name.trim() || !reason.trim() || !days.some(Boolean)}>개정 미리보기</button>
    </fieldset></form>
    {date < today && <p>과거 근무조는 조회할 수 있습니다. 개정하려면 오늘 이후 날짜를 선택하세요.</p>}
    {action.error && <p role="alert">{action.error}</p>}
    {group && onRefresh && !action.uncertain && (action.error || Boolean(action.review?.conflicts.length)) &&
      <button type="button" className="t-btn" onClick={onRefresh}>입력을 초기화하고 최신 근무조 조회</button>}
    {action.review && <section aria-label="근무조 개정 미리보기" className="wg-review"><h3>근무조 개정 미리보기</h3>
      <GroupComparison date={date} preview={action.review} />
      <Feedback errors={action.review.conflicts} />
      {action.review.token && !action.review.conflicts.length && <button type="button" className="t-btn primary" disabled={action.busy} onClick={action.save}>{action.uncertain ? '같은 명령으로 저장 확인' : '개정 저장'}</button>}
    </section>}
  </section>;
}

export function MemberAssignment({ date, today, csrf, group, initialQuery, onSaved, onBusy }: Props & { initialQuery: string }) {
  const [q, setQ] = useState(initialQuery);
  const [filter, setFilter] = useState(initialQuery);
  const [page, setPage] = useState(0);
  const [chosen, setChosen] = useState<Employee[]>([]);
  const [replace, setReplace] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const result = useScheduleQuery<Employee[]>('/employees?' + new URLSearchParams({ date, q: filter, page: String(page) }));
  const action = useReviewedCommand<AssignmentReview>('/assignments', csrf, id => {
    setChosen([]); setReason(''); setReplace([]); result.refresh(); onSaved(id);
  }, onBusy);
  function select(person: Employee) {
    action.clear(); setReplace([]);
    setChosen(prev => prev.some(p => p.id === person.id) ? prev.filter(p => p.id !== person.id) : [...prev, person]);
  }
  function prepare() {
    if (!group) return;
    void action.preview({ groupId: group.id, validFrom: date, employeeIds: chosen.map(p => p.id),
      replaceAssignmentIds: replace, reason });
  }
  return <section className="wl-section wg-panel" aria-label="근무조 사원 배정">
    <h2>사원 배정</h2>
    <fieldset disabled={action.busy || action.uncertain}>
      <form className="wg-filter" onSubmit={e => { e.preventDefault(); setPage(0); setFilter(q.trim()); result.refresh(); }}>
        <label>사원 검색<input value={q} onChange={e => setQ(e.target.value)} placeholder="사번, 이름 또는 소속" /></label>
        <button className="t-btn" type="submit">조회</button></form>
      {result.error ? <div role="alert">{result.error} <button className="t-btn" type="button" onClick={result.refresh}>다시 조회</button></div>
        : result.data ? <>{!result.data.length && <p>조회된 사원이 없습니다.</p>}
          {result.data.length > 0 && <ul className="wg-people">{result.data.map(p => <li key={p.id}>
            <label className="wg-check"><input type="checkbox" checked={chosen.some(c => c.id === p.id)}
              disabled={chosen.length >= 50 && !chosen.some(c => c.id === p.id)} onChange={() => select(p)} />
              <span>{p.number} {p.name} <small>{p.organization || '소속 미등록'} · {p.currentGroup || '근무 미배정'}</small></span></label>
            <a href={'/admin/employees/' + p.id + '/schedule'}>배정 이력</a></li>)}</ul>}
        </> : <p role="status">사원 조회 중</p>}
      {(page > 0 || result.data?.length === 50) && <div className="wg-filter"><button className="t-btn" disabled={!page} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1}페이지</span><button className="t-btn" disabled={result.data?.length !== 50} onClick={() => setPage(page + 1)}>다음</button></div>}
      <p>선택 {chosen.length}/50명{group && ' · 배정할 근무조 ' + group.code + ' ' + group.name} · 적용 시작 {date}</p>
      {chosen.length > 0 && <ul className="wg-chosen">{chosen.map(p => <li key={p.id}>{p.number} {p.name}
        <button type="button" aria-label={p.name + ' 선택 해제'} onClick={() => select(p)}>×</button></li>)}</ul>}
      {replace.length > 0 && <p>교체할 예약 {replace.length}건</p>}
      <label>배정 사유<textarea rows={2} maxLength={500} value={reason} onChange={e => { action.clear(); setReason(e.target.value); }} /></label>
      <button type="button" className="t-btn" onClick={prepare} disabled={date < today || !group || !chosen.length || !reason.trim()}>배정 미리보기</button>
    </fieldset>
    {!group && <p>사원에게 배정할 근무조를 선택하세요.</p>}
    {date < today && <p>과거 배정은 조회할 수 있습니다. 변경하려면 오늘 이후 날짜를 선택하세요.</p>}
    {action.error && <p role="alert">{action.error}</p>}
    {action.review && <section className="wg-review" aria-label="사원 배정 미리보기"><h3>사원 배정 미리보기</h3>
      <ul className="wg-assignment-preview">{action.review.rows.map(r => <li key={r.employeeId}><strong>{r.number} {r.name}</strong>
        <dl><div><dt>기존 배정</dt><dd>{r.currentGroup || '근무 미배정'}{r.lastDay && ' · ' + r.lastDay + '까지'}</dd></div>
          <div><dt>새 배정</dt><dd>{r.newGroup} · {r.firstDay}부터</dd></div><div><dt>다음 예약</dt><dd>{r.nextStart || '없음'}</dd></div></dl>
        {r.conflict && <p>{r.conflict}</p>}
        {r.assignmentId && r.conflict && <label className="wg-check"><input type="checkbox" checked={replace.includes(r.assignmentId)} onChange={e => {
          const id = r.assignmentId!; setReplace(e.target.checked ? [...replace, id] : replace.filter(x => x !== id));
        }} />{r.name} 예약 교체</label>}</li>)}</ul>
      <Feedback errors={action.review.conflicts} />
      {action.review.token && !action.review.conflicts.length && <button className="t-btn primary" disabled={action.busy} onClick={action.save}>{action.uncertain ? '같은 명령으로 저장 확인' : '배정 저장'}</button>}
    </section>}
  </section>;
}
