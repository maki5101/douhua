#!/usr/bin/env python3
"""从 art/icon.webp 生成 App 图标的前景层 res/drawable-nodpi/ic_launcher_fg.webp。

换图标：替换 art/icon.webp（正方形），运行 python3 tools/make_icon.py，再 tools/build.sh。
需要 Pillow（pip install --user pillow），只有生成图标时要，平时构建不用。

自适应图标的图层是 108dp 见方，桌面按圆形 / 圆角方形裁剪时只露出中间 72dp。
整张图正好放进中间这 72dp（鸟从头顶到尾巴都在里面，被裁掉的只有四角的云）；
外圈 18dp 只在桌面做视差、缩放动效时露出来：把图的边缘镜像出去再模糊，衔接处颜色连续，看起来是云的延续。
"""
import sys
from pathlib import Path

try:
    from PIL import Image, ImageFilter
except ImportError:
    sys.exit("缺少 Pillow：pip install --user pillow")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "icon.webp"
OUT = ROOT / "res" / "drawable-nodpi" / "ic_launcher_fg.webp"

LAYER = 432          # 108dp × 4（xxxhdpi），系统按屏幕密度缩小
VISIBLE = 288        # 中间露出来的 72dp × 4


def main():
    img = Image.open(SRC).convert("RGB")
    if abs(img.width - img.height) > 2:
        sys.exit(f"{SRC} 不是正方形（{img.width}×{img.height}），先裁成正方形")
    inner = img.resize((VISIBLE, VISIBLE), Image.LANCZOS)
    m = (LAYER - VISIBLE) // 2
    # 先左右镜像，再把整行上下镜像（四个角也就补上了）
    layer = Image.new("RGB", (LAYER, LAYER))
    layer.paste(inner, (m, m))
    layer.paste(inner.crop((0, 0, m, VISIBLE)).transpose(Image.FLIP_LEFT_RIGHT), (0, m))
    layer.paste(inner.crop((VISIBLE - m, 0, VISIBLE, VISIBLE)).transpose(Image.FLIP_LEFT_RIGHT), (m + VISIBLE, m))
    layer.paste(layer.crop((0, m, LAYER, 2 * m)).transpose(Image.FLIP_TOP_BOTTOM), (0, 0))
    layer.paste(layer.crop((0, VISIBLE, LAYER, m + VISIBLE)).transpose(Image.FLIP_TOP_BOTTOM), (0, m + VISIBLE))
    layer = layer.filter(ImageFilter.GaussianBlur(10))
    layer.paste(inner, (m, m))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    layer.save(OUT, "WEBP", quality=92, method=6)   # 不带原图的元数据
    print(f"已生成 {OUT.relative_to(ROOT)}（{OUT.stat().st_size // 1024} KB）", file=sys.stderr)


if __name__ == "__main__":
    main()
