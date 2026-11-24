import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Vendor chunks by npm package. Route pages are lazy chunks (src/app/routes.tsx), so `charts` downloads only with the
 * pages that draw charts. React gets its own chunk: left to Rollup, React's internals landed in `charts`, which made
 * the entry import the charts chunk eagerly. Packages added to recharts later simply fall back to Rollup's chunking.
 */
const vendorChunks: [chunk: string, packages: RegExp][] = [
  ['react', /^(react|react-dom|scheduler)$/],
  ['motion', /^(motion|framer-motion|motion-dom|motion-utils)$/],
  [
    'charts',
    /^(recharts|victory-vendor|d3-.+|internmap|@reduxjs\/toolkit|redux|redux-thunk|reselect|immer|react-redux|use-sync-external-store|decimal\.js-light|es-toolkit|eventemitter3|tiny-invariant|clsx|@standard-schema\/.+)$/,
  ],
];
/** Package of a module id (the innermost `node_modules/<name>` or `node_modules/@scope/<name>`). */
const packageName = (id: string) =>
  /.*\/node_modules\/((?:@[^/]+\/)?[^/]+)/.exec(id.replaceAll('\\', '/'))?.[1];

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const name = packageName(id);
          return name ? vendorChunks.find(([, packages]) => packages.test(name))?.[0] : undefined;
        },
      },
    },
  },
  server: { port: 5173, proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } } },
  preview: { port: 4173 },
});
