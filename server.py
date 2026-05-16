#!/usr/bin/env python3
"""
Location Upload Server for Hermes
手机浏览器获取 GPS 坐标 → 反解城市 → 更新 Hermes USER.md
Port: 18788
"""

import http.server
import json
import urllib.request
import urllib.parse
import re
import os
import html
import signal
import sys

USER_MD_PATH = "/opt/data/memories/USER.md"
PORT = 18788

# ── HTML 页面（内嵌 CSS + JS） ──────────────────────────────────────
PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
<title>Hermes 位置更新</title>
<style>
* {{ margin:0; padding:0; box-sizing:border-box; }}
body {{
    font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
    background: #0f172a; color: #e2e8f0; min-height: 100vh;
    display: flex; flex-direction: column; align-items: center;
    padding: 40px 20px;
}}
.card {{
    background: #1e293b; border-radius: 16px; padding: 32px;
    width: 100%; max-width: 420px; box-shadow: 0 8px 32px rgba(0,0,0,0.3);
}}
h1 {{ font-size: 22px; text-align: center; margin-bottom: 8px; color: #38bdf8; }}
.subtitle {{ text-align: center; font-size: 14px; color: #94a3b8; margin-bottom: 24px; }}
.btn {{
    display: block; width: 100%; padding: 16px; border: none; border-radius: 12px;
    font-size: 18px; font-weight: 600; cursor: pointer; transition: all 0.2s;
}}
.btn-primary {{ background: #38bdf8; color: #0f172a; }}
.btn-primary:hover {{ background: #7dd3fc; transform: translateY(-1px); }}
.btn-primary:disabled {{ background: #475569; color: #94a3b8; cursor: not-allowed; transform: none; }}
.btn-secondary {{ background: #334155; color: #e2e8f0; margin-top: 12px; }}
.btn-secondary:hover {{ background: #475569; }}
.status {{
    margin-top: 20px; padding: 16px; border-radius: 12px; text-align: center;
    font-size: 14px; line-height: 1.6; display: none;
}}
.status.loading {{ display: block; background: #1e3a5f; color: #7dd3fc; }}
.status.success {{ display: block; background: #064e3b; color: #6ee7b7; }}
.status.error {{ display: block; background: #450a0a; color: #fca5a5; }}
.coords {{ margin-top: 16px; padding: 12px; background: #0f172a; border-radius: 8px;
    font-family: 'SF Mono', 'Cascadia Code', monospace; font-size: 12px; text-align: center;
    color: #94a3b8; display: none; word-break: break-all; }}
.spinner {{ display: inline-block; width: 20px; height: 20px; border: 3px solid #475569;
    border-top: 3px solid #38bdf8; border-radius: 50%; animation: spin 0.8s linear infinite;
    margin-right: 8px; vertical-align: middle; }}
@keyframes spin {{ to {{ transform: rotate(360deg); }} }}
</style>
</head>
<body>
<div class="card">
    <h1>📍 位置更新</h1>
    <p class="subtitle">将当前位置同步到 Hermes 每日早报天气</p>

    <button class="btn btn-primary" id="locateBtn" onclick="getLocation()">
        📡 获取当前位置
    </button>
    <button class="btn btn-secondary" id="homeBtn" onclick="setHome()" style="display:none;">
        🏠 回到默认城市（东莞）
    </button>

    <div class="status" id="status"></div>
    <div class="coords" id="coords"></div>
</div>

<script>
function showStatus(msg, type) {{
    const el = document.getElementById('status');
    el.className = 'status ' + type;
    el.innerHTML = msg;
}}

function getLocation() {{
    const btn = document.getElementById('locateBtn');
    btn.disabled = true;
    btn.textContent = '⏳ 获取中...';
    document.getElementById('homeBtn').style.display = 'none';

    showStatus('<span class="spinner"></span>正在获取 GPS 定位...', 'loading');

    if (!navigator.geolocation) {{
        showStatus('❌ 浏览器不支持 Geolocation API<br>请使用 Chrome/Safari', 'error');
        btn.disabled = false;
        btn.textContent = '📡 获取当前位置';
        return;
    }}

    navigator.geolocation.getCurrentPosition(
        function(pos) {{
            const lat = pos.coords.latitude.toFixed(6);
            const lng = pos.coords.longitude.toFixed(6);
            document.getElementById('coords').style.display = 'block';
            document.getElementById('coords').textContent = '📍 ' + lat + ', ' + lng;

            showStatus('<span class="spinner"></span>正在反查城市...', 'loading');

            // 发送到服务器
            fetch('/location', {{
                method: 'POST',
                headers: {{ 'Content-Type': 'application/json' }},
                body: JSON.stringify({{ lat: parseFloat(lat), lng: parseFloat(lng) }})
            }})
            .then(r => r.json())
            .then(data => {{
                if (data.success) {{
                    showStatus('✅ 位置已更新<br>📍 ' + htmlEscape(data.city) + '<br>' +
                        '<span style="font-size:12px;color:#94a3b8;">' + htmlEscape(data.address) + '</span>', 'success');
                    document.getElementById('homeBtn').style.display = 'block';
                }} else {{
                    showStatus('❌ ' + htmlEscape(data.error || '更新失败'), 'error');
                }}
            }})
            .catch(err => {{
                showStatus('❌ 网络错误: ' + htmlEscape(err.message), 'error');
            }})
            .finally(() => {{
                btn.disabled = false;
                btn.textContent = '📡 重新定位';
            }});
        }},
        function(err) {{
            const msgs = {{
                1: '⛔ 定位权限被拒绝<br><span style="font-size:12px;">请在浏览器设置中允许位置访问</span>',
                2: '❌ 无法获取位置（信号弱）',
                3: '⏱ 定位超时<br><span style="font-size:12px;">请确保 GPS/WiFi 已开启</span>'
            }};
            showStatus(msgs[err.code] || '❌ 定位失败: ' + err.message, 'error');
            btn.disabled = false;
            btn.textContent = '📡 重新获取';
        }},
        {{
            enableHighAccuracy: true,
            timeout: 15000,
            maximumAge: 60000
        }}
    );
}}

function setHome() {{
    const btn = document.getElementById('homeBtn');
    btn.disabled = true;
    showStatus('<span class="spinner"></span>重置到默认城市...', 'loading');

    fetch('/location', {{
        method: 'POST',
        headers: {{ 'Content-Type': 'application/json' }},
        body: JSON.stringify({{ city: '东莞', reset: true }})
    }})
    .then(r => r.json())
    .then(data => {{
        if (data.success) {{
            showStatus('✅ 已重置到 🏠 东莞', 'success');
        }} else {{
            showStatus('❌ ' + htmlEscape(data.error), 'error');
        }}
    }})
    .catch(err => {{
        showStatus('❌ 网络错误: ' + htmlEscape(err.message), 'error');
    }})
    .finally(() => {{ btn.disabled = false; }});
}}

function htmlEscape(s) {{
    var div = document.createElement('div');
    div.appendChild(document.createTextNode(s));
    return div.innerHTML;
}}
</script>
</body>
</html>"""


# ── 定位服务 ─────────────────────────────────────────────────────────

def reverse_geocode(lat: float, lng: float) -> dict:
    """通过 Nominatim 反解坐标 → 城市名"""
    # zoom=8 返回地级市级别，避免 China 区/县层级混淆
    url = (
        f"https://nominatim.openstreetmap.org/reverse"
        f"?lat={lat}&lon={lng}&format=json&zoom=8&accept-language=zh"
    )
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "HermesLocationApp/1.0 (lizhiganglg@gmail.com)"}
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())

    address = data.get("address", {})

    # 优先取 city，次选 town，再不济 county
    city = (address.get("city")
            or address.get("town")
            or address.get("county")
            or address.get("state")
            or "未知")

    # 去掉 "市" 后缀统一格式 (南宁市 → 南宁)
    city = re.sub(r"市$", "", city)

    # 拼接完整地址显示用
    full_addr = ", ".join(filter(None, [
        address.get("state", ""),
        address.get("city", ""),
        address.get("town", ""),
        address.get("district", ""),
    ]))

    return {"city": city, "full_address": full_addr}


def update_user_md(new_city: str) -> bool:
    """更新 USER.md 中的出差城市"""
    if not os.path.exists(USER_MD_PATH):
        return False

    with open(USER_MD_PATH, "r", encoding="utf-8") as f:
        content = f.read()

    # 替换出差城市行
    # 匹配: 目前在南宁出差（家东莞）。每日早报天气用当前出差城市。
    pattern = r"目前在[^出（]+出差（家东莞）。每日早报天气用当前出差城市。"
    replacement = f"目前在{new_city}出差（家东莞）。每日早报天气用当前出差城市。"
    new_content = re.sub(pattern, replacement, content)

    if new_content == content:
        return False

    with open(USER_MD_PATH, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True


# ── HTTP Handler ─────────────────────────────────────────────────────

class LocationHandler(http.server.BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(PAGE_HTML.encode("utf-8"))
        elif self.path == "/health":
            self.send_json({"status": "ok", "city_file": USER_MD_PATH})
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != "/location":
            self.send_json({"success": False, "error": "Not found"}, 404)
            return

        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body.decode())
        except json.JSONDecodeError:
            self.send_json({"success": False, "error": "无效的 JSON"}, 400)
            return

        # 如果是重置到默认城市
        if data.get("reset"):
            city = data.get("city", "东莞")
            ok = update_user_md(city)
            if ok:
                self.send_json({"success": True, "city": city, "message": f"已重置到 {city}"})
            else:
                self.send_json({"success": False, "error": f"重置失败（USER.md 未匹配）"})
            return

        # GPS 定位模式
        lat = data.get("lat")
        lng = data.get("lng")
        if lat is None or lng is None:
            self.send_json({"success": False, "error": "缺少 lat/lng"}, 400)
            return

        try:
            result = reverse_geocode(float(lat), float(lng))
            city = result["city"]
            ok = update_user_md(city)
            if ok:
                self.send_json({
                    "success": True,
                    "city": city,
                    "address": result["full_address"],
                    "lat": lat,
                    "lng": lng,
                })
            else:
                self.send_json({
                    "success": True,
                    "city": city,
                    "address": result["full_address"],
                    "warning": f"城市已是最新或 USER.md 未匹配"
                })
        except Exception as e:
            self.send_json({"success": False, "error": f"反解失败: {str(e)}"}, 500)

    def send_json(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {args[0]} {args[1]} {args[2]}")


def signal_handler(sig, frame):
    print("\nShutting down location server...")
    sys.exit(0)


if __name__ == "__main__":
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    server = http.server.HTTPServer(("0.0.0.0", PORT), LocationHandler)
    print(f"🌍 Location app server running on http://0.0.0.0:{PORT}")
    print(f"📱 Open on phone: http://192.168.5.16:{PORT}")
    print(f"📝 Updates USER.md at: {USER_MD_PATH}")
    server.serve_forever()
