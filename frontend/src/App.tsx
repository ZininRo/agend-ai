import { AppLayout } from "./components/layout/AppLayout"
import { ChatPage } from "./features/chat/ChatPage"
import { EmptyState } from "./features/chat/EmptyState"

function App() {

  return (
    <AppLayout>
      <ChatPage/>
    </AppLayout>
  )
}

export default App
