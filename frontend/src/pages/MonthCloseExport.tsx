import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchPage, PAGE_ACCEPT, PageDownload, PageRequestError } from '../transport';
import { ErrorNotice, SelectField } from '../ui';
import { monthEndAccessDenied, monthEndRequest } from './monthEndApi';

const path = '/admin/attendance/month-close/';
const xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
interface Department { organizationId: string | null; organizationCode: string | null; organizationName: string | null }
interface Options { month: string; version: number; departments: Department[] }
type Phase = 'loading' | 'ready' | 'downloading' | 'retry' | 'stale' | 'denied';

interface Props {
  month: string;
  version: number;
  deny: (error: PageRequestError) => void;
  onRefresh: () => void;
}

export function MonthCloseExport(props: Props) {
  return <ExportCard key={props.month + '/' + props.version} {...props} />;
}

async function requestWorkbook(url: string, signal: AbortSignal): Promise<PageDownload> {
  try {
    const { envelope, status } = await fetchPage(url, { signal, headers: {
      Accept: xlsx + ', application/json;q=0.9, ' + PAGE_ACCEPT + ';q=0.8',
    } });
    if (envelope.page === 'login' || envelope.data.reauthenticate === true) {
      throw new PageRequestError(401, '다시 로그인해 주세요.', true);
    }
    throw new PageRequestError(status, '파일을 받지 못했습니다.');
  } catch (result) {
    if (result instanceof PageDownload && result.blob.type.split(';')[0] === xlsx && result.blob.size > 0) return result;
    throw result;
  }
}

function ExportCard({ month, version, deny, onRefresh }: Props) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [choice, setChoice] = useState('COMPANY');
  const [phase, setPhase] = useState<Phase>('loading');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [attempt, setAttempt] = useState(0);
  const active = useRef<AbortController | null>(null);
  const sending = useRef(false);
  const baseQuery = new URLSearchParams({ month, version: String(version) }).toString();

  const fail = useCallback((cause: unknown, options: boolean) => {
    if (monthEndAccessDenied(cause)) { setPhase('denied'); deny(cause); return; }
    if (cause instanceof PageRequestError && cause.status === 409) {
      setError('마감 상태가 바뀌었습니다. 최신 상태를 다시 조회해 주세요.'); setPhase('stale'); return;
    }
    if (cause instanceof PageRequestError && cause.status === 404) {
      setError('해당 범위의 마감 자료를 찾을 수 없습니다. 다운로드 범위를 다시 조회해 주세요.'); setPhase('retry'); return;
    }
    setError(options ? '다운로드 범위를 불러오지 못했습니다.' : 'Excel 파일을 받지 못했습니다. 다시 시도해 주세요.');
    setPhase(options ? 'retry' : 'ready');
  }, [deny]);

  useEffect(() => {
    const controller = new AbortController();
    active.current = controller;
    monthEndRequest<Options>(path + 'export-options?' + baseQuery, undefined, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!result || !Array.isArray(result.departments)) throw new Error();
      if (result.month !== month || result.version !== version) throw new PageRequestError(409, '마감 상태가 바뀌었습니다.');
      setDepartments(result.departments); setChoice('COMPANY'); setPhase('ready');
    }).catch(cause => { if (!controller.signal.aborted) fail(cause, true); });
    return () => controller.abort();
  }, [attempt, baseQuery, month, version, fail]);

  async function download() {
    const signal = active.current?.signal;
    if (!signal || signal.aborted || sending.current || phase !== 'ready') return;
    sending.current = true;
    setPhase('downloading'); setError(''); setNotice('');
    const query = new URLSearchParams(baseQuery);
    if (choice === 'COMPANY' || choice === 'UNASSIGNED') query.set('scope', choice);
    else { query.set('scope', 'DEPARTMENT'); query.set('organizationId', choice); }
    try {
      const workbook = await requestWorkbook(path + 'export?' + query, signal);
      if (signal.aborted) return;
      workbook.save();
      setNotice('Excel 파일 다운로드를 시작했습니다.'); setPhase('ready');
    } catch (cause) {
      if (!signal.aborted) fail(cause, false);
    } finally { sending.current = false; }
  }

  const selectable = phase === 'ready' || phase === 'downloading';
  if (phase === 'denied') return null;
  return <section className="month-review-batch" aria-label="마감 결과 Excel">
    <h3>마감 결과 Excel</h3>
    {phase === 'loading' && <p role="status">다운로드 범위를 불러오고 있습니다.</p>}
    <ErrorNotice message={error} />
    {phase === 'stale' && <button className="t-btn" type="button" onClick={onRefresh}>마감 상태 다시 조회</button>}
    {phase === 'retry' && <button className="t-btn" type="button" onClick={() => {
      setError(''); setPhase('loading'); setAttempt(value => value + 1);
    }}>다운로드 범위 다시 조회</button>}
    {selectable && <div className="actions">
      <SelectField label="다운로드 범위" name="scope" value={choice} disabled={phase === 'downloading'}
        style={{ maxWidth: '100%' }} onChange={event => { setChoice(event.currentTarget.value); setNotice(''); setError(''); }}>
        <option value="COMPANY">회사 전체</option>
        {departments.map(department => department.organizationId
          ? <option key={department.organizationId} value={department.organizationId}>
            {[department.organizationCode,department.organizationName].filter(Boolean).join(' · ')}</option>
          : <option key="UNASSIGNED" value="UNASSIGNED">소속 미등록</option>)}
      </SelectField>
      <button className="t-btn" type="button" disabled={phase === 'downloading'} onClick={download}>Excel 다운로드</button>
    </div>}
    {(notice || phase === 'downloading') && <p role="status">{phase === 'downloading' ? 'Excel 파일을 준비하고 있습니다.' : notice}</p>}
  </section>;
}
