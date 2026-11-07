# -*- coding: utf-8 -*-
"""Generates the FloodPass icon set.

App-icon style: diagonal green gradient rounded square with a subtle brick
wall texture, flat white keyhole in the center, solid white AUTO / FLOODGATE
text above and below.

Outputs (next to this script):
    icon.svg          vector source
    icon-512.png      RGBA, rounded corners transparent
    icon-256.png
    icon-128.png
    icon-64.png

Usage: python make_icon.py
"""

import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))

# --- design constants (logical units on a 1024 x 1024 canvas) ----------------
SIZE = 1024
SS = 4  # supersampling factor for smooth edges

# Diagonal gradient, top-left to bottom-right, both greens close to the
# original flat green so the icon keeps its bright identity.
GRADIENT_START = (47, 210, 110, 255)    # lighter green, top-left
GRADIENT_END = (18, 148, 71, 255)       # deeper green, bottom-right

# Brick wall texture: staggered mortar joints drawn in translucent black,
# which turns into darker green grooves over the gradient. The pattern only
# covers the center area and fades out well before the icon edges.
BRICK_WIDTH = 360
BRICK_HEIGHT = 160
MORTAR = 12                             # joint thickness
MORTAR_ALPHA = 60                       # about 24% opacity
TEXTURE_RADIUS = 300                    # solid texture zone around the center
TEXTURE_BLUR = 85                       # fade-out softness

CORNER_RADIUS = 205
WHITE = (255, 255, 255, 255)
TEXT_ALPHA = 255                        # solid white text

FONT_BOLD = r"C:\Windows\Fonts\arialbd.ttf"

TOP_TEXT = "AUTO"
TOP_TEXT_SIZE = 118
TOP_TEXT_TRACKING = 46
TOP_TEXT_CENTER_Y = 176

BOTTOM_TEXT = "FLOODGATE"
BOTTOM_TEXT_SIZE = 74
BOTTOM_TEXT_TRACKING = 16
BOTTOM_TEXT_CENTER_Y = 880

KEYHOLE_CIRCLE = (512, 468, 152)    # (cx, cy, r)
KEYHOLE_POLY = (446, 560, 578, 560, 634, 800, 390, 800)  # x1,y1,... widening stem


def diagonal_gradient(size, start_rgba, end_rgba):
    """Builds a 45-degree gradient from top-left to bottom-right."""
    # Oversize the source so the rotated gradient fully covers the crop
    # without touching the rotation fill at the corners.
    span = int(size * 1.5)
    ramp = Image.linear_gradient("L").resize((span, span), Image.BILINEAR)
    ramp = ramp.rotate(45, resample=Image.BICUBIC)
    left = (span - size) // 2
    ramp = ramp.crop((left, left, left + size, left + size))

    top = Image.new("RGBA", (size, size), start_rgba)
    bottom = Image.new("RGBA", (size, size), end_rgba)
    top.putalpha(ramp)
    return Image.alpha_composite(bottom, top)


