/* Navigation only: all business reads and commands remain server-rendered. */
(() => {
    const button = document.querySelector('.t-mobile-menu');
    const shell = document.querySelector('.t-app-shell');
    if (!button || !shell) return;
    const close = () => {
        shell.dataset.menuOpen = 'false';
        button.setAttribute('aria-expanded', 'false');
        button.setAttribute('aria-label', '업무 메뉴 열기');
    };
    button.addEventListener('click', () => {
        const open = button.getAttribute('aria-expanded') !== 'true';
        shell.dataset.menuOpen = String(open);
        button.setAttribute('aria-expanded', String(open));
        button.setAttribute('aria-label', open ? '업무 메뉴 닫기' : '업무 메뉴 열기');
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && button.getAttribute('aria-expanded') === 'true') {
            close();
            button.focus();
        }
    });
    const desktop = window.matchMedia('(min-width: 601px)');
    desktop.addEventListener('change', close);
})();
