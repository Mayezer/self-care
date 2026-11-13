self.addEventListener('push', event => {
  event.waitUntil(showAgendaReminder());
});

async function showAgendaReminder() {
  const subscription = await self.registration.pushManager.getSubscription();
  if (!subscription) return;
  let response;
  try {
    response = await fetch('/api/agenda/web-push/delivery', {
      cache: 'no-store',
      credentials: 'omit',
      headers: { 'X-Push-Endpoint': subscription.endpoint },
    });
  } catch {
    return;
  }
  if (response.status !== 204) return;
  await self.registration.showNotification('Agenda de autocuidado', {
    body: 'Abra a agenda para conferir seus lembretes.',
  });
}

self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(self.clients.openWindow('/agenda'));
});
