#!/usr/bin/env python3
"""Versioned installer and ephemeral local configuration forms. Python 3 standard library only."""
import argparse
import hashlib
import http.client
import http.server
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import secrets
import socket
import webbrowser

ROOT = Path(__file__).resolve().parent.parent
PRIVATE = tuple(ipaddress.ip_network(n) for n in
                ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8', 'fc00::/7', '::1/128'))


class SetupError(Exception):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_bundle(root=ROOT):
    checked = set()
    for line in (root / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        relative = Path(name)
        if (not re.fullmatch(r'[a-f0-9]{64}', digest) or relative.is_absolute()
                or '..' in relative.parts or name in checked):
            raise SetupError('校驗清單格式不正確。')
        target = root / relative
        if not target.resolve().is_relative_to(root.resolve()) or not target.is_file() or sha256(target) != digest:
            raise SetupError('檔案校驗失敗；請重新解壓完整安裝包。')
        checked.add(name)
    if not {'FreeMyTV.apk', 'tools/restore.py', 'tools/adb', 'version.json'}.issubset(checked):
        raise SetupError('安裝包缺少必要的校驗項目。')
    version = json.loads((root / 'version.json').read_text())
    if version.get('package') != 'io.freemytv' or version.get('apk_sha256') != sha256(root / 'FreeMyTV.apk'):
        raise SetupError('版本資料與安裝包不符。')
    return version


def adb_executable():
    supplied = os.environ.get('FREEMYTV_ADB')
    if supplied:
        return supplied
    if sys.platform == 'darwin':
        return str(ROOT / 'tools' / 'adb')
    value = shutil.which('adb')
    if not value:
        raise SetupError('此包附 Mac 版 adb；其他平台請先安裝 Android platform-tools。')
    return value


def run_adb(serial, *args, data=None, timeout=90):
    command = [adb_executable()]
    if serial:
        command += ['-s', serial]
    command += list(args)
    return subprocess.run(command, input=data, capture_output=True, timeout=timeout)


def television_serial(value):
    address = ipaddress.IPv4Address(value.strip())
    if not any(address in network for network in PRIVATE if network.version == 4) or address.is_loopback:
        raise SetupError('請輸入電視在區域網路中的 IPv4 位址。')
    return str(address) + ':5555'


def connect(serial):
    run_adb(None, 'connect', serial)
    result = run_adb(serial, 'get-state')
    if result.returncode or result.stdout.strip() != b'device':
        raise SetupError('尚未連上電視。請開啟 USB 調試、允許電視上的授權，再重試。')
    result = run_adb(serial, 'shell', 'id', '-u')
    if result.returncode or result.stdout.strip() != b'2000':
        raise SetupError('此工具需要一般 ADB shell；請勿使用 root 模式。')


def fetch(server, token, suffix):
    url = urllib.parse.urlsplit(server)
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password
            or url.query or url.fragment):
        raise ValueError()
    if url.scheme == 'http':
        addresses = {a[4][0] for a in socket.getaddrinfo(url.hostname, url.port or 80, type=socket.SOCK_STREAM)}
        if not addresses or any(not any(ipaddress.ip_address(a) in n for n in PRIVATE) for a in addresses):
            raise ValueError()
        connection = http.client.HTTPConnection(sorted(addresses)[0], url.port or 80, timeout=8)
    else:
        connection = http.client.HTTPSConnection(url.hostname, url.port or 443, timeout=8)
    try:
        connection.request('GET', (url.path.rstrip('/') or '/emby') + suffix,
                           headers={'Host': url.netloc, 'X-Emby-Token': token, 'Accept-Encoding': 'identity'})
        response = connection.getresponse()
        body = response.read(1048577)
        if response.status != 200 or len(body) > 1048576:
            raise ValueError()
        return json.loads(body)
    finally:
        connection.close()


def emby_payload(fields):
    server = fields['server'][0].strip().rstrip('/')
    token = fields['key'][0].strip()
    username = fields['user'][0].strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{16,256}', token):
        raise ValueError()
    if urllib.parse.urlsplit(server).path in ('', '/'):
        server += '/emby'
    info = fetch(server, token, '/System/Info')
    users = fetch(server, token, '/Users')
    matched = [user for user in users if user.get('Name') == username]
    if len(matched) != 1:
        raise ValueError()
    return json.dumps({'server_url': server, 'api_key': token, 'server_id': info['Id'],
                       'user_id': matched[0]['Id'], 'user_name': username, 'device_id': uuid.uuid4().hex}).encode()


def import_tmdb(serial, fields):
    key = fields['key'][0].strip()
    region = fields['region'][0].strip().upper()
    if not re.fullmatch(r'[A-Za-z0-9._=-]{16,4096}', key) or not re.fullmatch(r'[A-Z]{2}', region):
        raise ValueError()
    payload = json.dumps({'key': key, 'region': region}).encode()
    result = run_adb(serial, 'shell', 'content', 'write', '--uri',
                     'content://io.freemytv.private-settings/tmdb', data=payload)
    if result.returncode or b'Error' in result.stdout or b'Exception' in result.stderr:
        raise ValueError()
    for _ in range(20):
        result = run_adb(serial, 'shell', 'content', 'call', '--uri',
                         'content://io.freemytv.private-settings', '--method', 'status')
        if (result.returncode == 0 and b'configured=true' in result.stdout
                and b'encrypted=true' in result.stdout and b'temporary=false' in result.stdout
                and ('region=' + region).encode() in result.stdout):
            return
        time.sleep(0.2)
    raise ValueError()


def form(serial, mode):
    secret = secrets.token_urlsafe(32)
    state = {'payload': None, 'used': False, 'busy': False, 'done': False}
    lock = threading.Lock()

    class Page(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def reply(self, status, body, content='text/html; charset=utf-8'):
            data = body.encode('utf-8') if isinstance(body, str) else body
            self.send_response(status)
            self.send_header('Content-Type', content)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def origin(self):
            return 'http://127.0.0.1:' + str(self.server.server_port)

        def host_ok(self):
            return self.headers.get('Host') == '127.0.0.1:' + str(self.server.server_port)

        def do_GET(self):
            if not self.host_ok():
                return self.reply(403, 'Forbidden')
            if self.path == '/' + secret + '/config' and mode == 'emby':
                with lock:
                    if state['payload'] is None or state['used']:
                        return self.reply(404, 'Not found')
                    state['used'] = True
                    payload = state['payload']
                    state['payload'] = None
                return self.reply(200, payload, 'application/json')
            if self.path != '/' + secret or state['done']:
                return self.reply(404, 'Not found')
            fields = ('<label>伺服器網址<input name="server" type="url" required></label>'
                      '<label>Emby API 金鑰<input name="key" type="password" autocomplete="new-password" required></label>'
                      '<label>Emby 帳號名稱<input name="user" required></label>') if mode == 'emby' else (
                      '<label>TMDB API 金鑰／讀取權杖<input name="key" type="password" autocomplete="new-password" required></label>'
                      '<label>片庫地區（兩字母）<input name="region" value="HK" pattern="[A-Za-z]{2}" maxlength="2" required></label>')
            self.reply(200, '<!doctype html><meta charset="utf-8"><title>Free My TV · 私密設定</title>'
                       '<style>body{background:#202126;color:#fff;font:18px system-ui;max-width:580px;margin:6vh auto;padding:24px}'
                       'input,button{box-sizing:border-box;width:100%;padding:14px;margin:8px 0 24px;font:inherit}small{color:#bbb}</style>'
                       '<h1>' + ('連接 Emby' if mode == 'emby' else '設定 TMDB') + '</h1>'
                       '<p>本機一次性輸入頁；不寫入電腦檔案，不傳給第三方。</p>'
                       '<form method="post" autocomplete="off">' + fields + '<button>儲存至電視</button></form>'
                       '<small>' + ('先離開電視的 Emby 設定頁。伺服器會收到驗證請求；播放器密碼／PIN 請在 Emby 內輸入。' if mode == 'emby'
                       else '金鑰會由電視加密儲存；此步驟不執行 TMDB 連線測試。') + '</small>')

        def do_POST(self):
            if (not self.host_ok() or self.path != '/' + secret or self.headers.get('Origin') != self.origin()
                    or self.headers.get('Content-Type', '').split(';')[0] != 'application/x-www-form-urlencoded'):
                return self.reply(403, 'Forbidden')
            with lock:
                if state['busy'] or state['done']:
                    return self.reply(409, 'Already processing')
                state['busy'] = True
            reverse_port = None
            saved = False
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 32768:
                    raise ValueError()
                fields = urllib.parse.parse_qs(self.rfile.read(length).decode(), max_num_fields=4)
                with lock:
                    if state['done']:
                        raise ValueError()
                if mode == 'tmdb':
                    import_tmdb(serial, fields)
                else:
                    payload = emby_payload(fields)
                    with lock:
                        if state['done']:
                            raise ValueError()
                        state['payload'] = payload
                        state['used'] = False
                    reverse_port = 'tcp:' + str(self.server.server_port)
                    if run_adb(serial, 'reverse', reverse_port, reverse_port).returncode:
                        raise ValueError()
                    url = self.origin() + '/' + secret + '/config'
                    result = run_adb(serial, 'shell', 'am', 'broadcast', '-a', 'io.freemytv.EMBY_CONFIGURE',
                                     '-n', 'io.freemytv/.EmbyConfigReceiver', '--es', 'config_url', url)
                    if result.returncode or b'result=-1' not in result.stdout:
                        raise ValueError()
                with lock:
                    state['done'] = True
                saved = True
                self.reply(200, '<meta charset="utf-8"><h1>已儲存至電視</h1><p>可以關閉此頁。回到桌面後若尚未更新，重新打開 Free My TV。</p>')
            except Exception:
                self.reply(400, '<meta charset="utf-8"><h1>未確認儲存成功</h1><p>請核對輸入和調試連線，返回上一頁重試；不會顯示或記錄金鑰。</p>')
            finally:
                with lock:
                    state['payload'] = None
                    state['busy'] = False
                if reverse_port:
                    try:
                        run_adb(serial, 'reverse', '--remove', reverse_port)
                    except Exception:
                        pass
                if saved:
                    threading.Thread(target=self.server.shutdown, daemon=True).start()

    httpd = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Page)
    httpd.daemon_threads = True
    webbrowser.open('http://127.0.0.1:' + str(httpd.server_port) + '/' + secret)
    print('已開啟本機輸入頁；15 分鐘後失效，或按 Control+C 結束。')
    expiry = threading.Timer(900, httpd.shutdown)
    expiry.daemon = True
    expiry.start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        expiry.cancel()
        httpd.server_close()
        with lock:
            state['payload'] = None
            state['done'] = True


def install(serial):
    result = run_adb(serial, 'install', '--no-incremental', '-r', str(ROOT / 'FreeMyTV.apk'))
    if result.returncode or b'Success' not in result.stdout:
        raise SetupError('安裝未成功。請核對版本與簽名；不要直接卸載或清除資料。')
    if input('是否授權首頁調試開關？會授予 WRITE_SECURE_SETTINGS；按鈕僅切換開發者及 USB 調試。[y/N]：').strip().lower() == 'y':
        result = run_adb(serial, 'shell', 'pm', 'grant', 'io.freemytv', 'android.permission.WRITE_SECURE_SETTINGS')
        print('調試開關授權完成。' if result.returncode == 0 else '未確認授權成功；可用系統設定手動管理調試。')
    optional = ROOT / 'Emby-TV.apk'
    if optional.is_file() and input('是否安裝旁附的官方 Emby 電視版？簽名或降級失敗時不會清資料。[y/N]：').strip().lower() == 'y':
        result = run_adb(serial, 'install', '--no-incremental', '-r', str(optional))
        print('Emby 安裝完成。' if result.returncode == 0 and b'Success' in result.stdout else 'Emby 未安裝；原有資料保留。')
    result = run_adb(serial, 'shell', 'am', 'start', '-n', 'io.freemytv/.SettingsActivity')
    print('桌面安裝成功。請在電視選「主屏幕 → Free My TV」，依提示授權。')
    print('Emby／TMDB：設定主頁 → 推薦海報設定；五種風格：主題設定。')
    if result.returncode:
        print('設定頁未確認開啟，請從電視應用清單手動打開 Free My TV。')


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--emby', action='store_true')
    mode.add_argument('--tmdb', action='store_true')
    parser.add_argument('--check', action='store_true', help='只離線核對檔案，不連線或操作電視')
    parser.add_argument('--ip', help='電視目前的區域網路 IPv4 位址')
    options = parser.parse_args()
    version = verify_bundle()
    print('檔案校驗通過：' + version['version'])
    if options.check:
        return
    serial = television_serial(options.ip or input('電視目前的 IPv4 位址：'))
    connect(serial)
    if options.emby or options.tmdb:
        result = run_adb(serial, 'shell', 'pm', 'path', 'io.freemytv')
        if result.returncode or b'package:' not in result.stdout:
            raise SetupError('請先安裝 Free My TV。')
        return form(serial, 'emby' if options.emby else 'tmdb')
    install(serial)


if __name__ == '__main__':
    try:
        main()
    except SetupError as error:
        print(str(error))
        sys.exit(1)
    except (KeyboardInterrupt, EOFError):
        print('已停止。')
        sys.exit(1)
    except Exception:
        # Neither subprocess output nor server responses/form fields enter console errors.
        print('未完成。請核對完整安裝包、電視 IP、調試與授權；不會自動清除資料。')
        sys.exit(1)
