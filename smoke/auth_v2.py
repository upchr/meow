#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""公网部署 v2 鉴权冒烟（纯 HTTP，标准库）。

用法：
    python smoke/auth_v2.py --base http://127.0.0.1:30011 --user admin --password '...'
    python smoke/auth_v2.py --base https://meow.example.com --token <device-token> --config-key <key>

断言：
  1. /api/health 可匿名访问，且带 authMode/authRequired
  2. 无凭证访问 /api/config → 401
  3. 账号密码（或设备令牌）能访问 /api/config
  4. /config.json 无 key → 403；带 key → 200（后端配了 configKey 时）
  5. 媒体票：换取 mt 后 /api/proxy/stream 无票 401、带票 200
  6. 配对码（有会话时）：start → approve → poll 拿到设备令牌
"""
import argparse
import http.cookiejar
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

RESULTS = []
FAILS = 0


def req(opener, method, url, body=None, headers=None, timeout=30):
    h = {"User-Agent": "meow-auth-smoke/1.0"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with opener.open(r, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # noqa: BLE001
        return 0, str(e).encode()


def check(cond, name, evidence=""):
    global FAILS
    if isinstance(evidence, bytes):
        evidence = evidence.decode("utf-8", "replace")
    RESULTS.append((cond, name, evidence))
    if not cond:
        FAILS += 1
    print(("PASS " if cond else "FAIL ") + name + (" | " + evidence[:160] if evidence else ""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="web 入口或后端地址（不带尾斜杠）")
    ap.add_argument("--user", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--token", default="", help="设备令牌（X-Meow-Token）")
    ap.add_argument("--config-key", default="", help="CatVod configKey（可选）")
    ap.add_argument("--insecure", action="store_true", help="跳过 TLS 证书校验（自签证书）")
    ap.add_argument("--probe-url", default="https://www.baidu.com/", help="代理票验证用的公网 URL")
    ap.add_argument("--skip-pair", action="store_true")
    a = ap.parse_args()
    base = a.base.rstrip("/")
    ctx = ssl._create_unverified_context() if a.insecure else None
    handlers = [urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())]
    if ctx:
        handlers.append(urllib.request.HTTPSHandler(context=ctx))
    opener = urllib.request.build_opener(*handlers)
    anon = urllib.request.build_opener(*( [urllib.request.HTTPSHandler(context=ctx)] if ctx else [] ))  # 无 Cookie：验证匿名边界

    st, b = req(opener, "GET", base + "/api/health")
    try:
        h = json.loads(b)
    except Exception:  # noqa: BLE001
        h = {}
    check(st == 200 and h.get("ok") is True, "health 200/ok", f"{st} {b[:100]}")
    check("authMode" in h, "health 带 authMode", str(h.get("authMode")))
    auth_mode = h.get("authMode")
    check(auth_mode in ("password", "token-only"), "已开启鉴权", str(auth_mode))

    st, b = req(opener, "GET", base + "/api/config")
    check(st == 401, "无凭证 /api/config 401", str(st))

    token = a.token.strip()
    if a.user and a.password:
        st, b = req(opener, "POST", base + "/api/auth/login",
                    {"username": a.user, "password": a.password})
        check(st == 200, "登录 200", f"{st} {b[:120]}")
        st, b = req(opener, "GET", base + "/api/auth/me")
        check(st == 200 and json.loads(b).get("scope") == "full", "会话 /me full", f"{st} {b[:100]}")

    hdr = {"X-Meow-Token": token} if token else None
    st, b = req(opener, "GET", base + "/api/config", headers=hdr)
    check(st == 200, "凭证可访问 /api/config", f"{st} {b[:80]}")

    if a.config_key:
        st, _ = req(anon, "GET", base + "/config.json")
        check(st == 403, "config.json 无 key 403", str(st))
        st, _ = req(anon, "GET", base + "/config.json?key=" + urllib.parse.quote(a.config_key))
        check(st == 200, "config.json 带 key 200", str(st))

    st, b = req(opener, "POST", base + "/api/stream/ticket",
                {"kind": "proxy", "urls": []}, headers=hdr)
    mt = ""
    try:
        mt = json.loads(b).get("mt", "")
    except Exception:  # noqa: BLE001
        pass
    check(st == 200 and bool(mt), "换取媒体票", f"{st} mt={mt[:8]}")
    if mt:
        u = base + "/api/proxy/stream?url=" + urllib.parse.quote(a.probe_url, safe="")
        st, _ = req(anon, "GET", u)
        check(st == 401, "无票代理 401", str(st))
        st, _ = req(opener, "GET", u + "&mt=" + urllib.parse.quote(mt))
        check(st == 200, "带票代理 200", str(st))

    if not a.skip_pair:
        st, b = req(opener, "POST", base + "/api/auth/pair/start", {})
        pair = {}
        try:
            pair = json.loads(b)
        except Exception:  # noqa: BLE001
            pass
        check(st == 200 and bool(pair.get("code")), "配对码 start", f"{st} {b[:80]}")
        if pair.get("code") and (a.user or token):
            st, b = req(opener, "POST", base + "/api/auth/pair/approve",
                        {"code": pair["code"], "name": "auth-smoke"}, headers=hdr)
            check(st in (200, 401), "配对码 approve（401=纯令牌部署，预期）", str(st))
            if st == 200:
                st, b = req(opener, "GET", base + "/api/auth/pair/poll?pairId="
                            + urllib.parse.quote(pair.get("pairId", "")))
                ok = False
                try:
                    ok = bool(json.loads(b).get("token"))
                except Exception:  # noqa: BLE001
                    pass
                check(ok, "配对轮询拿到设备令牌", b[:100])

    print(f"\n{len(RESULTS) - FAILS} passed, {FAILS} failed")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