def brick_overlay(size):
    """Draws staggered mortar joints (translucent black) for the brick wall.

    The joints are laid out across the whole canvas but their alpha is
    multiplied with a blurred elliptical mask, so the texture stays solid
    behind the keyhole and fades away long before the icon edges.
    """
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    mortar = (0, 0, 0, MORTAR_ALPHA)

    brick_w = BRICK_WIDTH * SS
    brick_h = BRICK_HEIGHT * SS
    half = (MORTAR // 2) * SS

    y = 0
    row = 0
    while y < size:
        # Horizontal bed joint across the row boundary
        draw.rectangle([0, y - half, size, y + half - 1], fill=mortar)
        # Vertical head joints, staggered every other row
        x = (brick_w // 2) if row % 2 else 0
        while x < size + brick_w:
            draw.rectangle([x - half, y, x + half - 1, y + brick_h], fill=mortar)
            x += brick_w
        y += brick_h
        row += 1

    # Center-weighted fade mask: solid inside TEXTURE_RADIUS, smooth falloff
    fade = Image.new("L", (size, size), 0)
    fade_draw = ImageDraw.Draw(fade)
    r = TEXTURE_RADIUS * SS
    fade_draw.ellipse([size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r], fill=255)
    fade = fade.filter(ImageFilter.GaussianBlur(TEXTURE_BLUR * SS))

    red, green, blue, alpha = overlay.split()
    alpha = ImageChops.multiply(alpha, fade)
    return Image.merge("RGBA", (red, green, blue, alpha))


def draw_tracked_text(draw, center_xy, text, font, tracking, fill):
    """Draws letter-spaced text centered horizontally around center_xy."""
    cx, cy = center_xy
    widths = [draw.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)

    left = cx - total / 2.0
    ascent, descent = font.getmetrics()
    y = cy - (ascent + descent) / 2.0
    x = left
    for ch, w in zip(text, widths):
        draw.text((x, y), ch, font=font, fill=fill)
        x += w + tracking


def build_bitmap():
    big = SIZE * SS

    # Background: diagonal gradient + brick texture, clipped to rounded square
    img = diagonal_gradient(big, GRADIENT_START, GRADIENT_END)
    img = Image.alpha_composite(img, brick_overlay(big))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=CORNER_RADIUS * SS, fill=255)
    img.putalpha(mask)

    # Top and bottom text on its own layer
    overlay = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    text_color = (255, 255, 255, TEXT_ALPHA)

    top_font = ImageFont.truetype(FONT_BOLD, TOP_TEXT_SIZE * SS)
    bottom_font = ImageFont.truetype(FONT_BOLD, BOTTOM_TEXT_SIZE * SS)
    draw_tracked_text(overlay_draw, (big / 2, TOP_TEXT_CENTER_Y * SS),
                      TOP_TEXT, top_font, TOP_TEXT_TRACKING * SS, text_color)
    draw_tracked_text(overlay_draw, (big / 2, BOTTOM_TEXT_CENTER_Y * SS),
                      BOTTOM_TEXT, bottom_font, BOTTOM_TEXT_TRACKING * SS, text_color)
    img = Image.alpha_composite(img, overlay)

    # Flat white keyhole: circle head + widening stem
    draw = ImageDraw.Draw(img)
    cx, cy, r = KEYHOLE_CIRCLE
    draw.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=WHITE)
    draw.polygon([(KEYHOLE_POLY[i] * SS, KEYHOLE_POLY[i + 1] * SS)
                  for i in range(0, len(KEYHOLE_POLY), 2)], fill=WHITE)

    # Downscale for antialiasing and export
    base = img.resize((SIZE, SIZE), Image.LANCZOS)
    for size in (512, 256, 128, 64):
        out = base.resize((size, size), Image.LANCZOS)
        out.save(os.path.join(HERE, "icon-%d.png" % size))


def build_svg():
    svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#2FD26E"/>
      <stop offset="1" stop-color="#129447"/>
    </linearGradient>
    <pattern id="bricks" width="360" height="320" patternUnits="userSpaceOnUse">
      <g stroke="#000000" stroke-opacity="0.235" stroke-width="12" fill="none">
        <path d="M -6 0 H 366 M -6 160 H 366 M -6 320 H 366"/>
        <path d="M 180 -6 V 160 M -6 160 V 320 M 366 160 V 320"/>
      </g>
    </pattern>
    <filter id="soft" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="85"/>
    </filter>
    <mask id="texFade">
      <ellipse cx="512" cy="512" rx="300" ry="300" fill="#ffffff" filter="url(#soft)"/>
    </mask>
  </defs>
  <rect x="0" y="0" width="1024" height="1024" rx="{radius}" fill="url(#bg)"/>
  <rect x="0" y="0" width="1024" height="1024" rx="{radius}" fill="url(#bricks)" mask="url(#texFade)"/>
  <text x="512" y="{top_y}" font-family="Arial, Helvetica, sans-serif" font-weight="bold"
        font-size="{top_size}" letter-spacing="{top_track}" fill="#FFFFFF"
        text-anchor="middle">{top_text}</text>
  <text x="512" y="{bottom_y}" font-family="Arial, Helvetica, sans-serif" font-weight="bold"
        font-size="{bottom_size}" letter-spacing="{bottom_track}" fill="#FFFFFF"
        text-anchor="middle">{bottom_text}</text>
  <circle cx="{cx}" cy="{cy}" r="{r}" fill="#FFFFFF"/>
  <polygon points="{poly}" fill="#FFFFFF"/>
</svg>
""".format(
        radius=CORNER_RADIUS,
        top_y=TOP_TEXT_CENTER_Y + TOP_TEXT_SIZE * 0.35,
        top_size=TOP_TEXT_SIZE,
        top_track=TOP_TEXT_TRACKING,
        top_text=TOP_TEXT,
        bottom_y=BOTTOM_TEXT_CENTER_Y + BOTTOM_TEXT_SIZE * 0.35,
        bottom_size=BOTTOM_TEXT_SIZE,
        bottom_track=BOTTOM_TEXT_TRACKING,
        bottom_text=BOTTOM_TEXT,
        cx=KEYHOLE_CIRCLE[0], cy=KEYHOLE_CIRCLE[1], r=KEYHOLE_CIRCLE[2],
        poly=" ".join(str(v) for v in KEYHOLE_POLY),
    )
    with open(os.path.join(HERE, "icon.svg"), "w", encoding="utf-8") as handle:
        handle.write(svg)


if __name__ == "__main__":
    build_bitmap()
    build_svg()
    print("Icon set written to", HERE)
