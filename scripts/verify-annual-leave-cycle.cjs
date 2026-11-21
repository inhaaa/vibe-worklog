// node scripts/verify-annual-leave-cycle.cjs <AnnualLeaveCycleBrowser fixture.json> <pc|mobile> <evidence-directory>
const assert = require('node:assert/strict');
const { leaveBrowser } = require('./verify-leave-browser.cjs');
const { cfg, mobile, result, named, days, check, open, tap, fill, post, screenshot, table, metrics, approved, login, execute } = leaveBrowser(...process.argv.slice(2));
const cycleRoute = '/admin/leave-balances/annual-cycle';

async function ledger(page, year) {
  await tap(page, named('link', '내 연차 원장'));
  await page.waitForSelector(named('heading', '내 연차 원장'), { visible: true });
  const yearInput = named('spinbutton', '조회 연도');
  const control = await page.waitForSelector(yearInput, { visible: true });
  if (await control.evaluate(input => input.value) !== String(year)) {
    await fill(page, yearInput, String(year));
    await tap(page, named('button', '조회'));
    await page.waitForFunction(y => new URL(location.href).searchParams.get('year') === y, {}, String(year));
    await page.waitForSelector(named('heading', '내 연차 원장'), { visible: true });
  }
  await page.waitForFunction(y => document.querySelector('#annual-entries-title + p')?.textContent.startsWith(y + '년 '), {}, year);
  const region = await page.waitForSelector(named('region', '오늘 사용 가능 연차'), { visible: true });
  const available = await region.$eval('strong', value => value.innerText);
  const selector = 'table[aria-label="연차 변동 이력"]';
  if (!await page.$(selector)) {
    const text = await page.$eval('main', main => main.innerText);
    assert.ok(text.includes(year + '년에 조회할 연차 변동이 없습니다.'));
    return { available: days(available), entries: [], movements: [] };
  }
  const contents = await table(page, '연차 변동 이력');
  assert.deepEqual(contents.headings, ['구분', '일수 증감', '귀속연도', '효력 기준일', '휴가 사용일', '근거·사유']);
  const movements = contents.rows.map(([kind, quantity, attribution, effectiveOn, leaveDate, reason]) => {
    const fromPrior = ['기초 등록', '다음 연도로 이월', '연차 소멸'].includes(kind);
    const fromApproval = ['휴가 사용', '취소 복원'].includes(kind);
    assert.equal(attribution, fromPrior ? '2025년' : '2026년', kind + ' 귀속');
    assert.equal(leaveDate, fromApproval ? cfg.leaveDate : '해당 없음', kind + ' 휴가일');
    assert.equal(effectiveOn, fromApproval ? cfg.today : kind === '기초 등록' ? '2025-01-01' : '2026-01-01', kind + ' 효력 기준일');
    assert.ok(reason.length > 0, kind + ' 근거와 사유');
    return { kind, days: days(quantity), attribution, effectiveOn, leaveDate, reason };
  });
  const entries = movements.map(m => [m.kind, m.days]).sort((a, b) => a[0].localeCompare(b[0], 'ko'));
  return { available: days(available), entries, movements };
}

const grant = ['연차 부여', 12], use = ['휴가 사용', -0.5], restore = ['취소 복원', 0.5];
const boundary = [grant, ['전년도에서 이월', 3], ['다음 연도로 이월', -3], ['연차 소멸', -1.5]];
async function balance(page, key, expected, entries, shot) {
  const summary = await metrics(page);
  assert.equal(days(summary['현재 사용 가능 연차']), expected, key + ' 근태 신청 잔여');
  const current = await ledger(page, 2026);
  assert.equal(current.available, expected, key + ' 내 원장 잔여');
  assert.deepEqual(current.entries, [...entries].sort((a, b) => a[0].localeCompare(b[0], 'ko')), key + ' 내 원장 항목');
  result.balances[key] = { summary, ...current };
  if (shot) await screenshot(page, key);
}

async function review(admin) {
  const response = await post(admin, cycleRoute + '/preview', '미리보기', [200]);
  const model = await response.json();
  const data = await table(admin, '사원별 반영 예정');
  const prepared = cfg.worker.employeeNo, empty = cfg.comparison.employeeNo;
  const actual = data.rows.map(row => [row[0], ...row.slice(1).map(days)]);
  const find = no => actual.find(row => row[0].includes(no));
  assert.deepEqual(find(prepared).slice(1), [4.5, 3, 1.5, 12, 15]);
  assert.deepEqual(find(empty).slice(1), [0, 0, 0, 12, 12]);
  assert.equal(actual.length, 2, '회계연도 시작에 재직한 두 사원');
  assert.equal(model.periodStart, '2026-01-01');
  assert.equal(model.periodEnd, '2026-12-31');
  assert.equal(model.canPost, true);
  return { headings: data.headings, rows: actual };
}

