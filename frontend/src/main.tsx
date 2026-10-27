import { createRoot } from 'react-dom/client';
import { App } from './App';
import { loadStartupPage } from './startup';

const root = document.getElementById('root');
const bootstrap = document.getElementById('worklog-page');
if (root) {
  void loadStartupPage(bootstrap, location.href)
    .then(({ envelope, url }) => {
      const destination = new URL(url, location.href);
      history.replaceState(
        null,
        '',
        `${destination.pathname}${destination.search}${destination.hash}`,
      );
      createRoot(root).render(<App initial={envelope} />);
    })
    .catch(() => {
      root.textContent = '화면을 불러올 수 없습니다. 새로 고침한 뒤 다시 시도해 주세요.';
    });
}
