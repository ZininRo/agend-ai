import { AppLayout } from './components/layout/AppLayout';
import { ChatPage } from './features/chat/ChatPage';
import { AuthPage } from './features/auth/AuthPage';
import { useAuth } from './features/auth/AuthContext';

function App() {
  const auth = useAuth();

  if (auth.status === 'loading') {
    return <main aria-busy="true" style={{ minHeight: '100%', display: 'grid', placeItems: 'center', color: 'var(--color-text-subtle)' }}>Загрузка…</main>;
  }
  if (auth.status === 'unauthenticated') return <AuthPage />;

  return (
    <AppLayout>
      <ChatPage />
    </AppLayout>
  );
}

export default App;
