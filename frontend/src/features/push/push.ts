import { API_BASE } from '../../api/env';

type PushConfig = { enabled: boolean; public_key: string };
type SubscriptionJson = { endpoint: string; keys: { p256dh: string; auth: string } };

function toUint8Array(base64url: string): Uint8Array<ArrayBuffer> {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(base64url.length / 4) * 4, '=');
  const raw = atob(padded);
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
}

async function config(): Promise<PushConfig> {
  const response = await fetch(`${API_BASE}/api/push/config`);
  if (!response.ok) throw new Error('Не удалось получить настройки уведомлений');
  return response.json() as Promise<PushConfig>;
}

async function sendSubscription(path: string, subscription: PushSubscription) {
  const json = subscription.toJSON() as SubscriptionJson;
  const response = await fetch(`${API_BASE}/api/push/${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(json),
  });
  if (!response.ok) throw new Error('Не удалось сохранить подписку на сервере');
}

export function pushSupported() {
  return window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

export async function pushEnabled(): Promise<boolean> {
  if (!pushSupported()) return false;
  const registration = await navigator.serviceWorker.getRegistration('/push-sw.js');
  return !!(await registration?.pushManager.getSubscription());
}

export async function enablePush(): Promise<void> {
  if (!pushSupported()) throw new Error('Нужен HTTPS или localhost и браузер с поддержкой Web Push');
  const settings = await config();
  if (!settings.enabled) throw new Error('На сервере ещё не настроены VAPID-ключи');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Браузер не разрешил уведомления');
  const registration = await navigator.serviceWorker.register('/push-sw.js');
  const subscription = (await registration.pushManager.getSubscription()) ??
    (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: toUint8Array(settings.public_key) }));
  await sendSubscription('subscriptions', subscription);
}

export async function disablePush(): Promise<void> {
  const registration = await navigator.serviceWorker.getRegistration('/push-sw.js');
  const subscription = await registration?.pushManager.getSubscription();
  if (!subscription) return;
  await sendSubscription('unsubscribe', subscription);
  await subscription.unsubscribe();
}
