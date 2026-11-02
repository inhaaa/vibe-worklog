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
export function WorkspaceNavigation({ roles, page, data, pathname = location.pathname, onNavigate }: NavigationProps) {
  const context = getNavigationContext(page, pathname, data);
  const current = `${page}/${pathname}/${context?.groupId ?? ''}/${context?.sectionId ?? ''}/${context?.itemId ?? ''}`;
  const ancestors = [context?.groupId, context?.sectionId].filter((id): id is string => Boolean(id));
  const [open, setOpen] = useState<string[]>(ancestors);
  const prefix = useId();
  useEffect(() => { setOpen(previous => [...new Set([...previous, ...ancestors])]); }, [current]);
  const toggle = (id: string) => setOpen(previous => previous.includes(id) ? previous.filter(value => value !== id) : [...previous, id]);
  return (
    <nav className="t-workspace-navigation" aria-label="업무 메뉴">
      {getNavigation(roles).map(group => (
        <div key={group.id} className="t-navigation-group">
          <button className="t-nav-item t-navigation-toggle t-navigation-major" type="button" aria-expanded={open.includes(group.id)} aria-controls={`${prefix}-${group.id}`} onClick={() => toggle(group.id)}>
            <span aria-hidden="true" className="t-navigation-chevron">{open.includes(group.id) ? '⌄' : '›'}</span>{group.label}
          </button>
          <div id={`${prefix}-${group.id}`} hidden={!open.includes(group.id)} className="t-navigation-sections">
            {group.children.map(section => (
              <div key={section.id}>
                <button className="t-nav-item t-navigation-toggle t-navigation-section" type="button" aria-expanded={open.includes(section.id)} aria-controls={`${prefix}-${section.id}`} onClick={() => toggle(section.id)}>
                  <span aria-hidden="true" className="t-navigation-chevron">{open.includes(section.id) ? '⌄' : '›'}</span>{section.label}
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
  if (!context) return null;
  return (
    <nav className="t-location-breadcrumbs" aria-label="현재 위치"><ol>
      {context.breadcrumbs.map((crumb, index) => (
        <li key={`${index}-${crumb.label}`}>{crumb.href ? <a href={crumb.href}>{crumb.label}</a> : <span aria-current={index === context.breadcrumbs.length - 1 ? 'page' : undefined}>{crumb.label}</span>}</li>
      ))}
    </ol></nav>
  );
}
