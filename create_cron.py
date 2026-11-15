# -*- coding: utf-8 -*-
# 在青龙里创建钉钉CK机器人看门狗定时任务（幂等）
import json
import requests

cfg = json.load(open("/ql/data/bot/config.json", encoding="utf-8"))
base = cfg["ql_url"].rstrip("/")

auth = requests.get(
    f"{base}/open/auth/token",
    params={"client_id": cfg["ql_client_id"], "client_secret": cfg["ql_client_secret"]},
    timeout=10).json()
if auth.get("code") != 200:
    raise SystemExit(f"认证失败: {auth}")
token = auth["data"]["token"]
headers = {"Authorization": f"Bearer {token}"}

cmd = ("pgrep -f dingtalk_ck_bot.py >/dev/null || "
       "(cd /ql/data/bot && nohup python3 dingtalk_ck_bot.py >> bot.log 2>&1 &)")

d = requests.get(f"{base}/open/crons?searchValue=%E9%92%89%E9%92%89CK",
                 headers=headers, timeout=10).json().get("data") or []
if isinstance(d, dict):
    d = d.get("data") or []
existing = [e for e in d if isinstance(e, dict) and "看门狗" in (e.get("name") or "")]
if existing:
    print("看门狗任务已存在，id =", existing[0].get("id"))
else:
    r = requests.post(f"{base}/open/crons", headers=headers, timeout=10,
                      json={"name": "钉钉CK机器人看门狗", "command": cmd,
                            "schedule": "*/5 * * * *"}).json()
    print("创建结果:", r)
