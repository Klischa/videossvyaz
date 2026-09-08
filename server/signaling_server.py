# -*- coding: utf-8 -*-
"""
P2PCall — свой сигнальный сервер («комнаты») для Windows 7.

Замена облачному Firebase для режима «Позвонить (комната)».
Работает на ЛЮБОМ Python 2.7 / 3.4+ БЕЗ установки дополнительных
библиотек (только стандартная библиотека) — поэтому запускается даже
на Windows 7 с Python 3.8 (последняя версия Python для Win7).

REST API совместим с Firebase Realtime Database, поэтому приложение
Android работает с этим сервером БЕЗ перепрошивки протокола:
достаточно вписать в настройках адрес http://IP-компьютера:8080
вместо Firebase URL:

    PUT    /rooms/<код>/offer.json    тело: "закодированный SDP"
    GET    /rooms/<код>/offer.json    ответ: "...." или null
    PUT    /rooms/<код>/answer.json   тело: "закодированный SDP"
    GET    /rooms/<код>/answer.json   ответ: "...." или null
    DELETE /rooms/<код>.json          очистить комнату, ответ: null
    GET    /rooms/<код>.json          {"offer": ..., "answer": ...} или null

Служебные адреса:

    GET    /                          status-страница (для браузера)
    GET    /health                    {"status": "ok", "rooms": N, ...}
    GET    /rooms.json                сводка по комнатам (без содержимого SDP)

Запуск на Windows 7:

    python signaling_server.py --port 8080
    (или двойным кликом по start-server.bat)

Автор: P2PCall project. Лицензия: см. корень репозитория.
"""
from __future__ import print_function

import argparse
import io
import json
import os
import re
import socket
import sys
import threading
import time

try:  # Python 3
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from socketserver import ThreadingMixIn
    from urllib.parse import urlparse, unquote
except ImportError:  # Python 2.7 (очень старые машины)
    from BaseHTTPServer import BaseHTTPRequestHandler, HTTPServer
    from SocketServer import ThreadingMixIn
    from urlparse import urlparse
    from urllib import unquote

VERSION = "1.0"

# Защита от мусора: SDP в gzip+Base64 обычно 2–8 КБ.
# Лимиты с большим запасом, но не безграничные.
MAX_BODY_BYTES = 256 * 1024
MAX_VALUE_LEN = 200 * 1024
MAX_ROOM_LEN = 64

# /rooms/<код>.json  или  /rooms/<код>/{offer,answer}.json
ROOM_RE = re.compile(r"^/rooms/([^/]+?)(?:/(offer|answer))?\.json$")

store_lock = threading.RLock()
rooms = {}  # room -> {"offer": str|None, "answer": str|None, "updated": float}

started_at = time.time()
log_lock = threading.Lock()
log_file = None
options = None


# ---------------------------------------------------------------------------
# Логирование (безопасное для консоли Windows 7 с cp866/cp1251)
# ---------------------------------------------------------------------------

def log(msg):
    line = "[{0}] {1}".format(time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with log_lock:
        try:
            print(line)
            sys.stdout.flush()
        except UnicodeEncodeError:
            # Консоль Win7 не смогла вывести юникод — печатаем ASCII-вариант.
            try:
                print(line.encode("ascii", "backslashreplace").decode("ascii"))
                sys.stdout.flush()
            except Exception:
                pass
        except Exception:
            pass
        global log_file
        if log_file is not None:
            try:
                log_file.write(line + "\n")
                log_file.flush()
            except Exception:
                pass


def _esc(text):
    """Минимальное экранирование для HTML (работает и на py2, и на py3)."""
    s = str(text)
    return (s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace('"', "&quot;"))


# ---------------------------------------------------------------------------
# Хранилище комнат (+ сохранение на диск)
# ---------------------------------------------------------------------------

def data_path():
    if options is not None and options.data:
        return options.data
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "rooms.json")


