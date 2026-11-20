// @vitest-environment jsdom

import { cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { PersonnelPage } from './PersonnelPage';

const data = {
  adminPersonnel: { tab: 'family', editable: true, employee: { id: 'sample', version: 0 } },
  employeeHeader: { employeeId: 'sample', employeeNo: '71001', employeeName: '합성 사원', companyName: '합성 회사', employmentStatus: 'WORKING' },
  personnel: { version: 1, form: {} },
  personalDetails: { version: 2, form: {}, entries: [], appointments: [] },
  today: '2026-11-17',
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('submits only the selected family tab and protects its draft on tab changes', () => {
  let submitted: FormData | undefined;
  const view = render(<div onSubmit={event => {
    event.preventDefault(); submitted = new FormData(event.target as HTMLFormElement);
  }}>
    <PersonnelPage page="org/personnel" data={data}
      csrf={{ token: 'token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }} reload={() => {}} />
  </div>);
  const panel = view.getByRole('tabpanel', { name: '가족사항' });
  fireEvent.click(within(panel).getByRole('button', { name: '이력 추가' }));
  fireEvent.change(within(panel).getByLabelText('관계'), { target: { value: 'CHILD' } });
  fireEvent.change(within(panel).getByLabelText('인원'), { target: { value: '2' } });
  fireEvent.change(within(panel).getByLabelText('변경 사유'), { target: { value: '합성 검증' } });
  vi.spyOn(window, 'confirm').mockReturnValue(false);
  fireEvent.click(view.getByRole('tab', { name: '기본사항' }));
  expect(view.getByRole('tabpanel', { name: '가족사항' })).toBe(panel);
  fireEvent.click(within(panel).getByRole('button', { name: '저장' }));
  expect(submitted?.get('relationship')).toBe('CHILD');
  expect(submitted?.get('count')).toBe('2');
  expect(submitted?.get('detailsVersion')).toBe('2');
  expect(submitted?.has('employeeVersion')).toBe(false);
  expect(submitted?.has('title')).toBe(false);
});

it('opens organization options from a normal initial page and exports the active tab', () => {
  const view = render(<PersonnelPage page="org/personnel"
    data={{ ...data, adminPersonnel: { ...data.adminPersonnel, tab: 'basic', exportAllowed: true,
      organizations: [{ id: 'organization', code: 'DEMO-OPS', name: '합성 운영팀' }] } }}
    csrf={{ token: 'token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }} reload={() => {}} />);
  fireEvent.click(view.getByRole('tab', { name: '발령내역' }));
  const panel = view.getByRole('tabpanel', { name: '발령내역' });
  fireEvent.click(within(panel).getByRole('button', { name: '소속 발령 등록' }));
  expect(within(panel).getByRole('option', { name: 'DEMO-OPS · 합성 운영팀' }).getAttribute('value')).toBe('organization');
  const download = within(panel).getByRole('button', { name: 'Excel 다운로드' });
  expect(download.closest('form')?.getAttribute('action')).toBe('/admin/employees/sample/personnel/tabs/appointments/export');
});
