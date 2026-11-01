#!/usr/bin/env python3
"""主机实测扫描的本机小服务（tools/kbd-scan.sh 拉起）：给主机上的记录页（scan.html）发页面和扫描计划，
页面每收完一段就让这边用 adb 叫手机发下一段（DebugMoves 的 DEBUG_KEYSCAN 广播），结果存进 <输出目录>/scan.json。

  GET  /<口令>/        页面 scan.html
  GET  /<口令>/plan    扫描计划：每一步发哪个键（用法码 + 修饰键）、期望出什么字（给页面边扫边显示、扫第三层前核一下主机布局）
  POST /<口令>/send    {from}：叫手机发 from 开始的一段（最多 --chunk 个，不跨进第三层），回 {to}
  POST /<口令>/stop    叫手机停（页面失去焦点、卡住时）
  POST /<口令>/save    {records, complete}：存 scan.json；complete 时服务退出
  POST /<口令>/log     页面上的状态（暂停、卡住……）在终端里打一行

为什么一段一段发：手机那边一次最多发 12 个键；页面失去焦点（点到别处、锁屏）或者这个脚本被打断，最多再多出一段，
不会把一长串字和空格、Tab 打进别的应用里。地址里带一串随机口令：--lan 时同一局域网的别人猜不到地址。
用法：python3 scan_server.py <输出目录> --host mac|ipad|win|linux|android --layout us|de [--lan] [--hold 毫秒] [--gap 毫秒]
                            [--chunk 个数] [--dry-run]
  --lan：听所有网卡（iPad、别的电脑上打开页面）；默认只听 127.0.0.1（这台电脑自己、桌面模式经 adb reverse）。
  --dry-run：不调 adb，只在终端里打出要发什么（看页面长什么样用）。
地址写进 <输出目录>/url.txt 给 kbd-scan.sh 读。
"""
import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kbdtable as kt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ACTION = 'dev.douhua.DEBUG_KEYSCAN'
PHONE_MAX = 12   # 手机那边一次最多收几个键（KeyScan.MAX，两边要一起改）

ap = argparse.ArgumentParser()
ap.add_argument('out')
ap.add_argument('--host', required=True, choices=sorted(kt.HOSTS))
ap.add_argument('--layout', required=True)
ap.add_argument('--lan', action='store_true')
ap.add_argument('--hold', type=int, default=40)
ap.add_argument('--gap', type=int, default=70)
ap.add_argument('--chunk', type=int, default=6)
ap.add_argument('--dry-run', action='store_true')
args = ap.parse_args()
args.chunk = max(1, min(PHONE_MAX, args.chunk))

platform = kt.HOSTS[args.host]
tables = kt.load_tables()
catalog = {r['id']: r for r in kt.read_catalog()}
if args.layout not in catalog:
    sys.exit('数据里没有布局 %s（有：%s）' % (args.layout, '、'.join(catalog)))
third = kt.has_third_layer(tables, platform, args.layout)
steps = kt.scan_plan(args.host, args.layout, third)
table = tables[platform][args.layout]
iso_table = tables[platform].get(kt.section_name(platform, args.layout, True), table)
l3_start = next((s['i'] for s in steps if s['layer'] >= 2), len(steps))


def expected(t, s):
    u = 0x31 if s['usage'] == 0x32 else s['usage']   # 0x32 期望和 0x31 一样（要核的前提）
    if u == kt.KEYPAD_DECIMAL:
        return [catalog[args.layout]['kpdl'], False] if s['layer'] == 0 else None
    c = t.get(u, [None] * 4)[s['layer']]
    return list(c) if c else None


plan = {
    'host': args.host, 'hostName': kt.HOST_NAMES[args.host], 'platform': platform, 'layout': args.layout,
    'layoutName': catalog[args.layout]['name_' + platform], 'layers': list(kt.layer_names(platform)),
    'third': third, 'l3Start': l3_start, 'checkBeforeL3': platform != 'mac',
    'chunk': args.chunk, 'stepMs': 3 * (args.hold + args.gap),
    'steps': [dict(s, label=kt.usage_label(s['usage']), code=kt.HID_TO_CODE.get(s['usage'], ''),
                   exp=expected(table, s), expIso=expected(iso_table, s)) for s in steps],
}
token = secrets.token_hex(8)
adb_lock = threading.Lock()
save_lock = threading.Lock()


