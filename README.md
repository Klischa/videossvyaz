# P2P Call — видеозвонок без сервера (WebRTC + обмен ссылками)

Android-приложение на Kotlin для прямых (P2P) видеозвонков через WebRTC.
**Серверная часть (сигналинг) не используется:** обмен SDP и ICE-кандидатами
происходит через обычные ссылки, которые пользователи отправляют друг другу в
любом мессенджере (Telegram, WhatsApp, Max и т.п.).

Соединение устанавливается после обмена **двумя** ссылками:
первая содержит `offer`, вторая — `answer`.

---

## Как это работает

```
Алиса (инициатор)                         Боб (принимающий)
─────────────────────                     ─────────────────────
1. «Создать звонок»
2. генерирует offer → ссылка ───────────► 3. открывает ссылку (deep link)
                                          4. генерирует answer → ссылка
5. вставляет ответную ссылку ◄─────────── 
6. applyAnswer → соединение ◄════════════► 7. onConnected
```

Ключевая особенность — **отключённый trickle ICE**: все ICE-кандидаты
включаются прямо в SDP (мы ждём `IceGatheringState.COMPLETE`), поэтому
достаточно одной ссылки на сторону. Чтобы ссылка не была огромной, SDP
сжимается через **GZIP** и кодируется в **Base64 (URL-safe)**.

---

## Архитектура

| Слой | Класс | Назначение |
|------|-------|------------|
| Конфигурация | `config/AppConfig` | Домен ссылки, ICE-серверы (STUN/TURN), таймауты |
| Сигналинг | `signaling/SdpCodec` | gzip/Base64, сборка и парсинг ссылок |
| WebRTC | `webrtc/WebRtcManager` | PeerConnectionFactory/PeerConnection, медиапотоки, offer/answer без trickle |
| WebRTC | `webrtc/WebRtcController` | Процесс-синглтон: переживает переключение Activity |
| WebRTC | `webrtc/CallRole`, `CallMode` | Роли и режимы запуска экрана |
| UI | `ui/MainActivity` | Главный экран + маршрутизация deep link |
| UI | `ui/CallActivity` | Экран звонка (видео, кнопки, сигнальные панели) |

**Почему `CallActivity` — `singleTask`?** Ответная ссылка может прийти, когда
инициатор уже ждёт на экране звонка. В этом случае срабатывает `onNewIntent`
и переиспользуется тот же `PeerConnection` с уже созданным `offer`.

---

## Структура проекта

```
P2PCallApp/
├── settings.gradle
├── build.gradle                 # корневой
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/example/p2pcall/
        │   ├── config/AppConfig.kt
        │   ├── signaling/SdpCodec.kt
        │   ├── webrtc/WebRtcManager.kt
        │   ├── webrtc/WebRtcController.kt
        │   ├── webrtc/CallRole.kt
        │   └── ui/
        │       ├── MainActivity.kt
        │       └── CallActivity.kt
        └── res/
            ├── drawable/ic_launcher.xml
            ├── layout/activity_main.xml
            ├── layout/activity_call.xml
            └── values/{strings,colors,themes}.xml
```

---

## Сборка

> Требуется **Android Studio** (Giraffe+) и **JDK 17**. Gradle 8.5.

1. Откройте папку `P2PCallApp` в Android Studio.
2. Дождитесь синхронизации Gradle.
3. Подключите устройство (API 24+, с камерой) и нажмите **Run**.

Если открываете проект в консоли — сгенерируйте обёртку Gradle:
```bash
cd P2PCallApp
gradle wrapper --gradle-version 8.5     # либо используйте системный gradle
./gradlew assembleDebug
```

`local.properties` (путь к SDK) Android Studio создаст сам. При ручной сборке
укажите: `sdk.dir=/путь/к/Android/Sdk`.

---

## Зависимость WebRTC

Артефакт `org.webrtc:google-webrtc:1.0.32006` изначально публиковался в **JCenter**.
JCenter переведён в режим read-only, но старые артефакты пока доступны.
В `settings.gradle` уже подключены `mavenCentral()` и зеркало `jitpack`.

