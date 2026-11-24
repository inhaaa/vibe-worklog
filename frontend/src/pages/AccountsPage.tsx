import { useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import type { CsrfToken } from '../types';

export interface AccountItem { employeeId: string; employeeNo: string; displayName: string; organizationName: string | null; state: string; label: string; self: boolean }
export interface AccountDirectory { items: AccountItem[]; q: string; status: string; page: number; hasNext: boolean; statuses: { value: string; label: string }[] }
interface BulkResponse { count?: number; message?: string; errors?: { employeeId: string; message: string }[] }

const CONFIRM = {
  create: '선택한 사원의 계정을 만듭니다. 직원은 7일 안에 사번으로 첫 로그인해 생년월일을 확인하고 비밀번호를 정합니다.',
  reset: '선택한 사원의 비밀번호를 초기화합니다. 직원은 7일 안에 사번으로 다시 첫 로그인해야 합니다.',
};
const UNKNOWN = '처리 결과를 확인하지 못했습니다. 같은 선택으로 다시 실행하면 한 번만 반영됩니다.';

export function AccountsPage({ model, csrf, reload }: { model: AccountDirectory; csrf: CsrfToken; reload: () => void }) {
  const [selected, setSelected] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState('');
  const [errors, setErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState('');
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const chosen = model.items.filter(item => selected.includes(item.employeeId));
  const ready = chosen.length > 0 && chosen.length <= 100 && reason.trim() !== '' && !busy;
  const canCreate = ready && chosen.every(item => item.state === 'NONE');
  const canReset = ready && chosen.every(item => item.state !== 'NONE' && !item.self);

  const allSelected = model.items.length > 0 && chosen.length === model.items.length;

  function toggle(id: string) {
    setSelected(ids => ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id]);
    setCommandId(crypto.randomUUID());
  }

  function toggleAll() {
    setSelected(allSelected ? [] : model.items.map(item => item.employeeId));
    setCommandId(crypto.randomUUID());
  }

  async function run(action: 'create' | 'reset') {
    if (!window.confirm(CONFIRM[action])) return;
    setBusy(true); setMessage(''); setErrors([]); setDone('');
    try {
      const response = await fetch('/admin/accounts/' + action, {
        method: 'POST', credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ commandId, reason, employeeIds: chosen.map(item => item.employeeId) }),
      });
      if (response.redirected || response.status === 401 || response.status === 403) {
        setMessage('로그인 상태와 권한을 다시 확인하세요.'); return;
      }
      if (response.status >= 500 || !(response.headers.get('content-type') ?? '').includes('json')) { setMessage(UNKNOWN); return; }
      const data = await response.json() as BulkResponse;
      if (response.ok) { setDone(`${data.count ?? chosen.length}명 처리했습니다.`); setSelected([]); setReason(''); setCommandId(crypto.randomUUID()); reload(); return; }
      setCommandId(crypto.randomUUID());
      setMessage(data.message ?? '처리하지 못했습니다.');
      setErrors((data.errors ?? []).map(error => {
        const item = model.items.find(candidate => candidate.employeeId === error.employeeId);
        return `${item ? `${item.employeeNo} ${item.displayName}` : error.employeeId}: ${error.message}`;
      }));
    } catch {
      setMessage(UNKNOWN);
    } finally { setBusy(false); }
  }

  const pageUrl = (page: number) => `/admin/accounts?q=${encodeURIComponent(model.q)}&status=${encodeURIComponent(model.status)}&page=${page}`;
  return (
    <section className="w-page" data-modal-busy={String(busy)}>
      <PageHeader title="사용자 관리" help="사원별 로그인 계정 상태를 보고, 여러 사원의 계정을 만들거나 비밀번호를 초기화합니다. 한 페이지(최대 100명) 단위로 처리합니다." />
      <form className="wl-querybar" action="/admin/accounts" method="get" role="search" aria-label="사용자 검색">
        <label htmlFor="account-q">사번·이름 검색</label>
        <input id="account-q" className="t-input" type="search" name="q" defaultValue={model.q} maxLength={80} />
        <label htmlFor="account-status">상태</label>
        <select id="account-status" className="t-input" name="status" defaultValue={model.status}>
          {model.statuses.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </form>
      <table>
        <thead><tr><th scope="col"><input type="checkbox" aria-label="이 페이지 전체 선택" disabled={busy}
          checked={allSelected} onChange={toggleAll} /></th><th scope="col">사번</th><th scope="col">이름</th><th scope="col">현재 소속</th><th scope="col">계정 상태</th></tr></thead>
        <tbody>
          {model.items.map(item => (
            <tr key={item.employeeId}>
              <td><input type="checkbox" aria-label={`${item.employeeNo} ${item.displayName} 선택`}
                disabled={busy} checked={selected.includes(item.employeeId)} onChange={() => toggle(item.employeeId)} /></td>
              <td>{item.employeeNo}</td><td>{item.displayName}</td><td>{item.organizationName ?? '—'}</td>
              <td>{item.label}{item.self ? ' (본인)' : ''}</td>
            </tr>
          ))}
          {model.items.length === 0 && <tr><td colSpan={5}>조건에 맞는 사원이 없습니다.</td></tr>}
        </tbody>
      </table>
      <nav className="actions pagination" aria-label="사용자 목록 페이지">
        {model.page > 0 && <a href={pageUrl(model.page - 1)}>이전</a>}
        {model.hasNext && <a href={pageUrl(model.page + 1)}>다음</a>}
      </nav>
      <div className="wl-save-reason">
        <label htmlFor="account-reason" className="wl-required">처리 사유</label>
        <textarea id="account-reason" value={reason} maxLength={500} onChange={event => { setReason(event.target.value); setCommandId(crypto.randomUUID()); }} />
      </div>
      <div className="wl-form-actions" aria-busy={busy}>
        <span>{`선택 ${chosen.length}명`}</span>
        <button type="button" className="t-btn primary" disabled={!canCreate} onClick={() => run('create')}>계정 생성</button>
        <button type="button" className="t-btn" disabled={!canReset} onClick={() => run('reset')}>비밀번호 초기화</button>
      </div>
      {done && <p role="status">{done}</p>}
      {message && <div className="notice" role="alert"><p>{message}</p>{errors.map(error => <p key={error}>{error}</p>)}</div>}
    </section>
  );
}
