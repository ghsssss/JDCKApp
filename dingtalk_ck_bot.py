# -*- coding: utf-8 -*-
# 钉钉机器人接收 CK -> 更新青龙 JD_COOKIE
# 依赖: pip install dingtalk-stream requests
# 环境变量(在青龙环境变量里配):
#   DT_APP_KEY / DT_APP_SECRET       钉钉企业内部应用(机器人, Stream模式)的 AppKey/AppSecret
#   QL_URL                            青龙地址, 如 http://127.0.0.1:5700
#   QL_CLIENT_ID / QL_CLIENT_SECRET   青龙 OpenAPI 应用密钥(需"环境变量"权限)

import os
import re
import time
import json
import requests

import dingtalk_stream
from dingtalk_stream import AckMessage

# 优先读环境变量，否则读同目录 config.json
_cfg_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
_cfg = json.load(open(_cfg_path, encoding="utf-8")) if os.path.exists(_cfg_path) else {}

QL_URL = (os.environ.get("QL_URL") or _cfg.get("ql_url") or "").rstrip("/")
QL_CID = os.environ.get("QL_CLIENT_ID") or _cfg.get("ql_client_id") or ""
QL_SECRET = os.environ.get("QL_CLIENT_SECRET") or _cfg.get("ql_client_secret") or ""
DT_KEY = os.environ.get("DT_APP_KEY") or _cfg.get("dt_app_key") or ""
DT_SECRET = os.environ.get("DT_APP_SECRET") or _cfg.get("dt_app_secret") or ""

PIN_RE = re.compile(r"pt_pin=([^;&\s]+)")
KEY_RE = re.compile(r"pt_key=([^;&\s]+)")


def log(msg):
    print(time.strftime("[%Y-%m-%d %H:%M:%S] "), msg, flush=True)


def decode(s):
    from urllib.parse import unquote
    return unquote(s)


def split_segments(value):
    """青龙 JD_COOKIE 的多账号分隔符可能是换行或 &"""
    return [s.strip() for s in re.split(r"[\n&]", value) if s.strip()]


def pin_of(seg):
    m = PIN_RE.search(seg)
    return decode(m.group(1)) if m else None


class Qinglong:
    def __init__(self):
        self.token = None

    def _auth(self):
        # 青龙 2.24+ 认证端点：GET /open/auth/token
        r = requests.get(f"{QL_URL}/open/auth/token",
                         params={"client_id": QL_CID, "client_secret": QL_SECRET}, timeout=10)
        d = r.json()
        if d.get("code") != 200:
            raise RuntimeError(f"青龙认证失败: {d}")
        self.token = d["data"]["token"]

    def _req(self, method, path, body=None):
        if not self.token:
            self._auth()
        headers = {"Authorization": f"Bearer {self.token}"}
        r = requests.request(method, f"{QL_URL}{path}", json=body, headers=headers, timeout=15)
        if r.status_code == 401:  # token 失效重试一次
            self._auth()
            headers = {"Authorization": f"Bearer {self.token}"}
            r = requests.request(method, f"{QL_URL}{path}", json=body, headers=headers, timeout=15)
        d = r.json()
        if d.get("code") != 200:
            raise RuntimeError(f"青龙接口失败 {method} {path}: {d}")
        return d

    def get_envs(self):
        return self._req("GET", "/open/envs?searchValue=JD_COOKIE")["data"] or []

    def update(self, env_id, value):
        return self._req("PUT", "/open/envs",
                         {"id": env_id, "name": "JD_COOKIE", "value": value})

    def create(self, value):
        return self._req("POST", "/open/envs",
                         [{"name": "JD_COOKIE", "value": value}])

    def enable(self, env_ids):
        """青龙 2.24: PUT /open/envs/enable，body 为 id 数组，status 置为 normal(0)"""
        ids = [int(i) for i in env_ids if i]
        if not ids:
            return None
        return self._req("PUT", "/open/envs/enable", ids)


ql = Qinglong()


