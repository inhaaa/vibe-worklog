import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Check, CsrfContext, Field, Form, fmtDate, fmtTime, fmtDateTime, duration, secondsInput } from './ui';

describe('공통 폼과 표시 계약', () => {
  it('서버 CSRF 이름과 토큰을 POST 폼에 넣고 사용자 입력은 이스케이프한다', () => {
    const html = renderToStaticMarkup(
      <CsrfContext
        value={{ token: 'csrf-token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }}
      >
        <Form action="/admin/employees">
          <Field label="이름" name="displayName" value={'<script>alert(1)</script>'} />
        </Form>
      </CsrfContext>,
    );
    expect(html).toContain('name="_csrf" value="csrf-token"');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });
  it('체크 해제와 복수 역할 값을 Spring 폼 계약으로 전달한다', () => {
    const html = renderToStaticMarkup(
      <Check name="roles" label="관리자" value="ATTENDANCE_ADMIN" checked />,
    );
    expect(html).toContain('name="_roles" value="on"');
    expect(html).toContain('value="ATTENDANCE_ADMIN"');
  });
  it('업무 날짜는 시간대 변환 없이 표시하고 시각은 서울 기준 초까지만 보인다', () => {
    expect(fmtDate('2026-01-01')).toBe('2026-01-01');
    expect(fmtDateTime('2026-11-11T08:09:58.315Z')).toBe('2026-11-11 17:09:58');
    expect(fmtDateTime('2026-12-31T15:00:00.999Z')).toBe('2027-01-01 00:00:00');
    expect(fmtTime('09:00:00.000')).toBe('09:00');
    expect(fmtTime('09:00')).toBe('09:00');
    expect(fmtTime('09:00:30.125')).toBe('09:00:30');
    expect(fmtTime('09:00:00.500')).toBe('09:00:00');
    expect(fmtTime(null)).toBe('미확인');
    expect(duration(29_398_999)).toBe('8:09:58');
    expect(duration(0)).toBe('0:00:00');
    expect(duration(null)).toBe('-');
    expect(secondsInput('2026-11-04T18:00:30.125')).toBe('2026-11-04T18:00:30');
    expect(secondsInput('2026-11-04T12:00/2026-11-04T13:00:00.000')).toBe('2026-11-04T12:00/2026-11-04T13:00:00');
    expect(secondsInput(null)).toBe('');
  });
});
