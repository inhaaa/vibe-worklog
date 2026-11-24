const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require(process.env.WORKLOG_PUPPETEER_MODULE || 'puppeteer-core');

const config = JSON.parse(fs.readFileSync(0, 'utf8'));
for (const origin of [config.origin, config.mailbox]) assert.equal(new URL(origin).hostname, '127.0.0.1');
const why = '가상 회사 개통 확인';
const output = path.resolve('target/customer-opening-browser');
const configured = process.env.WORKLOG_CHROME;
const timeout = 35000;
fs.mkdirSync(output, { recursive: true });

async function run(browser, device) {
  const mobile = device === 'mobile';
  const viewport = { width: mobile ? 390 : 1440, height: mobile ? 844 : 900, isMobile: mobile, hasTouch: mobile };
  const suffix = randomUUID().slice(0, 8);
  const company = 'OPEN-' + device.toUpperCase() + '-' + suffix.toUpperCase();
  const date = config.companyDate;
  const shift = mobile ? ['09:00', '18:00', '12:00', '13:00'] : ['09:30', '18:30', '12:30', '13:30'];
  const makePerson = (name, number) => ({
    name, number, email: device + '-' + suffix + '-' + number + '@example.test', password: 'First-' + randomUUID(),
    birth: '1990-05-12'
  });
  const admin = makePerson('최초 담당자', '10001');
  const approver = makePerson('첫 결재자', '10002');
  const worker = makePerson('첫 근무 직원', '10003');
  const locked = makePerson('잠금 확인 직원', '10004');
  const problems = [], passed = [];
  const clockIn = { input: mobile ? 'touch' : 'mouse', posts: 0 };
  let keyboardActivations = 0;
  const contexts = new Set();
  let stage = '';
  async function check(name, action) {
    stage = name;
    console.log(device + ' > ' + name);
    await action();
    passed.push(name);
  }
  async function newPage() {
    const context = await browser.createBrowserContext();
    contexts.add(context);
    const page = await context.newPage();
    page.setDefaultTimeout(timeout);
    await page.setViewport(viewport);
    page.on('pageerror', e => problems.push(e.name));
    return page;
  }
  async function finishPage(page) {
    const context = page.browserContext();
    contexts.delete(context);
    await context.close();
  }
  function absolute(value) {
    const link = new URL(value, config.origin);
    assert.equal(link.origin, config.origin);
    return link.href;
  }
  async function open(page, target) {
    await page.bringToFront();
    const response = await page.goto(absolute(target), { waitUntil: 'domcontentloaded' })
      .catch(() => { throw new Error('화면 이동 ' + new URL(target, config.origin).pathname +
        ' (이전 화면 ' + new URL(page.url()).pathname + ')'); });
    assert.ok(response.status() < 400, new URL(target, config.origin).pathname + ' ' + response.status());
    await page.waitForSelector('main', { visible: true });
  }
  async function keyActivate(page, element) {
    const input = await element.evaluateHandle(e => e.tagName === 'LABEL' ? e.control : e);
    const target = input.asElement();
    assert.ok(target, '키보드 탐색 대상');
    await target.focus();
    const checkbox = await target.evaluate(e => e.type === 'checkbox' || e.type === 'radio');
    await page.keyboard.press(checkbox ? 'Space' : 'Enter');
    keyboardActivations++;
    await input.dispose();
  }
  async function press(page, element) {
    await page.bringToFront();
    await element.evaluate(e => e.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }));
    const isLink = await element.evaluate(e => e.tagName === 'A');
    if (!mobile && !isLink) { await element.asLocator().setWaitForStableBoundingBox(true).click(); return; }
    // Submit with the mobile keyboard, as a user can after filling the last field.
    if (mobile && await element.evaluate(e => e instanceof HTMLButtonElement && e.type === 'submit')) {
      await keyActivate(page, element);
      return;
    }
    const clicked = await element.evaluateHandle(e => {
      const event = { delivered: false };
      e.addEventListener('click', () => { event.delivered = true; }, { once: true, capture: true });
      return event;
    });
    async function hitTarget() {
      await element.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const point = await page.waitForFunction(e => {
        // Inline links may wrap; their enclosing rectangle also contains unrelated text.
        for (const r of e.getClientRects()) {
          const left = Math.max(1, r.left), right = Math.min(innerWidth - 1, r.right);
          const top = Math.max(1, r.top), bottom = Math.min(innerHeight - 1, r.bottom);
          if (right <= left || bottom <= top) continue;
          for (const y of [(top + bottom) / 2, top + 2, bottom - 2]) {
            for (const x of [(left + right) / 2, left + 2, right - 2]) {
              const hit = { x: Math.round(x), y: Math.round(y) };
              if (e.contains(document.elementFromPoint(hit.x, hit.y))) return hit;
            }
          }
        }
        return false;
      }, {}, element);
      const { x, y } = await point.jsonValue();
      if (mobile) await page.touchscreen.tap(x, y);
      else await page.mouse.click(x, y);
    }
    await hitTarget();
    if (await element.evaluate(e => e.isConnected).catch(() => false)) {
      const received = await page.waitForFunction(event => event.delivered, { timeout: 1000 }, clicked).catch(() => null);
      if (!received && await element.evaluate(e => e.isConnected).catch(() => false)) await keyActivate(page, element);
    }
    await clicked.dispose();
  }
  async function click(page, selector, label) {
    if (label == null) {
      await press(page, await page.waitForSelector(selector, { visible: true }));
      return;
    }
    const handle = await page.waitForFunction((s, word) => Array.from(document.querySelectorAll(s)).find(e =>
      e.checkVisibility() && !e.disabled && e.innerText.trim() === word), {}, selector, label)
      .catch(() => { throw new Error(new URL(page.url()).pathname + ' · 버튼 ' + label); });
    await press(page, handle.asElement());
  }
  async function visible(page, selector, text) {
    await page.waitForFunction((s, needle) => Array.from(document.querySelectorAll(s)).some(e =>
      e.checkVisibility() && e.innerText.includes(needle)), {}, selector, text)
      .catch(() => { throw new Error(new URL(page.url()).pathname + ' · 표시 ' + text); });
  }
  async function fill(page, selector, value) {
    const input = await page.waitForSelector(selector, { visible: true });
    await setValue(input, value);
  }
  async function setValue(input, value) {
    await input.evaluate((e, v) => {
      const prototype = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(e, v);
      e.dispatchEvent(new Event('input', { bubbles: true }));
      e.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
  }
  async function selectOption(page, selector, part) {
    const value = await page.waitForFunction((s, p) => Array.from(document.querySelector(s)?.options || [])
      .find(o => o.value && o.textContent.includes(p) && !o.disabled)?.value, {}, selector, part);
    await page.select(selector, await value.jsonValue());
  }
  async function values(page, action, data) {
    const form = 'form[action="' + action + '"]';
    for (const [name, value] of Object.entries(data)) await fill(page, form + ' [name="' + name + '"]', value);
  }
  async function posted(page, action, submit, expected = [302, 303]) {
    const [response] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === action && r.request().method() === 'POST')
        .catch(() => { throw new Error('양식 제출 대기 ' + action); }),
      submit()
    ]);
    assert.ok(expected.includes(response.status()), action + ' HTTP ' + response.status());
    if (response.headers().location) {
      const target = new URL(response.headers().location, config.origin);
      await page.waitForFunction((p, q) => location.pathname === p && location.search === q,
        {}, target.pathname, target.search);
    }
  }
  async function submit(page, action, label, expected) {
    await posted(page, action, () => click(page, 'form[action="' + action + '"] button', label), expected);
  }
  async function screen(page, name) {
    await page.evaluate(() => document.activeElement.blur());
    const width = await page.evaluate(() => ({ viewport: innerWidth, view: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth, touch: navigator.maxTouchPoints }));
    assert.equal(width.viewport, viewport.width, name + ' viewport');
    assert.ok(width.scroll <= width.view + 1, name + ' overflows the viewport (' + width.scroll + ')');
    assert.equal(width.touch > 0, mobile, name + ' touch');
    await page.screenshot({ path: path.join(output, device + '-' + name + '.png') });
  }
  async function login(page, user, good = true, identifier = user.email) {
    await open(page, '/login');
    await values(page, '/login', { companyCode: company, email: identifier, password: user.password });
    await submit(page, '/login', '로그인', good ? [302, 303] : [401]);
    if (good) await visible(page, 'main', '오늘 근무');
    else await visible(page, '[role=alert]', '로그인 정보를 확인');
  }
  let mailbox;
  async function readMail(user, expectedCount) {
    const until = Date.now() + timeout;
    let id;
    while (Date.now() < until) {
      const response = await fetch(config.mailbox + '/api/v1/messages');
      const found = (await response.json()).messages.filter(m => m.To.some(x => x.Address === user.email));
      if (found.length === expectedCount) { id = found[0].ID; break; }
      await new Promise(resolve => setTimeout(resolve, 120));
    }
    assert.ok(id, '메일함에서 해당 수신자와 발급 차수 확인');
    mailbox ??= await newPage();
    await mailbox.bringToFront();
    await mailbox.goto(config.mailbox + '/view/' + id, { waitUntil: 'domcontentloaded' });
    await mailbox.waitForFunction(() => document.body.innerText.includes('일회용 코드:'));
    const body = await mailbox.evaluate(() => document.body.innerText);
    const token = body.match(/일회용 코드:\s*([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])/);
    const expiration = body.match(/만료 시각 \(서울\):\s*(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})/);
    const url = body.match(/http:\/\/127\.0\.0\.1:\d+\/invite\/[A-Z0-9-]+/);
    assert.ok(token && expiration && url && body.includes(company), '메일 본문의 회사·코드·만료 시각·접속 주소');
    const expiresAt = Date.parse(expiration[1].replace(' ', 'T') + '+09:00');
    const minutes = (expiresAt - Date.now()) / 60000;
    assert.ok(minutes > 24 * 60 - 5 && minutes <= 24 * 60 + 1, '메일의 서울 만료 시각');
    const link = new URL(url[0]);
    assert.equal(link.origin, config.origin);
    assert.equal(link.pathname, '/invite/' + company);
    assert.ok(!link.search && !link.hash && !body.includes(link.href + '?'), '코드 없이 폼을 여는 링크');
    return { link: link.href, token: token[1], expiresAt };
  }
  async function activate(page, user, mail) {
    await open(page, mail.link);
    const action = '/invite/' + company + '/accept';
    await values(page, action, { email: user.email, token: mail.token, password: user.password, confirmation: user.password });
    await submit(page, action, '초대 수락·비밀번호 설정', [303]);
    await visible(page, '[role=status]', '초대를 수락');
  }
  const rejected = '입력한 정보가 맞지 않거나 최초 접속 기간이 지났습니다';
  async function startFirstLogin(page, user) {
    // A pending account signs in with its employee number as the password and is sent to the first-login form.
    await open(page, '/login');
    await values(page, '/login', { companyCode: company, email: user.number, password: user.number });
    await submit(page, '/login', '로그인');
    await page.waitForSelector('form[action="/first-login"]', { visible: true });
    assert.equal(await page.$eval('form[action="/first-login"] [name=employeeNo]', e => e.value), user.number, '사번 미리 입력');
  }
  async function setPassword(page, user, birth, good = true) {
    await values(page, '/first-login', { birthDate: birth, password: user.password, confirmation: user.password });
    await submit(page, '/first-login', '비밀번호 정하기', good ? [303] : [422, 429]);
    if (good) await visible(page, '[role=status]', '비밀번호를 정했습니다');
    else await visible(page, '[role=alert]', rejected);
  }
  let adminPage, employeePage;
  async function setupLink(href) {
    await open(adminPage, '/me/home');
    await click(adminPage, 'button[aria-controls="company-setup-steps"]');
    const selector = '#company-setup-steps a[href="' + href + '"]';
    await click(adminPage, selector);
    const expected = new URL(href, config.origin);
    const arrived = (path, search) => location.pathname === path && location.search === search;
    const opened = await adminPage.waitForFunction(arrived, { timeout: 3000 }, expected.pathname, expected.search)
      .catch(() => null);
    if (!opened) {
      // The guide can re-render as it switches roles. Retry only navigation to the same URL.
      await adminPage.waitForFunction(() => document.querySelector('#tna-app')?.getAttribute('aria-busy') !== 'true');
      if (new URL(adminPage.url()).pathname === '/me/home') {
        await keyActivate(adminPage, await adminPage.waitForSelector(selector, { visible: true }));
      }
      await adminPage.waitForFunction(arrived, {}, expected.pathname, expected.search)
        .catch(() => { throw new Error('초기 설정 링크 ' + href + ' / 현재 ' + new URL(adminPage.url()).pathname); });
    }
  }
  async function hire(person) {
    const route = '/admin/employees/onboarding';
    await setupLink(route);
    await values(adminPage, route, { displayName: person.name, joinedOn: date, birthDate: person.birth, reason: why });
    await selectOption(adminPage, 'form[action="' + route + '"] select[name=organizationId]', '첫 근무팀');
    await selectOption(adminPage, 'form[action="' + route + '"] select[name=scheduleVersionId]', '기본 주간조');
    await submit(adminPage, route, '입사 예정 등록');
    const confirm = await adminPage.waitForSelector('form[action$="/confirm"]');
    const action = await confirm.evaluate(e => new URL(e.action).pathname);
    await values(adminPage, action, { employeeNo: person.number, reason: why });
    await click(adminPage, 'form[action="' + action + '"] input[type=checkbox]');
    await submit(adminPage, action, '입사 확정');
    await visible(adminPage, 'main', '최초 접속 대기');
    await visible(adminPage, 'main', person.number);
    const personnel = await adminPage.waitForSelector('main a[href$="/personnel"]');
    person.access = await personnel.evaluate(e => new URL(e.href).pathname.replace(/personnel$/, 'access'));
  }
  async function editRouteCell(label, value, option) {
    stage = '결재선 시트의 ' + label + ' 입력';
    const selector = '[aria-label="' + label + ' 1"]';
    const editing = 'input' + selector + ',select' + selector;
    if (!await adminPage.$(editing)) {
      const display = await adminPage.waitForSelector(selector);
      // AG Grid keeps the horizontal scrollbar separate from the rendered cell.
      await display.evaluate(e => {
        const grid = e.closest('.ag-root-wrapper'), cell = e.closest('.ag-cell');
        const bar = grid.querySelector('.ag-body-horizontal-scroll-viewport');
        if (bar && !cell.closest('.ag-pinned-left-cols-container')) {
          const panel = grid.querySelector('.ag-center-cols-viewport,.ag-body-viewport') || grid;
          bar.scrollLeft += cell.getBoundingClientRect().left - panel.getBoundingClientRect().left
            - Math.max(0, (panel.clientWidth - cell.clientWidth) / 2);
        }
        e.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
      });
      if (mobile) {
        // Wait until AG Grid has moved its centre columns after the horizontal scroll.
        await adminPage.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const point = await adminPage.waitForFunction(s => {
          const e = document.querySelector(s), cell = e?.closest('.ag-cell');
          if (!cell) return false;
          const r = e.getBoundingClientRect(), y = Math.round(r.top + r.height / 2);
          const left = Math.max(3, r.left + 2), right = Math.min(innerWidth - 3, r.right - 2);
          for (const x of [(left + right) / 2, right - 2, left + 2]) {
            if (cell.contains(document.elementFromPoint(x, y))) return { x: Math.round(x), y };
          }
          return false;
        }, {}, selector);
        const { x, y } = await point.jsonValue();
        await adminPage.touchscreen.tap(x, y);
        const opened = await adminPage.waitForSelector(editing, { visible: true, timeout: 800 }).catch(() => null);
        if (!opened) {
          const cell = await display.evaluateHandle(e => e.closest('.ag-cell'));
          await keyActivate(adminPage, cell.asElement());
          await cell.dispose();
        }
      } else await display.click();
    }
    await adminPage.waitForSelector(editing, { visible: true });
    if (option) await selectOption(adminPage, 'select' + selector, value);
    else await fill(adminPage, 'input' + selector, value);
    const field = await adminPage.$(editing);
    if (field) await field.press('Enter');
  }
  async function clockInOn(page, person, track) {
    const clockButton = 'form:has([name=eventKind][value=IN]) button';
    const eventPath = '/me/attendance/events';
    if (track) {
      page.on('request', request => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === eventPath) clockIn.posts++;
      });
    }
    const button = await page.waitForSelector(clockButton + ':enabled', { visible: true });
    assert.equal(await button.evaluate(e => e.textContent.trim()), '출근');
    await button.evaluate(async e => {
      e.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
      await new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)));
    });
    await posted(page, eventPath, () => mobile ? button.tap() : button.click(), [303]);
    await page.waitForFunction(s => document.querySelector(s)?.disabled && /출근 \d\d:\d\d/.test(document.querySelector(s).textContent), {}, clockButton);
    person.clock = await page.$eval(clockButton, e => e.textContent);
  }
  const imported = [makePerson('일괄 등록 직원 A', ''), makePerson('일괄 등록 직원 B', '')];
  try {
    await check('운영자 회사 개통', async () => {
      const operatorPage = await newPage();
      await open(operatorPage, '/operator/login');
      await values(operatorPage, '/operator/login', { loginId: config.operator, password: config.operatorPassword });
      await submit(operatorPage, '/operator/login', '운영자 로그인');
      await values(operatorPage, '/operator/tenants', { companyCode: company, companyName: '첫 근무 회사 ' + device,
        employeeNo: admin.number, employeeName: admin.name, joinedOn: date, email: admin.email, relationshipBasis: why, reason: why });
      await submit(operatorPage, '/operator/tenants', '신규 회사·최초 초대 연결');
      await visible(operatorPage, 'main', company);
      await finishPage(operatorPage);
    });
    await check('최초 담당자 메일 활성화와 첫 로그인', async () => {
      adminPage = await newPage();
      await login(adminPage, admin, false);
      await activate(adminPage, admin, await readMail(admin, 1));
      await login(adminPage, admin);
      await visible(adminPage, 'button[aria-controls="company-setup-steps"]', '회사 초기 설정');
      await screen(adminPage, 'admin-home');
    });
    await check('첫 조직과 근무시간·근무조 편성', async () => {
      await setupLink('/admin/organizations');
      await click(adminPage, 'summary', '조직 단건 등록');
      await values(adminPage, '/admin/organizations', { name: '첫 근무팀', reason: why });
      await submit(adminPage, '/admin/organizations', '조직 등록');
      await setupLink('/admin/schedules?tab=codes');
      await click(adminPage, 'button', '코드 등록');
      await fill(adminPage, '.wt-editor input[pattern]', 'D01');
      await fill(adminPage, '.wt-editor .wt-fields input[maxlength="120"]', '첫 근무 기준');
      for (const [index, name] of ['정규 출근', '정규 퇴근', '정규 휴게 1 시작', '정규 휴게 1 종료'].entries()) {
        const time = await adminPage.waitForFunction(n => Array.from(document.querySelectorAll('.wt-editor label'))
          .find(l => l.innerText.trim() === n)?.querySelector('input[type=time]'), {}, name);
        await setValue(time.asElement(), shift[index]);
      }
      await fill(adminPage, '.wt-editor input[maxlength="500"]', why);
      await click(adminPage, '.wt-editor button', '저장');
      await visible(adminPage, '[role=status]', '근무시간코드를 저장');
      await visible(adminPage, '.wt-revision', shift[0]);
      await setupLink('/admin/schedules?tab=groups');
      await fill(adminPage, '.wg-panel input[pattern]', 'G01');
      await fill(adminPage, '.wg-panel input[maxlength="120"]', '기본 주간조');
      // Five rotating workdays including the day on which this check is run.
      const weekday = (new Date(date + 'T00:00:00Z').getUTCDay() + 6) % 7;
      const days = ['월','화','수','목','금','토','일'].filter((_, i) => (i - weekday + 7) % 7 < 5);
      for (const day of days) {
        await selectOption(adminPage, 'select[aria-label="' + day + '요일 근무시간코드"]', 'D01');
      }
      await fill(adminPage, '.wg-panel textarea', why);
      await click(adminPage, '.wg-panel button', '개정 미리보기');
      await visible(adminPage, '.wg-review', 'D01');
      for (const time of shift) await visible(adminPage, '.wg-review', time);
      await click(adminPage, '.wg-review button', '개정 저장');
      await visible(adminPage, '[role=status]', '근무조 개정을 저장');
    });
    await check('휴일 관리 저장과 출퇴근 허용망 저장', async () => {
      // Public holidays are not imported: today stays a WORKDAY whatever the date; saving reflects the whole year.
      await setupLink('/admin/holidays?year=' + date.slice(0, 4));
      await fill(adminPage, '#holiday-reason', why);
      await click(adminPage, '.wl-form-actions button', '저장');
      await visible(adminPage, '[role=status]', '근태 반영');
      await setupLink('/admin/networks');
      await values(adminPage, '/admin/networks/changes', { network: '127.0.0.1/32', label: '첫 출근 업무망', reason: why });
      await submit(adminPage, '/admin/networks/changes', '허용망 저장');
      await visible(adminPage, 'main h3', '첫 출근 업무망');
    });
    await check('결재자 입사 확정·최초 접속 전 역할 부여와 첫 로그인', async () => {
      await hire(approver);
      await setupLink('/admin/access?role=APPROVER');
      await click(adminPage, '.wl-role-members button', '사원 추가');
      await click(adminPage, 'dialog[open] label:has(input[aria-label="첫 결재자 후보 선택"])');
      await click(adminPage, 'dialog[open] button', '선택한 사원 추가');
      await click(adminPage, '.wl-role-members button', '저장');
      await fill(adminPage, 'dialog[open] textarea', why);
      await click(adminPage, 'dialog[open] button', '1건 저장');
      await visible(adminPage, 'dialog[open] [role=status]', '1명의 권한 변경을 저장');
      await click(adminPage, 'dialog[open] button', '목록 확인');
      await visible(adminPage, '.wl-role-members .ag-cell', approver.name);
      const approverPage = await newPage();
      await startFirstLogin(approverPage, approver);
      await setPassword(approverPage, approver, approver.birth);
      await login(approverPage, approver, true, approver.number);
      await finishPage(approverPage);
    });
    await check('직원 입사 확정·근무 배정과 최초 접속 대기', async () => {
      await hire(worker);
      await hire(locked);
      employeePage = await newPage();
    });
    await check('재직자 일괄 등록(붙여넣기·검증 오류 수정·저장)과 사용자 관리 최초 접속 대기', async () => {
      const ago = new Date(date + 'T00:00:00Z');
      ago.setUTCFullYear(ago.getUTCFullYear() - 1);
      const iso = ago.toISOString().slice(0, 10);
      const joined = iso.replaceAll('-', '.');
      await open(adminPage, '/admin/employees/import');
      const orgCode = await adminPage.$$eval('table[aria-label="조직코드"] tbody tr', rows =>
        rows.map(r => [...r.cells].map(c => c.textContent.trim())).find(([, name]) => name === '첫 근무팀')?.[0]);
      assert.ok(orgCode, '조직코드 안내에서 첫 근무팀 코드');
      const row = (name, birth) => ['', name, joined, birth, orgCode, '사원', ''].join('\t');
      const tsv = birthB => row(imported[0].name, '19900512') + '\n' + row(imported[1].name, birthB);
      // The textarea reads clipboardData in its React paste handler; a synthetic paste event carries the TSV.
      const paste = text => adminPage.$eval('textarea[aria-label="엑셀에서 복사한 행 붙여넣기"]', (e, t) => {
        const data = new DataTransfer();
        data.setData('text/plain', t);
        e.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
      }, text);
      const validate = () => click(adminPage, 'button', '검증');
      await paste(tsv(iso.replaceAll('-', '')));
      await validate();
      await visible(adminPage, '[role=status]', '오류 1행');
      await visible(adminPage, '.ag-cell', '생년월일은 입사일 이전이어야 합니다.');
      // Re-pasting the corrected text replaces the rows; this avoids driving a second AG Grid cell editor.
      await paste(tsv('19900512'));
      await validate();
      await visible(adminPage, '[role=status]', '모든 행 정상');
      const numbers = await adminPage.waitForFunction(() => {
        const rows = Array.from(document.querySelectorAll('.ag-center-cols-container .ag-row'))
          .sort((a, b) => a.getAttribute('row-index') - b.getAttribute('row-index'));
        const found = rows.map(r => r.querySelector('[col-id="status"]')?.innerText.match(/정상 · 사번 (\S+)/)?.[1]);
        return found.length === 2 && found.every(Boolean) && found;
      });
      (await numbers.jsonValue()).forEach((n, i) => { imported[i].number = n; });
      await fill(adminPage, '#import-reason', why);
      await click(adminPage, 'button', '저장');
      await visible(adminPage, '[role=status]', '등록 2명 · 계정 2개');
      await screen(adminPage, 'employee-import');
      // 사용자 관리: filter by 최초 접속 대기 and the second imported number.
      await open(adminPage, '/admin/accounts');
      await selectOption(adminPage, '#account-status', '최초 접속 대기');
      await fill(adminPage, '#account-q', imported[1].number);
      await Promise.all([adminPage.waitForNavigation({ waitUntil: 'domcontentloaded' }),
        adminPage.$eval('form[action="/admin/accounts"]', f => f.requestSubmit())]);
      await adminPage.waitForFunction(n => Array.from(document.querySelectorAll('tbody tr')).some(r =>
        r.innerText.includes(n) && r.innerText.includes('최초 접속 대기')), {}, imported[1].number);
    });
    await check('결재선 설정과 신청서 연결·직원별 미리보기', async () => {
      stage = '결재선 시트 등록';
      await setupLink('/admin/approval-routes');
      await click(adminPage, 'button', '행 추가');
      await editRouteCell('결재선 코드', 'R01');
      await editRouteCell('결재선 이름', '첫 근무 승인');
      await editRouteCell('결재 방식', '지정 사원', true);
      await editRouteCell('지정 결재 사원', approver.name, true);
      await click(adminPage, 'form button', '저장');
      await fill(adminPage, 'dialog[open] textarea', why);
      await posted(adminPage, '/admin/approval-routes/bulk', () => click(adminPage, 'dialog[open] button', '1건 저장'));
      await adminPage.waitForSelector('.t-approval-routes[data-unsaved-changes="false"]', { visible: true });
      await visible(adminPage, '.t-approval-routes .ag-cell', '첫 근무 승인');
      stage = '등록한 결재선을 신청서에 연결';
      await setupLink('/admin/request-forms');
      await click(adminPage, '.rf-list li a', 'A01 출퇴근 정정 신청서');
      await selectOption(adminPage, '#form-route', '첫 근무 승인');
      await values(adminPage, '/admin/request-forms/commands', { reason: why });
      await submit(adminPage, '/admin/request-forms/commands', '저장');
      stage = '저장한 신청서의 사원별 결재선 미리보기';
      await fill(adminPage, '#form-example', worker.number);
      await click(adminPage, '.rf-preview button', '결재선 미리보기');
      await visible(adminPage, '.rf-preview li', approver.name);
      await screen(adminPage, 'approval-preview');
      await finishPage(adminPage);
    });
    await check('직원 첫 로그인(사번·생년월일), 새 비밀번호 로그인과 첫 출근', async () => {
      await startFirstLogin(employeePage, worker);
      await setPassword(employeePage, worker, worker.birth);
      await login(employeePage, worker, true, worker.number);
      await visible(employeePage, '.t-work-home-schedule', shift[0]);
      await visible(employeePage, '.t-work-home-schedule', shift[1]);
      await visible(employeePage, '.t-work-home-timeline li', shift[2]);
      await visible(employeePage, '.t-work-home-timeline li', shift[3]);
      await clockInOn(employeePage, worker, true);
      await screen(employeePage, 'clock-in');
    });
    await check('일괄 등록 직원 첫 로그인(사번·생년월일), 첫 출근과 사용자 관리 사용 중', async () => {
      const page = await newPage();
      await startFirstLogin(page, imported[0]);
      await setPassword(page, imported[0], imported[0].birth);
      await login(page, imported[0], true, imported[0].number);
      await clockInOn(page, imported[0], false);
      await finishPage(page);
      const manager = await newPage();
      await login(manager, admin);
      await open(manager, '/admin/accounts?q=' + encodeURIComponent(imported[0].number));
      await manager.waitForFunction(n => Array.from(document.querySelectorAll('tbody tr')).some(r =>
        r.innerText.includes(n) && r.innerText.includes('사용 중')), {}, imported[0].number);
      await finishPage(manager);
    });
    await check('최초 접속 5회 실패 잠금, 담당자 초기화와 재접속', async () => {
      const attempt = await newPage();
      await startFirstLogin(attempt, locked);
      for (let i = 0; i < 5; i++) await setPassword(attempt, locked, '1990-05-13', false);
      // After the lock even the correct birth date is rejected with the same message.
      await setPassword(attempt, locked, locked.birth, false);
      const operator = await newPage();
      await login(operator, admin);
      await open(operator, locked.access);
      await visible(operator, 'main', '잠김(5회 실패)');
      const action = locked.access.replace(/access$/, 'account-reset');
      operator.on('dialog', dialog => dialog.accept());
      await values(operator, action, { reason: '가상 직원의 잠금 해제' });
      await submit(operator, action, '비밀번호 초기화');
      await visible(operator, '[role=status]', '비밀번호를 초기화했습니다');
      await visible(operator, 'main', '최초 접속 대기');
      await finishPage(operator);
      await startFirstLogin(attempt, locked);
      await setPassword(attempt, locked, locked.birth);
      await login(attempt, locked, true, locked.number);
      assert.equal(clockIn.posts, 1, '첫 출근은 한 번만 접수');
      await screen(attempt, 'unlocked');
    });
    async function verifySingleGrant() {
      const separateSession = await newPage();
      try {
        const path = approver.access.replace(/access$/, 'roles');
        const selector = 'form[action="' + path + '"] input[value=APPROVER]';
        await login(separateSession, admin);
        await open(separateSession, approver.access);
        for (const enabled of [false, true]) {
          await separateSession.waitForFunction((s, v) => document.querySelector(s)?.checked === !v, {}, selector, enabled);
          await click(separateSession, selector);
          await separateSession.waitForFunction((s, v) => document.querySelector(s)?.checked === v, {}, selector, enabled)
            .catch(() => { throw new Error('결재권한 터치 직후 체크 상태 불일치'); });
          await values(separateSession, path, { reason: why });
          await submit(separateSession, path, '역할 저장');
          await open(separateSession, approver.access);
          await separateSession.waitForFunction((s, v) => document.querySelector(s)?.checked === v, {}, selector, enabled)
            .catch(() => { throw new Error('결재권한 저장 후 재조회 불일치'); });
        }
      } finally { await finishPage(separateSession); }
    }
    assert.equal(problems.length, 0, '브라우저 런타임 오류');
    return { result: { device, viewport, company, date, shift, clockIn, keyboardActivations, mailboxMessages: 1, importedEmployees: imported.length, passed },
      verifySingleGrant: mobile ? verifySingleGrant : undefined };
  } catch (error) {
    let location = '';
    if (adminPage && !adminPage.isClosed() && new URL(adminPage.url()).pathname.startsWith('/admin/')) {
      location = await adminPage.evaluate(() => {
        const field = document.activeElement.closest('.ag-cell');
        const modal = document.querySelector('dialog[open] h2');
        return ' [' + window.location.pathname + (field ? ' · ' + field.getAttribute('col-id') : '') +
          (modal ? ' · ' + modal.textContent.trim() : '') + ']';
      }).catch(() => '');
    }
    throw new Error(device + ' / ' + stage + location + ': ' + error.message, { cause: error });
  } finally {
    await Promise.all(Array.from(contexts, context => context.close()));
  }
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: configured, channel: configured ? undefined : 'chrome', headless: true });
  try {
    const result = [], failures = [];
    const chrome = await browser.version();
    let singleGrant;
    for (const device of ['mobile', 'desktop']) {
      try {
        const runResult = await run(browser, device);
        result.push(runResult.result);
        singleGrant ||= runResult.verifySingleGrant;
        fs.writeFileSync(path.join(output, device + '-result.json'), JSON.stringify({ chrome, ...runResult.result }, null, 2));
        console.log('PASS ' + device);
      } catch (error) {
        failures.push(error.message);
        console.error(error.message);
      }
    }
    if (failures.length) throw new Error(failures.join('\n'));
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({ chrome, result }, null, 2));
    console.log('PASS PC and mobile / two new companies / two received emails');
    if (singleGrant) {
      await singleGrant();
      console.log('PASS mobile / separate session / direct approval role save and reload');
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
