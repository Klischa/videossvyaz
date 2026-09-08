# -*- coding: utf-8 -*-
"""
Быстрая проверка сигнального сервера (тоже только стандартная библиотека).

Использование на Windows 7:

    python test_room.py http://127.0.0.1:8080 моя-комната

Проверяет: /health, запись/чтение offer и answer, очистку комнаты.
Код возврата 0 = всё хорошо.
"""
from __future__ import print_function

import json
import sys

try:  # Python 3
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:  # Python 2.7
    from urllib2 import Request, urlopen, HTTPError, URLError

try:
    from urllib.parse import quote
except ImportError:
    from urllib import quote

failures = 0


def check(name, cond, extra=""):
    global failures
    status = "OK  " if cond else "FAIL"
    if not cond:
        failures += 1
    print("[{0}] {1} {2}".format(status, name, extra))


def http(method, url, body=None):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = Request(url, data=data, headers=headers)
    req.get_method = lambda: method
    try:
        resp = urlopen(req, timeout=10)
        code = resp.getcode()
        text = resp.read().decode("utf-8", "replace")
        return code, text
    except HTTPError as e:
        try:
            text = e.read().decode("utf-8", "replace")
        except Exception:
            text = ""
        return e.code, text
    except URLError as e:
        return -1, "connection error: {0}".format(e)
    except Exception as e:
        return -1, "error: {0}".format(e)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
    room = sys.argv[2] if len(sys.argv) > 2 else "test-room"
    base = base.rstrip("/")
    room_q = quote(room.encode("utf-8") if isinstance(room, str) else room)
    offer_url = "{0}/rooms/{1}/offer.json".format(base, room_q)
    answer_url = "{0}/rooms/{1}/answer.json".format(base, room_q)
    room_url = "{0}/rooms/{1}.json".format(base, room_q)

    print("Сервер: {0}".format(base))
    print("Комната: {0}".format(room))

    code, text = http("GET", base + "/health")
    check("GET /health", code == 200 and '"ok"' in text, "({0})".format(code))

    code, _ = http("DELETE", room_url)
    check("DELETE комнаты", code == 200, "({0})".format(code))

    code, text = http("GET", offer_url)
    check("GET offer пустой -> null", code == 200 and text.strip() == "null",
          "({0} {1})".format(code, text.strip()[:40]))

    code, _ = http("PUT", offer_url, "dGVzdC1vZmZlci1zZHAtMTIz")
    check("PUT offer", code == 200, "({0})".format(code))

    code, text = http("GET", offer_url)
    ok = code == 200
    try:
        ok = ok and json.loads(text) == "dGVzdC1vZmZlci1zZHAtMTIz"
    except Exception:
        ok = False
    check("GET offer совпадает", ok, "({0})".format(code))

    code, _ = http("PUT", answer_url, "dGVzdC1hbnN3ZXItc2RwLTQ1Ng")
    check("PUT answer", code == 200, "({0})".format(code))

    code, text = http("GET", answer_url)
    ok = code == 200
    try:
        ok = ok and json.loads(text) == "dGVzdC1hbnN3ZXItc2RwLTQ1Ng"
    except Exception:
        ok = False
    check("GET answer совпадает", ok, "({0})".format(code))

    code, _ = http("DELETE", room_url)
    check("DELETE комнаты", code == 200, "({0})".format(code))

    code, text = http("GET", offer_url)
    check("GET offer после очистки -> null", code == 200 and text.strip() == "null",
          "({0})".format(code))

    print("----")
    if failures:
        print("ИТОГ: ОШИБОК: {0}".format(failures))
        return 1
    print("ИТОГ: все проверки пройдены, сервер работает корректно")
    return 0


if __name__ == "__main__":
    sys.exit(main())