Если сборка падает с ошибкой резолва зависимости, выберите один из вариантов:

**Вариант A** — подключить legacy jcenter (read-only):
```groovy
// settings.gradle → dependencyResolutionManagement.repositories
maven { url 'https://jcenter.bintray.com' }
// или просто: jcenter()
```

**Вариант B** — использовать поддерживаемый форк (рекомендуется для новых сборок):
```groovy
// app/build.gradle
// implementation 'org.webrtc:google-webrtc:1.0.32006'   // закомментировать
implementation 'io.getstream:stream-webrtc-android:1.0.8' // поддерживаемый форк
```
Форк `io.getstream:stream-webrtc-android` имеет совместимый API `org.webrtc.*`,
поэтому менять код не потребуется.

---

## Настройка

### 1. Домен / схема ссылки
В `config/AppConfig.kt` поменяйте `BASE_URL` на свой домен:
```kotlin
const val BASE_URL = "https://my-real-domain.com/call"
```
И приведите в соответствие `AndroidManifest.xml`:
```xml
<data android:scheme="https" android:host="my-real-domain.com" android:path="/call" />
```
Для теста без домена удобно использовать кастомную схему (уже подключена):
```
myapp://call?type=offer&sdp=...
```

### 2. App Links (чтобы мессенджеры открывали ссылку прямо в приложении)
Чтобы `https://…/call` открывался в приложении без диалога выбора браузера,
опубликуйте `assetlinks.json` на сервере и установите
`android:autoVerify="true"` в intent-filter. См. официальную документацию
Android App Links.

### 3. TURN-сервер (для симметричных NAT)
STUN `stun.l.google.com` работает в большинстве случаев, но если оба абонента
за симметричным NAT — нужен TURN. В `AppConfig.ICE_SERVERS` уже есть пример
(закомментирован) — подставьте свой coturn:
```kotlin
PeerConnection.IceServer.builder("turn:turn.example.com:3478")
    .setUsername("user")
    .setPassword("pass")
    .createIceServer()
```

---

## Использование

**Инициатор**
1. На главном экране — «Создать звонок».
2. Дождаться генерации ссылки → «Поделиться» → отправить собеседнику.
3. Получить от собеседника ответную ссылку → вставить в поле внизу →
   «Установить соединение». Либо просто открыть ответную ссылку (deep link).

**Принимающий**
1. Открыть присланную ссылку (приложение откроется автоматически).
2. Приложение сгенерирует ответ и **само скопирует его в буфер** +
   предложит «Поделиться» → отправить обратно инициатору.
3. Дождаться соединения.

**Управление звонком:** переключение камеры, микрофон вкл/выкл, видео вкл/выкл,
завершить звонок.

---

## Тестирование

- Запустите на **двух реальных устройствах** (эмуляторы плохо работают с камерой/NAT).
- Проверьте сценарии: оба в Wi-Fi; один в мобильном интернете; разные сети.
- Если соединение не строится — вероятнее всего нужен TURN (см. выше).
- Длина ссылки: SDP сжат gzip+Base64, обычно помещается в одно сообщение мессенджера.

---

## Обработка ошибок

- **Нет разрешений** (камера/микрофон) → запрос и поясняющий тост.
- **Повреждённая ссылка** → тост «Неверная или повреждённая ссылка».
- **Сбой WebRTC** → сообщение с причиной, возврат на главный экран.
- **Разрыв соединения** (`ICE failed/disconnected`) → статус и возврат.
- **Потеря сессии** (процесс убит, а пришла ответная ссылка) →
  тост «Сессия потеряна, создайте звонок заново».

---

## Известные ограничения (MVP)

- Кросс-процессное восстановление offer'а не реализовано: если систему
  убила приложение инициатора до получения ответа, звонок нужно создать заново.
- Шифрование SDP не применяется (как и требовалось в задании). При желании
  можно добавить симметричное шифрование по паролю в `SdpCodec`.
- STUN-only; TURN подключается вручную (см. настройку).
