# Свой TURN-сервер (coturn) на бесплатном VPS

WebRTC-звонок между телефонами в **разных сетях** часто не строится напрямую
(симметричный NAT у операторов) → в приложении «ICE failed». Решение — свой
**TURN-сервер (relay)**. Ниже — поднять его за ~10 минут на бесплатном VPS.

> ВАЖНО: TURN **обязательно нужен публичный IP**. На самом телефоне он работать
> не будет (телефон за NAT/CGNAT — до него не достучаться).

---

## 1. Бесплатный VPS с публичным IP
Подойдёт любой из (все дают публичный IPv4):
- **Oracle Cloud Always Free** — ARM Ampere A1 (бесплатно навсегда), или AMD micro.
- **Google Cloud** — `e2-micro` (free tier, зона us-central1/europe-west1 и т.п.).
- Любой дешёвый VPS (Hetzner CX11 и т.п.).

Нужна ОС **Ubuntu/Debian** и открытый вход по SSH.

## 2. Узнайте публичный IP сервера
```bash
curl https://api.ipify.org
```
Запомните его — это `PUBLIC_IP`.

## 3. Установка coturn
```bash
sudo apt update
sudo apt install -y coturn
```

## 4. Включить демона
```bash
echo 'TURNSERVER_ENABLED=1' | sudo tee -a /etc/default/coturn
```

## 5. Конфиг `/etc/turnserver.conf`
Откройте и приведите к виду (замените `PUBLIC_IP`, придумайте пользователя/пароль):
```bash
sudo tee /etc/turnserver.conf > /dev/null <<'EOF'
realm=p2pcall
server-name=turnserver
listening-ip=0.0.0.0
external-ip=PUBLIC_IP
fingerprint
lt-cred-mech
user=myuser:mypassword
total-quota=100
stale-nonce=600
no-tls
no-dtls
min-port=49152
max-port=65535
log-file=/var/log/turnserver.log
simple-log
EOF
```
- `user=myuser:mypassword` — **это логин/пароль, которые вы впишете в приложение**.
- `external-ip` — публичный IP сервера (обязательно, иначе relay будет кривым).

## 6. Перезапуск + автозапуск
```bash
sudo systemctl restart coturn
sudo systemctl enable coturn
sudo systemctl status coturn
```

## 7. Открыть порты в фаерволе
TURN использует:
- **3478/tcp** и **3478/udp** — основной порт;
- **49152–65535/udp** — диапазон relay-портов для медиа.

```bash
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 49152:65535/udp
sudo ufw reload
```
> Если VPS в облаке (Oracle/GCP) — откройте эти порты ещё и в **security group /
> firewall правилах консоли облака** (там отдельный фильтр поверх ОС).

## 8. Проверка
- Логи: `sudo tail -f /var/log/turnserver.log`
- Онлайн-тест: https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/
  впишите `turn:PUBLIC_IP:3478`, user/pass → должен вернуть `relay`-кандидат.

## 9. Вписать в приложение
В приложении: **Настройки** → включить «Использовать свой TURN»:
- **TURN-сервер:** `turn:PUBLIC_IP:3478`
- **Логин:** `myuser`
- **Пароль:** `mypassword`

Сохранить → новый звонок теперь пойдёт через ваш coturn.

---

## Примечания
- Для **TLS-TURN (turns:)** нужен домен + сертификат (Let's Encrypt). Для начала
  достаточно обычного `turn:` по 3478.
- Бесплатные тарифы VPS имеют скромный трафик — для длинных видеозвонков
  relays жрут много. Для тестов хватает.
- Альтернатива без своего сервера: платные managed-TURN (Twilio, Metered, Xirsys) —
  дают готовую строку `turn:...` с кредами.
