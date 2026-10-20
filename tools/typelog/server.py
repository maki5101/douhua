# 打字测试的本机小服务（tools/type-log.sh 拉起，只听 127.0.0.1）：
#   GET  /           页面 typing.html
#   GET  /sentences  测试句子（sentences.txt，打乱顺序）
#   POST /progress   打完一句：在终端里打一行（第几句、用时、退格几次），这句先存进 typing.json（页面中途被关掉也不丢）
#   POST /result     页面点"完成并保存"（或全部打完）：存 typing.json，然后服务退出
# 用法：python3 server.py <输出目录>
# 端口由系统挑一个空闲的（固定端口可能被别的程序占着），地址写进 <输出目录>/url.txt 给 type-log.sh 读。
# 不复用 airlog/trace_server.py：那份绑着描红的轮次和图形。
import json, os, random, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

out = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))

with open(os.path.join(here, 'sentences.txt'), encoding='utf-8') as f:
    SENTENCES = [s.strip() for s in f if s.strip() and not s.lstrip().startswith('#')]
random.shuffle(SENTENCES)
done = []   # 打完的句子（/progress 一句句送来）
lock = threading.Lock()   # 服务是多线程的，两个请求别同时写文件


def save(data):
    with lock, open(os.path.join(out, "typing.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body=b"", ctype="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            with open(os.path.join(here, "typing.html"), "rb") as f:
                self.reply(200, f.read(), "text/html; charset=utf-8")
        elif self.path == "/sentences":
            self.reply(200, json.dumps(SENTENCES).encode(), "application/json")
        else:
            self.reply(404)

    def body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        if self.path == "/progress":
            p = json.loads(self.body())
            with lock:
                done.append(p["sentence"])
                partial = {"sentences": list(done), "partial": True}
            save(partial)
            print(f"  [{p['i']}/{p['n']}] {p['secs']:.1f} 秒，退格 {p['backspaces']} 次" + ("" if p["same"] else "（和目标不一样）"),
                  flush=True)
            self.reply(200, b"ok")
        elif self.path == "/result":
            save(json.loads(self.body()))
            print("结果已保存", flush=True)
            self.reply(200, b"ok")
            threading.Thread(target=self.server.shutdown).start()
        else:
            self.reply(404)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
url = f"http://127.0.0.1:{server.server_address[1]}/"
with open(os.path.join(out, "url.txt"), "w") as f:
    f.write(url + "\n")
print(f"打字测试页面：{url}（{len(SENTENCES)} 句）", flush=True)
server.serve_forever()