def load_data():
    path = data_path()
    if not os.path.exists(path):
        return
    try:
        with io.open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            now = time.time()
            ttl = options.ttl if options else 1800
            with store_lock:
                for room, rec in data.items():
                    if not isinstance(rec, dict):
                        continue
                    updated = rec.get("updated", 0)
                    if now - updated > ttl:
                        continue  # протухшая комната — не грузим
                    rooms[room] = {
                        "offer": rec.get("offer"),
                        "answer": rec.get("answer"),
                        "updated": updated,
                    }
            log("Загружено комнат из {0}: {1}".format(path, len(rooms)))
    except Exception as e:
        log("Не удалось прочитать {0}: {1} (начинаем с пустого списка)".format(path, e))


def save_data():
    path = data_path()
    tmp = path + ".tmp"
    try:
        with store_lock:
            snapshot = dict(rooms)
        with io.open(tmp, "w", encoding="utf-8") as f:
            json.dump(snapshot, f, ensure_ascii=False)
        # Атомарная замена, чтобы rooms.json не бился при выключении света.
        if os.path.exists(path):
            os.remove(path)
        os.rename(tmp, path)
    except Exception as e:
        log("Не удалось сохранить {0}: {1}".format(path, e))


def valid_room(room):
    """Код комнаты: 1..64 символа, без слешей и '..' (можно кириллицу)."""
    if not room or len(room) > MAX_ROOM_LEN:
        return False
    if "/" in room or "\\" in room or room in (".", ".."):
        return False
    if ".." in room:
        return False
    return True


def is_expired(rec):
    ttl = options.ttl if options else 1800
    return (time.time() - rec.get("updated", 0)) > ttl


def get_room(room):
    with store_lock:
        rec = rooms.get(room)
        if rec is None:
            return None
        if is_expired(rec):
            del rooms[room]
            return None
        return rec


