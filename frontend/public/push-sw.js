/* Service Worker must be served from the frontend origin, at /push-sw.js. */
self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { /* ignore invalid payload */ }
  event.waitUntil(self.registration.showNotification(data.title || 'Agend AI', {
    body: data.body || 'Новое напоминание',
    icon: '/favicon.ico',
    tag: 'agendai-' + (data.body || ''),
    data: { url: data.url || '/' },
  }));
});
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = new URL(event.notification.data?.url || '/', self.location.origin);
  if (url.origin !== self.location.origin) return;
  event.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (windows) => {
    for (const client of windows) {
      if (client.url === url.href && 'focus' in client) return client.focus();
    }
    return clients.openWindow(url.href);
  }));
});
