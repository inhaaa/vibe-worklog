const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require(process.env.WORKLOG_PUPPETEER_MODULE || 'puppeteer-core');

function leaveBrowser(fixtureFile, device, outputDirectory) {
  assert.ok(fixtureFile && ['pc', 'mobile'].includes(device) && outputDirectory, 'fixture.json pc|mobile evidence-directory');
  const cfg = JSON.parse(fs.readFileSync(fixtureFile, 'utf8'));
  assert.equal(new URL(cfg.baseUrl).hostname, '127.0.0.1');
  assert.equal(new URL(cfg.baseUrl).origin, cfg.baseUrl);
  const mobile = device === 'mobile';
  const viewport = { width: mobile ? 390 : 1440, height: mobile ? 844 : 900, isMobile: mobile, hasTouch: mobile };
  const output = path.resolve(outputDirectory);
  fs.mkdirSync(output, { recursive: true });
  const result = { device, viewport, today: cfg.today, leaveDate: cfg.leaveDate, stages: [], balances: {}, screenshots: [], retriedReads: [] };
  const runtimeErrors = [];
  let stage;

  async function check(name, run) {
    stage = name;
    console.log(device + ' ' + name);
    await run();
    result.stages.push(name);
  }

  async function open(page, route) {
    const url = new URL(route, cfg.baseUrl);
    assert.equal(url.origin, cfg.baseUrl);
    await page.bringToFront();
    let response;
    try {
      response = await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 60000 });
    } catch (error) {
      if (error.name !== 'TimeoutError' && !error.message.includes('net::ERR_ABORTED')) throw error;
      result.retriedReads.push(url.pathname);
      response = await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 60000 });
    }
    assert.equal(response.status(), 200, route);
    await page.waitForSelector('main', { visible: true });
  }

  async function tap(page, selector) {
    await page.bringToFront();
    const element = await page.waitForSelector(selector, { visible: true });
    await element.evaluate(node => node.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }));
    if (mobile) await element.tap();
    else await element.click();
  }

  function named(role, name) {
    return '::-p-aria([name="' + name + '"][role="' + role + '"])';
  }

  async function fill(page, selector, value) {
    await page.bringToFront();
    await page.locator(selector).fill(value);
  }

  async function post(page, route, button, expectedStatus) {
    const [response] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === route && r.request().method() === 'POST'),
      tap(page, named('button', button)),
    ]);
    assert.ok(expectedStatus.includes(response.status()), route + ' HTTP ' + response.status());
    if (response.headers().location) {
      const next = new URL(response.headers().location, cfg.baseUrl);
      assert.equal(next.origin, cfg.baseUrl);
      await page.waitForFunction((pathname, search) => location.pathname === pathname && location.search === search,
        {}, next.pathname, next.search);
    }
    return response;
  }

  async function screenshot(page, name) {
    await page.bringToFront();
    const geometry = await page.evaluate(() => ({
      width: innerWidth, documentWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth, touch: navigator.maxTouchPoints > 0,
    }));
    assert.equal(geometry.width, viewport.width, name);
    assert.ok(geometry.documentWidth <= geometry.clientWidth + 1, name + ' 가로 넘침 ' + JSON.stringify(geometry));
    assert.equal(geometry.touch, mobile, name);
    const file = device + '-' + name + '.png';
    await page.screenshot({ path: path.join(output, file) });
    result.screenshots.push(file);
  }

  function days(value) {
    const number = String(value).trim().replaceAll('−', '-').match(/^[+-]?\d+(\.\d+)?(?=\s*일?\s*$)/);
    assert.ok(number, '일수 ' + JSON.stringify(value));
    return Number(number[0]);
  }

  async function table(page, name) {
    const selector = 'table[aria-label="' + name + '"]';
    await page.waitForSelector(selector, { visible: true });
    return page.$eval(selector, t => ({
      headings: [...t.querySelectorAll('thead th')].map(cell => cell.textContent.trim()),
      rows: [...t.querySelectorAll('tbody tr')].map(row => [...row.cells].map(cell => cell.textContent.trim())),
    }));
  }

  async function metrics(page) {
    await open(page, '/me/requests?year=2026');
    await page.waitForSelector('.t-request-available strong', { visible: true });
    return page.$$eval('.t-annual-metrics section', cards =>
      Object.fromEntries(cards.map(card => [card.querySelector('h2').textContent.trim(), card.querySelector('strong').textContent.trim()])));
  }

  async function approved(approver, worker, id, reason, screenshotName) {
    const detail = '/requests/' + id;
    await open(approver, '/approvals');
    await tap(approver, 'main a[href="' + detail + '"]');
    await approver.waitForSelector('.t-request-detail-summary', { visible: true });
    const actor = await approver.$eval('.t-request-detail-summary', section => section.innerText);
    assert.ok(actor.includes(cfg.worker.name));
    const action = detail + '/approve';
    await fill(approver, 'form[action="' + action + '"] [name=reason]', reason);
    await screenshot(approver, screenshotName);
    await post(approver, action, '승인하기', [302, 303]);
    await open(worker, detail);
    await worker.waitForSelector('.t-request-detail-status[data-status=APPROVED]', { visible: true });
  }

  async function signInForm(browser, person) {
    const context = await browser.createBrowserContext();
    const page = await context.newPage();
    page.setDefaultTimeout(30000);
    await page.setViewport(viewport);
    page.on('pageerror', error => runtimeErrors.push(error.message));
    await open(page, '/login');
    await fill(page, '[name=companyCode]', cfg.companyCode);
    await fill(page, '[name=email]', person.email);
    await fill(page, '[name=password]', cfg.password);
    return page;
  }

  async function login(browser, person) {
    const page = await signInForm(browser, person);
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
      tap(page, 'form[action="/login"] button'),
    ]);
    await page.waitForSelector('.home-today, main h1', { visible: true });
    assert.notEqual(new URL(page.url()).pathname, '/login', person.employeeNo);
    return page;
  }

  async function execute(scenario) {
    const executablePath = process.env.WORKLOG_CHROME;
    const browser = await puppeteer.launch({ executablePath, channel: executablePath ? undefined : 'chrome', headless: true });
    try {
      await scenario(browser);
      assert.deepEqual(runtimeErrors, []);
      result.chrome = await browser.version();
      fs.writeFileSync(path.join(output, device + '-result.json'), JSON.stringify(result, null, 2) + '\n');
    } catch (error) {
      console.error(device + ' / ' + stage + ' / ' + error.message);
      await Promise.allSettled((await browser.pages()).map(async (page, i) => {
        if (!page.url().startsWith(cfg.baseUrl + '/') || new URL(page.url()).pathname === '/login') return;
        const body = await Promise.race([page.$eval('main', node => node.innerText),
          new Promise(resolve => setTimeout(() => resolve(''), 3000).unref())]);
        fs.writeFileSync(path.join(output, device + '-error-' + i + '.txt'), body);
      }));
      throw error;
    } finally {
      await browser.close();
    }
  }
  return { cfg, device, mobile, result, named, days, check, open, tap, fill, post, screenshot, table, metrics, approved, signInForm, login, execute };
}

module.exports = { leaveBrowser };
