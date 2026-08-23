# Релизы (GitHub Releases)

В этом репозитории релизы **создаются автоматически** при пуше тега `v*`.
CI соберёт debug- и подписанные release-APK (с ABI-splits) и опубликует их в Release.

---

## Как создаётся новый релиз

### Вариант A — командой (рекомендуется) ✅

1. (Опционально) Поднимите версию в `app/build.gradle`:
   ```groovy
   versionCode 3          // +1 к предыдущему
   versionName "1.2"
   ```
2. Зафиксируйте изменения и запушьте в `main`:
   ```bash
   git commit -am "chore: bump version to 1.2"
   git push origin main
   ```
3. Создайте и запушьте тег **`v*`** (это и запускает публикацию релиза):
   ```bash
   git tag v1.2
   git push origin v1.2
   ```
4. Откройте вкладку **Actions** и дождитесь зелёного прогона.
5. Готово — релиз появится здесь:
   👉 https://github.com/Klischa/videossvyaz/releases

### Вариант B — через веб-интерфейс GitHub

1. **Releases → Draft a new release**.
2. **Choose a tag** → введите новый тег `v1.x` (GitHub создаст его на `main`).
3. Заполните «Release title» и описание, нажмите **Publish release**.
4. **Важно:** веб-форма сама не собирает APK — релиз с APK появится только если создание тега запустит воркфлоу (обычно запускается).
   Если APK не прикрепились автоматически — вверху релиза будет ссылка на прогон CI с артефактами.

---

## Что попадает в релиз

Вложениями идут все APK из `app/build/outputs/apk/`:

| Файл | Что это |
|------|---------|
| `app-arm64-v8a-release.apk` | **release, подписан** — для современных телефонов (~16 МБ) |
| `app-armeabi-v7a-release.apk` | release, подписан — старые 32-битные (~11 МБ) |
| `app-universal-release.apk` | release, подписан — любой устройство (~46 МБ) |
| `app-*-release.apk` (x86/x86_64) | release, подписан — эмуляторы |
| `app-*-debug.apk` | debug-сборки (с логами) |

## Подпись release-APK

Подписываются автоматически секретами репозитория (уже настроены):

| Секрет | Значение |
|--------|----------|
| `SIGNING_KEYSTORE_BASE64` | base64 keystore |
| `SIGNING_STORE_PASSWORD` | `android` |
| `SIGNING_KEY_ALIAS` | `p2pcall` |
| `SIGNING_KEY_PASSWORD` | `android` |

Если секреты **не заданы** — release-APK выйдут неподписанными (`*-release-unsigned.apk`).

### Проверить подпись локально
```bash
/path/to/android-sdk/build-tools/34.0.0/apksigner verify --print-certs app-arm64-v8a-release.apk
```
Ожидаемый сертификат: `CN=P2P Call, OU=Dev, O=Example, L=Amsterdam, ST=North Holland, C=NL`.

---

## Что «под капотом» (воркфлоу)

`.github/workflows/build.yml`:
- `on.push.tags: ['v*']` — триггер.
- JDK 17 + Android SDK → `./gradlew assembleDebug assembleRelease`.
- При `startsWith(github.ref, 'refs/tags/v')` выполняется:
  ```bash
  gh release create "$TAG" --title "$TAG" --generate-notes \
    $(find app/build/outputs/apk -name '*.apk')
  ```
  т.е. создаётся опубликованный Release с авто-описанием и всеми APK.

---

## Шпаргалка: «хочу новый релиз прямо сейчас»
```bash
git tag v1.X && git push origin v1.X
```
Подождать зелёный прогон в Actions — релиз готов.
