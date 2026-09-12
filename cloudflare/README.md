# Cloudflare Worker: дозвон для P2P Call

WebSocket-релей на Cloudflare Workers (Durable Object). Позволяет двум
экземплярам приложения **дозвониться друг другу напрямую, без Telegram и
без своего сервера**: оба телефона подключаются по WebSocket в одну «комнату»,
воркер ретранслирует между ними дозвон и SDP (offer/answer). Медиа (видео/аудио)
после согласования идёт P2P — мимо воркера.

## Схема

```
Телефон A ──wss://…/ws?room=КОД──► Durable Object (комната "КОД")
Телефон B ──wss://…/ws?room=КОД──► Durable Object (комната "КОД")
```

- Первый подключившийся получает роль `caller` (инициатор), второй — `callee`.
- Все прикладные JSON-сообщения (`ring`, `offer`, `answer`, `bye`) просто
  пересылаются между двумя сокетами. Служебные события — `welcome`,
  `peer-joined`, `peer-left`, `error` (комната переполнена).
- Комната — больше двух сокетов не принимает.

## Деплой

1. Установите wrangler: `npm i -g wrangler` (или через `npx wrangler`).
2. Авторизуйтесь: `wrangler login` (браузер, аккаунт `Klischa85@gmail.com`).
3. Деплой из этой папки:

```bat
cd cloudflare
npx wrangler deploy
```

Эндпоинт после деплоя:
`https://p2pcall-signal.<ваш-subdomain>.workers.dev/ws?room=КОД`

В приложении в Настройках впишите этот адрес (без `/ws`, только хост):

```
https://p2pcall-signal.<ваш-subdomain>.workers.dev
```

и общий код комнаты у обоих телефонов — так же, как для Firebase-режима.

## Проверка без приложения

```js
// узел: WebSocket в Node 22+ доступен глобально
const a = new WebSocket("wss://…/ws?room=test");
const b = new WebSocket("wss://…/ws?room=test");
a.onmessage = (e) => console.log("A:", e.data);
b.onopen = () => b.send('{"type":"ring","from":"Боб"}');
```

`A` должен получить `{"type":"welcome","role":"caller"...}`, затем
`{"type":"peer-joined"...}`, затем пересланное `{"type":"ring"...}`.