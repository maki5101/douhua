# 描红 / 白板测试的本机小服务（tools/air-log.sh --trace / --board 拉起，只听 127.0.0.1）。白板（board.html）只用 /、/progress、/result。
# 描红：
#   GET  /        描红页面 trace.html
#   GET  /plan    三轮的顺序（只给页面 A/B/C 标签，每轮是哪组参数记在 plan.json 里，页面不知道——盲测）
#   POST /round   换到第几轮：经 adb 调试广播临时换消抖参数（AirMouse.debugFilter），记进 marks.txt
#   POST /progress 描完一个图形：在终端里打一行（第几轮、哪个图形、偏多少）
#   POST /result  页面描完、选完，存 trace.json，然后服务退出
# 用法：python3 trace_server.py <输出目录> [trace|board]（adb 对准哪台手机看环境变量 ANDROID_SERIAL）
# 端口由系统挑一个空闲的（固定端口可能被别的程序占着），地址写进 <输出目录>/url.txt 给 air-log.sh 读。
import json, os, random, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

out = sys.argv[1]
page = sys.argv[2] if len(sys.argv) > 2 else "trace"
here = os.path.dirname(os.path.abspath(__file__))

# 三组：(消抖参数：截止, β, 判断在动的截止, 死区, 轨迹平滑 ms, 防抖半径；激光笔式发送间隔 ms，−1 = App 默认；
#        方向换算 AirPointer.SPACE_*：0 斜基、1 玩家空间、2 世界空间)
# 2026-10-16：消抖都用"侧倾补偿那一版"的参数，三轮只换方向换算；手机日志记下原始陀螺仪 + 重力，事后按描红的目标方向拟合用户自己的手法
FILTER = "1.0,1.5,2.0,3,0,0"
CONFIGS = {
    "斜基（左右绕竖直、上下绕手机右轴）": (FILTER, 8, 0),
    "玩家空间（GyroWiki / JoyShockMapper）": (FILTER, 8, 1),
    "世界空间（GamepadMotionHelpers）": (FILTER, 8, 2),
}
SHAPES = ["横线", "竖线", "圆", "方", "三角"]

names = list(CONFIGS)
random.shuffle(names)
rounds = [{"label": "ABC"[i], "name": n, "cfg": CONFIGS[n][0], "gap": CONFIGS[n][1], "space": CONFIGS[n][2]}
          for i, n in enumerate(names)]
if page == "trace":
    with open(os.path.join(out, "plan.json"), "w") as f:
        json.dump({"rounds": rounds, "shapes": SHAPES}, f, ensure_ascii=False, indent=1)


def mark(text):
    with open(os.path.join(out, "marks.txt"), "a") as f:
        f.write(f"{time.time_ns()} {text}\n")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body=b"", ctype="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            with open(os.path.join(here, page + ".html"), "rb") as f:
                self.reply(200, f.read(), "text/html; charset=utf-8")
        elif self.path == "/plan":
            plan = {"rounds": [{"label": r["label"]} for r in rounds], "shapes": SHAPES}
            self.reply(200, json.dumps(plan).encode(), "application/json")
        else:
            self.reply(404)

    def body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        if self.path == "/round":
            r = rounds[json.loads(self.body())["round"]]
            subprocess.run(["adb", "shell", "am", "broadcast", "-a", "dev.douhua.DEBUG_AIRLOG",
                            "--ez", "on", "true", "--ei", "gap", str(r["gap"]), "--es", "cfg", r["cfg"],
                            "--ei", "space", str(r["space"])],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            mark(f"round {r['label']}")
            print(f"第 {rounds.index(r) + 1} 轮：参数已换，开始描", flush=True)
            self.reply(200, b"ok")
        elif self.path == "/progress" and page == "board":
            p = json.loads(self.body())
            print(f"  {p['cat']} 第 {p['n']} 笔（共 {p['total']} 笔）", flush=True)
            self.reply(200, b"ok")
        elif self.path == "/progress":
            p = json.loads(self.body())
            n = len(SHAPES) * len(rounds)
            k = p["round"] * len(SHAPES) + SHAPES.index(p["shape"]) + 1
            print(f"  [{k}/{n}] 第 {p['round'] + 1} 轮 {p['shape']}：平均偏 {p['mean']:.1f} 像素，最远 {p['max']:.0f}"
                  + (f"（重画过 {p['redo']} 次）" if p["redo"] else ""), flush=True)
            self.reply(200, b"ok")
        elif self.path == "/result":
            with open(os.path.join(out, page + ".json"), "wb") as f:
                f.write(self.body())
            mark("end")
            print("结果已保存", flush=True)
            self.reply(200, b"ok")
            threading.Thread(target=self.server.shutdown).start()
        else:
            self.reply(404)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
url = f"http://127.0.0.1:{server.server_address[1]}/"
with open(os.path.join(out, "url.txt"), "w") as f:
    f.write(url + "\n")
print(f"{'描红' if page == 'trace' else '白板'}页面：{url}", flush=True)
server.serve_forever()