def cleanup_loop():
    """Фоновая чистка протухших комнат раз в минуту."""
    while True:
        time.sleep(60)
        try:
            with store_lock:
                dead = [r for r, rec in rooms.items() if is_expired(rec)]
                for r in dead:
                    del rooms[r]
            if dead:
                log("Удалено протухших комнат: {0}".format(len(dead)))
                save_data()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# HTTP-обработчик
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "P2PCallSignaling/" + VERSION

    def log_message(self, fmt, *args):
        log("{0} - {1}".format(self.address_string(), fmt % args))

    # -- helpers ------------------------------------------------------------

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def send_html(self, html, code=200):
        body = html.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            length = 0
        if length <= 0:
            return ""
        if length > MAX_BODY_BYTES:
            return None  # слишком большое тело
        try:
            raw = self.rfile.read(length)
        except Exception:
            return ""
        if isinstance(raw, bytes):
            return raw.decode("utf-8", "replace")
        return raw

    def parse_value(self, body):
        """Тело PUT: JSON-строка "...." (как шлёт приложение).

        Возвращает (ok, value, error)."""
        if body is None:
            return False, None, "body too large"
        text = body.strip()
        if not text:
            return False, None, "empty body"
        value = None
        try:
            parsed = json.loads(text)
            if isinstance(parsed, str):
                value = parsed
            elif parsed is None:
                value = None  # явный null = стереть поле
            else:
                return False, None, "body must be a JSON string"
        except (ValueError, TypeError):
            # Либеральный режим: сырой base64 без кавычек тоже примем.
            value = text.strip('"')
        if value is not None and len(value) > MAX_VALUE_LEN:
            return False, None, "value too large"
        return True, value, None

    def route_room(self):
        """Разбирает путь. Возвращает (room, field) или (None, None)."""
        path = urlparse(self.path).path
        m = ROOM_RE.match(path)
        if not m:
            return None, None
        room = unquote(m.group(1))
        field = m.group(2)  # offer / answer / None (= вся комната)
        if not valid_room(room):
            return None, None
        return room, field

    # -- methods ------------------------------------------------------------

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_HEAD(self):
        # Для uptime-мониторов: только заголовки, без тела.
        path = urlparse(self.path).path
        known = path in ("/", "/index.html", "/health", "/rooms.json")
        if not known:
            room, _ = self.route_room()
            known = room is not None
        self.send_response(200 if known else 404)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self._cors()
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/" or path == "/index.html":
            self.serve_status()
            return
        if path == "/health":
            with store_lock:
                n = len(rooms)
            self.send_json({
                "status": "ok",
                "version": VERSION,
                "rooms": n,
                "uptime_sec": int(time.time() - started_at),
            })
            return
        if path == "/rooms.json":
            # Сводка БЕЗ содержимого SDP (чтобы ключи не светились).
            now = time.time()
            with store_lock:
                summary = {}
                for room, rec in rooms.items():
                    if is_expired(rec):
                        continue
                    summary[room] = {
                        "has_offer": rec.get("offer") is not None,
                        "has_answer": rec.get("answer") is not None,
                        "age_sec": int(now - rec.get("updated", now)),
                    }
            self.send_json(summary)
            return
        room, field = self.route_room()
        if room is None:
            self.send_json({"error": "not found"}, 404)
            return
        rec = get_room(room)
        if field is None:
            # Вся комната (как Firebase: {"offer": ..., "answer": ...} или null).
            if rec is None:
                self.send_json(None)
            else:
                self.send_json({"offer": rec.get("offer"), "answer": rec.get("answer")})
        else:
            self.send_json(rec.get(field) if rec else None)

    def do_PUT(self):
        self.handle_write()

    def do_POST(self):
        # POST принимаем как синоним PUT (на всякий случай).
        self.handle_write()

    def handle_write(self):
        room, field = self.route_room()
        if room is None or field is None:
            self.send_json({"error": "use /rooms/<code>/offer.json or .../answer.json"}, 404)
            return
        ok, value, err = self.parse_value(self.read_body())
        if not ok:
            self.send_json({"error": err}, 400)
            return
        with store_lock:
            rec = rooms.get(room)
            if rec is None or is_expired(rec):
                rec = {"offer": None, "answer": None, "updated": time.time()}
                rooms[room] = rec
            rec[field] = value
            rec["updated"] = time.time()
        save_data()
        log("Комната '{0}': записан {1} ({2} символов)".format(
            room, field, len(value) if value else 0))
        # Отвечаем как Firebase — эхом записанного значения.
        self.send_json(value)

    def do_DELETE(self):
        room, field = self.route_room()
        if room is None:
            self.send_json({"error": "not found"}, 404)
            return
        with store_lock:
            if field is None:
                rooms.pop(room, None)
            elif room in rooms:
                rooms[room][field] = None
                rooms[room]["updated"] = time.time()
        save_data()
        log("Комната '{0}': очищена ({1})".format(room, field or "целиком"))
        self.send_json(None)

    # -- status page --------------------------------------------------------

    def serve_status(self):
        now = time.time()
        uptime = int(now - started_at)
        with store_lock:
            items = sorted(rooms.items())
        rows = []
        for room, rec in items:
            if is_expired(rec):
                continue
            age = int(now - rec.get("updated", now))
            rows.append(
                "<tr><td>{0}</td><td>{1}</td><td>{2}</td><td>{3} сек назад</td></tr>".format(
                    _esc(room),
                    "да" if rec.get("offer") else "—",
                    "да" if rec.get("answer") else "—",
                    age))
        if not rows:
            rows.append('<tr><td colspan="4">пока пусто — комнаты появятся при звонках</td></tr>')
        html = """<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<title>P2PCall signaling — работает</title>
<style>body{{font-family:sans-serif;max-width:700px;margin:40px auto;padding:0 16px;}}
table{{border-collapse:collapse;width:100%;}}td,th{{border:1px solid #ccc;padding:6px 10px;}}
.ok{{color:#2e7d32;font-weight:bold;}}</style></head>
<body><h1>P2PCall signaling <span class="ok">● работает</span></h1>
<p>Версия {ver}. Аптайм: {up} сек. Комнат: {n}.</p>
<p>На телефонах в настройках укажите адрес этого сервера:
<b>http://{ip}:{port}</b></p>
<table><tr><th>Комната</th><th>offer</th><th>answer</th><th>Обновлена</th></tr>
{rows}</table>
<p><small>Проверка: <a href="/health">/health</a></small></p>
</body></html>""".format(ver=_esc(VERSION), up=uptime, n=len(rows) if rows else 0,
                         ip=_esc(lan_ip() or "IP-КОМПЬЮТЕРА"),
                         port=(options.port if options else 8080),
                         rows="\n".join(rows))
        self.send_html(html)


class ThreadedServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# ---------------------------------------------------------------------------
# Утилиты запуска
# ---------------------------------------------------------------------------

def lan_ip():
    """Локальный IP компьютера в сети (который вписывать в телефоны)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        pass
    try:
        return socket.gethostbyname(socket.gethostname())
    except Exception:
        return None


def parse_args(argv):
    p = argparse.ArgumentParser(
        description="P2PCall signaling server (rooms) — работает на Windows 7")
    p.add_argument("--host", default="0.0.0.0",
                   help="адрес прослушивания (по умолчанию 0.0.0.0 — все интерфейсы)")
    p.add_argument("--port", type=int, default=8080,
                   help="порт (по умолчанию 8080)")
    p.add_argument("--data", default="",
                   help="файл хранения комнат (по умолчанию rooms.json рядом со скриптом)")
    p.add_argument("--ttl", type=int, default=1800,
                   help="время жизни комнаты без обновлений, сек (по умолчанию 1800)")
    p.add_argument("--log", default="",
                   help="файл лога (по умолчанию server.log рядом со скриптом)")
    return p.parse_args(argv)


def main(argv):
    global options, log_file
    options = parse_args(argv)

    here = os.path.dirname(os.path.abspath(__file__))
    if not options.data:
        options.data = os.path.join(here, "rooms.json")
    if not options.log:
        options.log = os.path.join(here, "server.log")
    try:
        log_file = io.open(options.log, "a", encoding="utf-8")
    except Exception as e:
        print("Не удалось открыть лог {0}: {1}".format(options.log, e))

    load_data()

    cleaner = threading.Thread(target=cleanup_loop)
    cleaner.daemon = True
    cleaner.start()

    try:
        server = ThreadedServer((options.host, options.port), Handler)
    except Exception as e:
        log("НЕ УДАЛОСЬ ЗАНЯТЬ {0}:{1} — {2}".format(options.host, options.port, e))
        log("Порт уже занят? Попробуйте: python signaling_server.py --port 8081")
        return 1

    ip = lan_ip()
    log("=" * 60)
    log("P2PCall signaling v{0} запущен!".format(VERSION))
    log("Слушаю: http://{0}:{1}".format(options.host, options.port))
    if ip:
        log("В настройках телефонов укажите сервер: http://{0}:{1}".format(ip, options.port))
    else:
        log("Не смог определить IP — узнайте через ipconfig и укажите http://IP:{0}".format(options.port))
    log("Проверка в браузере: http://127.0.0.1:{0}/health".format(options.port))
    log("Данные: {0} | TTL комнат: {1} сек".format(options.data, options.ttl))
    log("Остановка: Ctrl+C. Для выхода также можно просто закрыть окно.")
    log("=" * 60)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        log("Останавливаюсь, сохраняю комнаты...")
        try:
            save_data()
        except Exception:
            pass
        try:
            server.server_close()
        except Exception:
            pass
        if log_file is not None:
            try:
                log_file.close()
            except Exception:
                pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