def ensure_enabled(env_id):
    """同步 CK 后确保变量处于非禁用状态"""
    try:
        ql.enable([env_id])
        return "（变量已启用）"
    except Exception as e:
        log(f"启用变量失败 id={env_id}: {e}")
        return "（⚠️ 变量启用失败，请手动检查）"


def merge_cks(new_ck):
    """把 new_ck 按 pt_pin 合并进 JD_COOKIE，返回结果描述"""
    new_pin = pin_of(new_ck)
    if not new_pin or not KEY_RE.search(new_ck):
        return "❌ 消息里没识别到 pt_key/pt_pin"

    envs = [e for e in ql.get_envs() if e.get("name") == "JD_COOKIE"]

    # 找该账号已存在的环境变量，优先选未禁用（status=0）的那条
    candidates = []
    for e in envs:
        for s in split_segments(e.get("value", "")):
            p = pin_of(s)
            if p and p.lower() == new_pin.lower():
                candidates.append(e)
                break
    target = None
    if candidates:
        target = next((e for e in candidates if str(e.get("status", 0)) == "0"),
                      candidates[0])

    if target:
        kept = [s for s in split_segments(target.get("value", ""))
                if not (pin_of(s) and pin_of(s).lower() == new_pin.lower())]
        kept.append(new_ck)
        ql.update(target["id"], "\n".join(kept))
        return f"✅ 已更新 {new_pin}" + ensure_enabled(target["id"])
    elif envs:
        segs = split_segments(envs[0].get("value", ""))
        segs.append(new_ck)
        ql.update(envs[0]["id"], "\n".join(segs))
        return f"✅ 已新增 {new_pin}" + ensure_enabled(envs[0]["id"])
    else:
        ql.create(new_ck)
        created = [e for e in ql.get_envs()
                   if e.get("name") == "JD_COOKIE"
                   and any((pin_of(s) or "").lower() == new_pin.lower()
                           for s in split_segments(e.get("value", "")))]
        extra = ensure_enabled(created[0]["id"]) if created else ""
        return f"✅ 已新建变量并写入 {new_pin}" + extra


class CKBotHandler(dingtalk_stream.ChatbotHandler):

    async def process(self, callback: dingtalk_stream.CallbackMessage):
        try:
            msg = dingtalk_stream.ChatbotMessage.from_dict(callback.data)
            text = msg.text.content.strip() if msg.text else ""
            text = re.sub(r"^@\S+\s*", "", text)  # 去掉 @机器人 前缀
            log(f"收到消息: {text[:80]}...")

            if "pt_key=" not in text:
                self.reply_text("发 CK 格式：pt_key=xxx;pt_pin=xxx; （多账号用 & 或换行连接）", msg)
                return AckMessage.STATUS_OK, 'OK'

            segments = split_segments(text.replace("；", ";"))
            results = []
            for seg in segments:
                if "pt_key=" not in seg:
                    continue
                try:
                    results.append(merge_cks(seg))
                except Exception as e:
                    results.append(f"❌ 处理失败: {e}")

            reply_text = "\n".join(results) if results else "❌ 没有识别到任何 CK"
            log(reply_text)
            self.reply_text(reply_text, msg)
        except Exception as e:
            log(f"异常: {e}")
            try:
                self.reply_text(f"❌ 出错: {e}", msg)
            except Exception:
                pass
        return AckMessage.STATUS_OK, 'OK'


def main():
    if not (DT_KEY and DT_SECRET and QL_URL and QL_CID and QL_SECRET):
        raise SystemExit("缺少配置: DT_APP_KEY/DT_APP_SECRET/QL_URL/QL_CLIENT_ID/QL_CLIENT_SECRET（环境变量或 config.json）")

    credential = dingtalk_stream.Credential(DT_KEY, DT_SECRET)
    client = dingtalk_stream.DingTalkStreamClient(credential)
    client.register_callback_handler(
        dingtalk_stream.chatbot.ChatbotMessage.TOPIC, CKBotHandler())
    log("钉钉 Stream 机器人已启动，等待 CK 消息...")
    client.start_forever()


if __name__ == "__main__":
    main()
