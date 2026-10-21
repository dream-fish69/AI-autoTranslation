#!/usr/bin/env python3
"""Regenerates the mod icon (mods.toml iconFile / Modrinth / GitHub).

Design: rounded dark-navy tile, blueprint grid, inner rounded frame,
blue "A" -> white arrow -> amber CJK glyph, three small squares underneath.

Rendered at 4x and downsampled with LANCZOS, so it stays crisp at 512.

Usage:
    python make_icon.py [--size 512] [--out <path>]
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---- palette -------------------------------------------------------------
TOP = (29, 53, 80)
BOTTOM = (8, 17, 28)
GRID = (96, 148, 208, 22)
FRAME = (92, 152, 214, 235)
BLUE = (96, 172, 240)
AMBER = (246, 198, 66)
WHITE = (238, 244, 255)

FONT_A = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_CJK = r"C:\Windows\Fonts\msyhbd.ttc"

CJK = "\u6587"  # U+6587

SS = 4  # supersampling factor


def load_font(path: str, size: int, index: int = 0) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size, index=index)


def glyph_mask(size, xy, text, font):
    """White-on-transparent mask of `text` centred on xy."""
    layer = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(layer)
    bbox = d.textbbox((0, 0), text, font=font)
    x, y = xy
    d.text(
        (x - (bbox[0] + bbox[2]) / 2, y - (bbox[1] + bbox[3]) / 2),
        text,
        font=font,
        fill=255,
    )
    return layer


def paint_glyph(canvas, mask, top_colour, bottom_colour, shadow=True):
    """Fill a glyph mask with a vertical gradient plus a drop shadow."""
    bbox = mask.getbbox()
    if not bbox:
        return canvas
    x0, y0, x1, y1 = bbox
    grad = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    h = max(1, y1 - y0)
    for y in range(y0, y1):
        t = (y - y0) / h
        gd.line(
            [(x0, y), (x1, y)],
            fill=(
                int(top_colour[0] + (bottom_colour[0] - top_colour[0]) * t),
                int(top_colour[1] + (bottom_colour[1] - top_colour[1]) * t),
                int(top_colour[2] + (bottom_colour[2] - top_colour[2]) * t),
                255,
            ),
        )
    if shadow:
        offset = max(1, int((y1 - y0) * 0.022))
        sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        sh.paste((0, 0, 0, 115), (0, offset), mask)
        canvas = Image.alpha_composite(
            canvas, sh.filter(ImageFilter.GaussianBlur(offset))
        )
    canvas = Image.alpha_composite(
        canvas,
        Image.composite(grad, Image.new("RGBA", canvas.size, (0, 0, 0, 0)), mask),
    )
    return canvas


def build(size: int = 512) -> Image.Image:
    k = size / 512.0
    W = size * SS
    s = k * SS  # one "design pixel" in render pixels

    def p(v: float) -> int:
        return int(round(v * s))

    # ---- background gradient --------------------------------------------
    grad = Image.new("RGBA", (W, W))
    gd = ImageDraw.Draw(grad)
    for y in range(W):
        t = y / (W - 1)
        e = t ** 0.85  # keep the top brighter a little longer
        gd.line(
            [(0, y), (W, y)],
            fill=(
                int(TOP[0] + (BOTTOM[0] - TOP[0]) * e),
                int(TOP[1] + (BOTTOM[1] - TOP[1]) * e),
                int(TOP[2] + (BOTTOM[2] - TOP[2]) * e),
                255,
            ),
        )

    # soft top-left highlight
    hl = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse(
        [p(-120), p(-180), p(360), p(300)], fill=(120, 180, 240, 26)
    )
    grad = Image.alpha_composite(grad, hl.filter(ImageFilter.GaussianBlur(p(40))))

    # rounded-square mask
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, W - 1, W - 1], radius=p(112), fill=255
    )

    canvas = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    canvas.paste(grad, (0, 0), mask)

    # ---- blueprint grid ---------------------------------------------------
    grid = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(grid)
    step = p(48)
    for i in range(0, W + step, step):
        gdraw.line([(i, 0), (i, W)], fill=GRID, width=max(1, p(0.8)))
        gdraw.line([(0, i), (W, i)], fill=GRID, width=max(1, p(0.8)))
    canvas = Image.alpha_composite(
        canvas,
        Image.composite(grid, Image.new("RGBA", (W, W), (0, 0, 0, 0)), mask),
    )

    # ---- inner frame (with glow) -----------------------------------------
    frame_layer = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(frame_layer).rounded_rectangle(
        [p(46), p(46), W - p(46), W - p(46)],
        radius=p(74),
        outline=FRAME,
        width=p(9),
    )
    canvas = Image.alpha_composite(
        canvas, frame_layer.filter(ImageFilter.GaussianBlur(p(9)))
    )
    canvas = Image.alpha_composite(canvas, frame_layer)

    # ---- arrow ------------------------------------------------------------
    draw = ImageDraw.Draw(canvas)
    ay = p(220)
    draw.rounded_rectangle(
        [p(214), ay - p(8), p(268), ay + p(8)], radius=p(8), fill=WHITE
    )
    draw.polygon(
        [(p(260), ay - p(25)), (p(302), ay), (p(260), ay + p(25))], fill=WHITE
    )

    # ---- glyphs -----------------------------------------------------------
    canvas = paint_glyph(
        canvas,
        glyph_mask(W, (p(144), p(220)), "A", load_font(FONT_A, p(190))),
        (132, 198, 250),
        (58, 128, 214),
    )
    canvas = paint_glyph(
        canvas,
        glyph_mask(W, (p(370), p(222)), CJK, load_font(FONT_CJK, p(146), 0)),
        (255, 216, 108),
        (222, 160, 26),
    )

    # ---- three little squares --------------------------------------------
    draw = ImageDraw.Draw(canvas)
    dot_y = p(384)
    for dx, colour in [(232, BLUE), (258, BLUE), (284, AMBER)]:
        draw.rounded_rectangle(
            [p(dx - 7), dot_y - p(7), p(dx + 7), dot_y + p(7)],
            radius=p(3.5),
            fill=colour,
        )

    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    default_out = (
        Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "icon.png"
    )
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--out", default=str(default_out))
    args = ap.parse_args()

    img = build(args.size)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, format="PNG", optimize=True)
    print(f"wrote {out} ({out.stat().st_size} bytes, {img.width}x{img.height})")


if __name__ == "__main__":
    main()
