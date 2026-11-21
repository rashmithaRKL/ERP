import { Empty } from '../components';

/** Fallback for any path that is not in the route registry. */
export default function NotFoundPage() {
  return <Empty title="Page not found" body="Choose a module from the navigation." />;
}
