import { DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { errorMessage } from './api';

// --- routing -----------------------------------------------------------
//
// The app is served as one static page, so routes live in the URL hash:
// "#/accounts/123?tab=statement". Links can be bookmarked and shared, the
// back button works, and the server needs no route of its own.

export interface Route {
  /** Path segments: "#/accounts/123" is ["accounts", "123"]. */
  segments: string[];
  query: URLSearchParams;
}

function parseHash(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [path, search = ''] = raw.split('?');
  return {
    segments: path.split('/').filter(Boolean).map(decodeURIComponent),
    query: new URLSearchParams(search),
  };
}

export function navigate(path: string) {
  window.location.hash = path.startsWith('/') ? path : `/${path}`;
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));
  useEffect(() => {
    const onChange = () => {
      setRoute(parseHash(window.location.hash));
      window.scrollTo(0, 0);
    };
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

// --- loading data ------------------------------------------------------

export interface Loaded<T> {
  data: T | null;
  error: string | null;
  /** True for the first load and for every reload. */
  loading: boolean;
  reload: () => void;
}

/**
 * Runs a loader when the screen opens and whenever deps change. While a
 * reload is in flight the previous data stays on screen, so a filter
 * change dims the list instead of blanking it.
 */
export function useLoad<T>(loader: () => Promise<T>, deps: DependencyList): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [version, setVersion] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    const run = ++latest.current;
    setLoading(true);
    loader()
      .then((result) => {
        if (run !== latest.current) return; // a newer request has started
        setData(result);
        setError(null);
      })
      .catch((e) => {
        if (run !== latest.current) return;
        setError(errorMessage(e));
      })
      .finally(() => {
        if (run === latest.current) setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { data, error, loading, reload };
}

/** Calls the handler when Escape is pressed, while `active` is true. */
export function useEscape(active: boolean, handler: () => void) {
  useEffect(() => {
    if (!active) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') handler();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [active, handler]);
}

/** Calls the handler on a click outside the element, while `active` is true. */
export function useClickOutside<T extends HTMLElement>(active: boolean, handler: () => void) {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (!active) return;
    const onDown = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) handler();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [active, handler]);
  return ref;
}

/** The pixel width of an element, kept current as the layout changes. */
export function useWidth<T extends HTMLElement>(): [React.RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    setWidth(element.clientWidth);
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) setWidth(Math.round(entry.contentRect.width));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}