def adb_broadcast(extra):
    """叫手机（ANDROID_SERIAL 由 kbd-scan.sh 选好）收一个 DEBUG_KEYSCAN 广播。返回 None = 成功，否则是原因。"""
    cmd = ['adb', 'shell', 'am', 'broadcast', '-a', ACTION] + extra
    if args.dry_run:
        print('  （--dry-run，不发）' + ' '.join(cmd), flush=True)
        return None
    with adb_lock:
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
        except (OSError, subprocess.TimeoutExpired) as e:
            return 'adb 没跑起来：%s' % e
    if r.returncode != 0 or 'Broadcast completed' not in r.stdout:
        return 'adb 广播失败（手机断开了？）：%s' % (r.stderr.strip() or r.stdout.strip())[:200]
    return None


def send_chunk(start):
    end = min(start + args.chunk, len(steps))
    if start < l3_start < end:
        end = l3_start   # 第三层另起一段：页面先核一下主机布局，再决定扫不扫
    keys = ','.join('%x' % s['key'] for s in steps[start:end])
    err = adb_broadcast(['--es', 'keys', keys, '--ei', 'hold', str(args.hold), '--ei', 'gap', str(args.gap)])
    return end, err


def save(body):
    data = {'meta': {'host': args.host, 'platform': platform, 'layout': args.layout, 'third': third,
                     'hold': args.hold, 'gap': args.gap, 'saved': time.strftime('%Y-%m-%d %H:%M:%S'),
                     'userAgent': body.get('userAgent', ''), 'complete': bool(body.get('complete')),
                     'note': body.get('note', '')},
            'steps': steps, 'records': body.get('records', [])}
    with save_lock, open(os.path.join(args.out, 'scan.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body=b'', ctype='application/json'):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def route(self):
        prefix = '/' + token + '/'
        return self.path[len(prefix):] if self.path.startswith(prefix) else None

    def do_GET(self):
        r = self.route()
        if r == '':
            with open(os.path.join(HERE, 'scan.html'), 'rb') as f:
                self.reply(200, f.read(), 'text/html; charset=utf-8')
        elif r == 'plan':
            self.reply(200, json.dumps(plan, ensure_ascii=False).encode())
        else:
            self.reply(404)

    def do_POST(self):
        r = self.route()
        if r is None:
            self.reply(404)
            return
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if r == 'send':
            start = int(body.get('from', 0))
            end, err = send_chunk(start)
            if err:
                print('  ' + err, flush=True)
            else:
                print('  发第 %d–%d 步（共 %d 步）' % (start + 1, end, len(steps)), flush=True)
            self.reply(200, json.dumps({'to': end, 'error': err}, ensure_ascii=False).encode())
        elif r == 'stop':
            err = adb_broadcast(['--ez', 'stop', 'true'])
            self.reply(200, json.dumps({'error': err}, ensure_ascii=False).encode())
        elif r == 'save':
            save(body)
            if body.get('complete'):
                print('结果已保存：%s' % os.path.join(args.out, 'scan.json'), flush=True)
                self.reply(200, b'{}')
                threading.Thread(target=self.server.shutdown).start()
            else:
                self.reply(200, b'{}')
        elif r == 'log':
            print('  页面：' + str(body.get('msg', ''))[:300], flush=True)
            self.reply(200, b'{}')
        else:
            self.reply(404)


def lan_ip():
    """这台电脑在局域网里的地址（UDP connect 只查路由，不发包）。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('192.0.2.1', 9))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


server = ThreadingHTTPServer(('0.0.0.0' if args.lan else '127.0.0.1', 0), Handler)
port = server.server_address[1]
host = (lan_ip() or '<这台电脑的局域网 IP>') if args.lan else '127.0.0.1'
url = 'http://%s:%d/%s/' % (host, port, token)
with open(os.path.join(args.out, 'url.txt'), 'w') as f:
    f.write(url + '\n')
print('扫描页面：%s（%d 步%s）' % (url, len(steps), '' if third else '，这个布局在这家主机上没有第三层，不扫'), flush=True)
server.serve_forever()
