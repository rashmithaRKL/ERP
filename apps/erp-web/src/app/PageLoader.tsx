import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';

/**
 * Placeholder for the routed area (KPI row and a large panel) while `/api/state` or a page chunk loads. It fades in
 * after a short delay (`.page-skeleton` in styles.css), so fast loads do not flash it.
 */
export function PageSkeleton() {
  return (
    <div className="skeleton-grid page-skeleton" role="status">
      <span className="sr-only">Loading page…</span>
      {[1, 2, 3, 4].map((x) => (
        <div key={x} className="skeleton" />
      ))}
      <div className="skeleton large" />
    </div>
  );
}

type BoundaryState = { error?: Error };

/**
 * Keeps the shell usable when a page cannot render, for example when its chunk fails to download after a new
 * release or while offline. The shell mounts one per route (inside the keyed page transition), so navigating away
 * resets it.
 */
export class PageErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = {};

  static getDerivedStateFromError(error: Error): BoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="error-panel" role="alert">
        <TriangleAlert />
        <div>
          <strong>This page couldn’t be opened</strong>
          <p>
            {error.message || 'Something went wrong while loading the page.'} Check the connection and try
            again, or reload to get the latest version.
          </p>
        </div>
        <button className="button secondary" onClick={() => this.setState({ error: undefined })}>
          Try again
        </button>
        <button className="button primary" onClick={() => window.location.reload()}>
          <RefreshCw size={14} />
          Reload
        </button>
      </div>
    );
  }
}
