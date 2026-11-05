import { useEffect, useState } from 'react';
import './ThemeSwitcher.css';

type Theme = 'charcoal' | 'blue';
const storageKey = 'worklog-theme';

function storedTheme(): Theme {
  try {
    return localStorage.getItem(storageKey) === 'blue' ? 'blue' : 'charcoal';
  } catch {
    return 'charcoal';
  }
}

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>(storedTheme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(storageKey, theme);
    } catch {
      // 저장소가 제한되어도 현재 화면의 선택은 사용할 수 있다.
    }
  }, [theme]);

  return <label className="t-theme-picker">
    <span>화면 테마</span>
    <select aria-label="화면 테마" value={theme}
      onChange={event => setTheme(event.target.value === 'blue' ? 'blue' : 'charcoal')}>
      <option value="charcoal">차콜</option>
      <option value="blue">블루</option>
    </select>
  </label>;
}
