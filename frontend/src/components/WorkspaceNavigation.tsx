import { useEffect, useId, useState } from 'react';
import { getNavigation, getNavigationContext } from '../navigation';
import './WorkspaceNavigation.css';

interface NavigationProps {
  roles: readonly string[];
  page: string;
  data?: Record<string, unknown>;
  pathname?: string;
  onNavigate?: () => void;
}
const groupIcons: Record<string, string> = {
  mine: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  approvals: 'M5 3h14v18H5z m3 9 3 3 5-6 M9 6h6',
  people: 'M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 15a5 5 0 0 1 3 5 M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0',
  operations: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7v5l3 2',
  settings: 'M4 7h16M4 17h16M8 4v6M16 14v6',
};
function NavigationIcon({ path, className }: { path: string; className: string }) {
  return <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d={path} /></svg>;
}
export function WorkspaceNavigation({ roles, page, data, pathname = location.pathname, onNavigate }: NavigationProps) {
  const context = getNavigationContext(page, pathname, data);
  const current = `${page}/${pathname}/${context?.groupId ?? ''}/${context?.sectionId ?? ''}/${context?.itemId ?? ''}`;
  const ancestors = [context?.groupId, context?.sectionId].filter((id): id is string => Boolean(id));
  const [open, setOpen] = useState<string[]>(ancestors);
  const prefix = useId();
  useEffect(() => { setOpen(previous => [...new Set([...previous, ...ancestors])]); }, [current]);
  const toggle = (id: string) => setOpen(previous => previous.includes(id) ? previous.filter(value => value !== id) : [...previous, id]);
  const groups = getNavigation(roles);
  const expandAll = () => setOpen(groups.flatMap(group => [group.id, ...group.children.map(section => section.id)]));
  return (
    <nav className="t-workspace-navigation" aria-label="업무 메뉴">
      <div className="t-navigation-tools">
        <button type="button" onClick={expandAll}>모두 펼치기</button>
        <button type="button" onClick={() => setOpen([])}>모두 접기</button>
      </div>
      {groups.map(group => (
        <div key={group.id} className="t-navigation-group" data-workspace-group={group.id}>
          <button className="t-nav-item t-navigation-toggle t-navigation-major" type="button" data-active={context?.groupId === group.id} aria-expanded={open.includes(group.id)} aria-controls={`${prefix}-${group.id}`} onClick={() => toggle(group.id)}>
            <span className="t-navigation-icon"><NavigationIcon path={groupIcons[group.id]} className="t-navigation-group-icon" /></span>
            <span className="t-navigation-label">{group.label}</span>
            <NavigationIcon path="m9 5 7 7-7 7" className="t-navigation-chevron" />
          </button>
          <div id={`${prefix}-${group.id}`} hidden={!open.includes(group.id)} className="t-navigation-sections">
            {group.children.map(section => (
              <div key={section.id}>
                <button className="t-nav-item t-navigation-toggle t-navigation-section" type="button" data-active={context?.sectionId === section.id} aria-expanded={open.includes(section.id)} aria-controls={`${prefix}-${section.id}`} onClick={() => toggle(section.id)}>
                  <span className="t-navigation-label">{section.label}</span>
                  <NavigationIcon path="m9 5 7 7-7 7" className="t-navigation-chevron" />
                </button>
                <ul id={`${prefix}-${section.id}`} hidden={!open.includes(section.id)} className="t-navigation-links">
                  {section.children.map(leaf => (
                    <li key={leaf.id}><a className="t-nav-item t-navigation-link" href={leaf.href} aria-current={context?.itemId === leaf.id ? 'page' : undefined} onClick={onNavigate}>{leaf.label}</a></li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      ))}
    </nav>
  );
}
export function Breadcrumbs({ page, data, pathname = location.pathname }: Omit<NavigationProps, 'roles' | 'onNavigate'>) {
  const context = getNavigationContext(page, pathname, data);
  const crumbs = page === 'workspace/home' ? [{ label: '홈', href: undefined }] : context?.breadcrumbs;
  if (!crumbs) return null;
  return (
    <nav className="t-location-breadcrumbs" aria-label="현재 위치"><ol>
      {crumbs.map((crumb, index) => (
        <li key={`${index}-${crumb.label}`}>{crumb.href ? <a href={crumb.href}>{crumb.label}</a> : <span aria-current={index === crumbs.length - 1 ? 'page' : undefined}>{crumb.label}</span>}</li>
      ))}
    </ol></nav>
  );
}