async function run(browser) {
  const admin = await login(browser, cfg.admin);
  await check('담당자가 연차 관리에서 연도별 처리로 이동', async () => {
    await open(admin, '/admin/leave-balances');
    await admin.waitForSelector('main h1', { visible: true });
    assert.ok(await admin.$('main a[href="' + cycleRoute + '"]'), '연차 관리에 연차 부여·이월·소멸 링크가 필요합니다.');
    await tap(admin, named('link', '연차 부여·이월·소멸'));
    await admin.waitForSelector(named('button', '기준 저장'), { visible: true });
  });
  const worker = await login(browser, cfg.worker);
  let approver, comparison;
  await check('담당자가 회사 연차 기준을 저장하고 직원이 기초 잔여를 조회', async () => {
    await admin.select(named('combobox', '회계연도 시작 월'), '1');
    await fill(admin, named('spinbutton', '연간 부여 일수'), '12');
    await fill(admin, named('spinbutton', '이월 한도 일수'), '3');
    await fill(admin, named('textbox', '기준 설정 사유'), '가상 회사 2026년 연차 운영 기준');
    const response = await post(admin, cycleRoute + '/policy', '기준 저장', [200]);
    const rule = await response.json();
    assert.deepEqual([rule.fiscalStartMonth, rule.grantDays, rule.carryLimitDays], [1, 12, 3]);
    await balance(worker, 'opening', 0, [], false);
    await metrics(worker);
    const prior = await ledger(worker, 2025);
    assert.equal(prior.entries.length, 1);
    assert.equal(prior.entries[0][1], 4.5);
    result.balances.prior = prior;
  });
  await check('같은 미리보기 두 번과 소멸 확인 전 잔액 유지', async () => {
    await fill(admin, named('spinbutton', '대상 회계연도'), '2026');
    await fill(admin, named('textbox', '처리 사유'), '회계연도 확정 후 일괄 반영');
    result.preview = await review(admin);
    assert.deepEqual(await review(admin), result.preview);
    const button = await admin.waitForSelector(named('button', '원장에 반영'), { visible: true });
    assert.ok(await button.evaluate(node => node.disabled), '소멸 대상 확인을 먼저 요구해야 합니다.');
    if (mobile) await admin.$eval('#annual-review-title', heading => heading.scrollIntoView({ block: 'start', behavior: 'instant' }));
    await screenshot(admin, 'preview');
    await balance(worker, 'preview', 0, [], false);
  });
  await check('담당자가 확인한 처리와 동일 명령 재시도 후 사원별 잔액 확인', async () => {
    await tap(admin, named('checkbox', '소멸 대상과 일수를 확인했습니다.'));
    // Drop only the first committed response to exercise the actual retry control.
    const route = cycleRoute + '/apply';
    const session = await admin.createCDPSession();
    let originalBody, originalId;
    try {
      await session.send('Fetch.enable', { patterns: [{ urlPattern: cfg.baseUrl + route, requestStage: 'Response' }] });
      const [response] = await Promise.all([
        new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('첫 원장 반영 응답을 기다렸습니다.')), 30000);
          session.once('Fetch.requestPaused', value => { clearTimeout(timer); resolve(value); });
        }),
        tap(admin, named('button', '원장에 반영')),
      ]);
      assert.equal(response.responseStatusCode, 200, '첫 반영을 서버가 완료해야 합니다.');
      const payload = await session.send('Fetch.getResponseBody', { requestId: response.requestId });
      originalId = JSON.parse(payload.base64Encoded ? Buffer.from(payload.body, 'base64').toString() : payload.body).id;
      originalBody = response.request.postData;
      await session.send('Fetch.failRequest', { requestId: response.requestId, errorReason: 'ConnectionReset' });
    } finally {
      await session.detach();
    }
    const retried = await post(admin, route, '같은 명령으로 반영 결과 확인', [200]);
    assert.match(originalId, /^[\da-f-]{36}$/);
    assert.equal((await retried.json()).id, originalId);
    assert.equal(retried.request().postData(), originalBody, '동일한 명령과 미리보기로 결과 재확인');
    await admin.waitForFunction(() => document.querySelector('main').innerText.includes('이미 반영 2명'));
    assert.equal(await admin.$(named('button', '원장에 반영')), null);
    result.replay = { originalHttp: 200, retryHttp: retried.status(), sameApplication: true };
    await admin.browserContext().close();
    await balance(worker, 'posted', 15, boundary, true);
    comparison = await login(browser, cfg.comparison);
    await balance(comparison, 'baseline', 12, [grant], true);
    await metrics(worker);
    const prior = await ledger(worker, 2025);
    assert.deepEqual(prior.entries.map(entry => entry[1]).sort((a, b) => a - b), [-3, -1.5, 4.5]);
    assert.ok(prior.entries.some(([kind, amount]) => kind === '다음 연도로 이월' && amount === -3));
    assert.ok(prior.entries.some(([kind, amount]) => kind === '연차 소멸' && amount === -1.5));
    result.balances.priorClosed = prior;
    await screenshot(worker, 'prior-ledger');
  });
  let requestId, cancelId;
  await check('직원이 오전 반차를 제출하고 대기 중 잔액 확인', async () => {
    const target = '/me/requests/new?' + new URLSearchParams({ kind: 'LEAVE_DAY', mode: 'PAGE', date: cfg.leaveDate, year: '2026' });
    await open(worker, target);
    await worker.waitForSelector('#annual-leave-part', { visible: true });
    await worker.select('#annual-leave-part', 'AM');
    await fill(worker, '#annual-half-date', cfg.leaveDate);
    const usable = 'input[name=bucketId][aria-label*="사용 가능 15일"]';
    const bucket = await worker.waitForSelector(usable, { visible: true });
    if (!await bucket.evaluate(input => input.checked)) await tap(worker, usable);
    await fill(worker, '#annual-reason', '가상 사원 오전 반차');
    await tap(worker, named('button', '미리보기'));
    await worker.waitForSelector('.t-request-preview', { visible: true });
    const preview = await worker.$eval('.t-request-preview', section => section.innerText);
    assert.match(preview, /예상 차감 0\.5일/);
    assert.match(preview, /사용 가능 15일/);
    const sent = await post(worker, '/me/requests/leave', '신청 제출', [302, 303]);
    requestId = new URL(sent.headers().location, cfg.baseUrl).searchParams.get('createdId');
    assert.match(requestId, /^[\da-f-]{36}$/);
    await tap(worker, '.t-request-success a[href="/requests/' + requestId + '"]');
    await worker.waitForSelector('.t-request-detail-status[data-status=PENDING]', { visible: true });
    assert.equal(await worker.$('form[action$="/approve"]'), null);
    await screenshot(worker, 'leave-pending');
    await balance(worker, 'pending', 15, boundary, false);
    assert.equal(days(result.balances.pending.summary['결재 대기']), 0.5);
  });
  await check('승인자가 오전 반차를 최종 승인하고 직원 원장에 반일 사용 반영', async () => {
    approver = await login(browser, cfg.approver);
    await approved(approver, worker, requestId, '합성 근무일 오전 반차 승인', 'leave-approval');
    await balance(worker, 'approved', 14.5, [...boundary, use], true);
    assert.equal(days(result.balances.approved.summary['사용 예정']), 0.5);
  });
  await check('직원이 승인된 반차의 취소를 요청하고 아직 복원 전 잔액 확인', async () => {
    const action = '/requests/' + requestId + '/cancellations';
    await open(worker, '/requests/' + requestId);
    await fill(worker, 'form[action="' + action + '"] [name=reason]', '가상 오전 반차 계획 취소');
    const sent = await post(worker, action, '취소 심사 요청', [302, 303]);
    cancelId = new URL(sent.headers().location, cfg.baseUrl).pathname.split('/').pop();
    assert.match(cancelId, /^[\da-f-]{36}$/);
    assert.notEqual(cancelId, requestId);
    await open(worker, '/requests/' + cancelId);
    await worker.waitForSelector('.t-request-detail-status[data-status=PENDING]', { visible: true });
    await screenshot(worker, 'cancel-pending');
    await balance(worker, 'cancelPending', 14.5, [...boundary, use], false);
  });
  await check('승인자가 취소를 최종 승인하고 직원 원장·근태 신청의 잔액 복원', async () => {
    await approved(approver, worker, cancelId, '합성 취소 승인', 'cancel-approval');
    await balance(worker, 'restored', 15, [...boundary, use, restore], true);
    assert.equal(days(result.balances.restored.summary['사용 예정']), 0);
    await balance(comparison, 'baselineAfterCancellation', 12, [grant], false);
  });
}

execute(run).catch(error => { console.error(error.stack); process.exitCode = 1; });
