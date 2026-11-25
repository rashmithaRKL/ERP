// Feature styles first, in the order the eager bundle used, then the global styles that may override them. Pages
// are lazy chunks; importing their CSS here keeps it in the entry stylesheet with an unchanged cascade (no late CSS
// chunk overriding styles.css, no unstyled flash on first navigation).
import './features/boms/boms.css';
import './features/products/products.css';
import './features/inventory/inventory.css';
import './features/sales/sales.css';
import './features/purchasing/purchasing.css';
import './components/LineItemsEditor.css';
import './features/routings/routings.css';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './app/App';
import { MotionProvider } from './motion/motion';
import '@fontsource-variable/dm-sans';
import '@fontsource-variable/manrope';
import './styles.css';
import './motion/motion.css';
import './features/audit/audit.css';
const client = new QueryClient({
  defaultOptions: { queries: { staleTime: 15000, retry: 1, refetchOnWindowFocus: false } },
});
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <MotionProvider>
          <App />
        </MotionProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
