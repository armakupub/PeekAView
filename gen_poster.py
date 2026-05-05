from PIL import Image, ImageDraw, ImageFont, ImageChops
import math
import os

SIZE = 512
BG = (30, 30, 35)
ORANGE = (220, 160, 40)
WHITE = (255, 255, 255)

# --- Triangle (Eye of Providence, apex up), sized to clear the text zone ---
TRI_APEX = (256, 70)
TRI_BASE_L = (95, 345)
TRI_BASE_R = (417, 345)
TRI_OUTLINE_W = 12

# --- Eye inside triangle (size preserved: proportionally larger vs triangle) ---
EYE_CX, EYE_CY = 256, 250
EYE_W, EYE_H = 161, 67
EYE_OUTLINE_W = 6
IRIS_R = 35
PUPIL_R = 13

def almond_points(cx, cy, w, h, n=160):
    pts = []
    for i in range(n + 1):
        t = i / n
        pts.append((cx - w / 2 + w * t, cy - (h / 2) * math.sin(math.pi * t)))
    for i in range(n + 1):
        t = i / n
        pts.append((cx + w / 2 - w * t, cy + (h / 2) * math.sin(math.pi * t)))
    return pts


img = Image.new("RGB", (SIZE, SIZE), BG)
draw = ImageDraw.Draw(img)

triangle = [TRI_APEX, TRI_BASE_R, TRI_BASE_L]

# --- Triangle outline ---
# Start/end the polyline on the base midpoint so the path seam lands on a
# straight edge (invisible); this lets joint="curve" round all three corners.
mid_base = (
    (TRI_BASE_L[0] + TRI_BASE_R[0]) / 2,
    (TRI_BASE_L[1] + TRI_BASE_R[1]) / 2,
)
draw.line(
    [mid_base, TRI_BASE_R, TRI_APEX, TRI_BASE_L, mid_base],
    fill=ORANGE,
    width=TRI_OUTLINE_W,
    joint="curve",
)

# --- Eye inside triangle ---
eye_outline = almond_points(EYE_CX, EYE_CY, EYE_W, EYE_H)

# Mask for iris clipping
eye_mask = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(eye_mask).polygon(eye_outline, fill=255)

# Iris on its own layer, clipped by eye mask
iris_layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
iris_draw = ImageDraw.Draw(iris_layer)
iris_draw.ellipse(
    [EYE_CX - IRIS_R, EYE_CY - IRIS_R, EYE_CX + IRIS_R, EYE_CY + IRIS_R],
    fill=ORANGE,
)
iris_draw.ellipse(
    [EYE_CX - PUPIL_R, EYE_CY - PUPIL_R, EYE_CX + PUPIL_R, EYE_CY + PUPIL_R],
    fill=BG,
)
iris_alpha = iris_layer.split()[-1]
iris_layer.putalpha(ImageChops.multiply(iris_alpha, eye_mask))
img.paste(iris_layer, (0, 0), iris_layer)

# Eye almond outline on top
draw.line(eye_outline + [eye_outline[0]], fill=ORANGE, width=EYE_OUTLINE_W, joint="curve")

# --- Text ---
try:
    font = ImageFont.truetype("arial.ttf", 44)
except Exception:
    font = ImageFont.load_default()

text1 = "Peek a"
text2 = "View"
bbox1 = draw.textbbox((0, 0), text1, font=font)
bbox2 = draw.textbbox((0, 0), text2, font=font)
tw1 = bbox1[2] - bbox1[0]
tw2 = bbox2[2] - bbox2[0]
th = bbox1[3] - bbox1[1]

text_y = 410
draw.text(((SIZE - tw1) / 2, text_y), text1, fill=WHITE, font=font)
draw.text(((SIZE - tw2) / 2, text_y + th + 12), text2, fill=WHITE, font=font)

out = os.path.join(os.path.dirname(__file__), "poster.png")
img.save(out)
print(f"Saved to {out}")


# --- Icon: same logo, no text, supersampled and downscaled to 32x32 ---
# PZ shows this next to the mod name in the in-game mod-activation list.
# Render at 256x256 with proportionally bolder strokes, then resample to
# 32x32 with LANCZOS so the line edges stay clean at icon scale.
ICON_RENDER = 256
ICON_OUT = 32

icon_img = Image.new("RGB", (ICON_RENDER, ICON_RENDER), BG)
icon_draw = ImageDraw.Draw(icon_img)

# Triangle fills the canvas — no text reservation at the bottom so the
# logo can use the full height. Strokes scaled so the post-downscale
# line widths are ~1.5 px (triangle) and ~0.7 px (eye), readable at
# 32x32 without going too dense.
ITRI_APEX = (ICON_RENDER * 0.50, ICON_RENDER * 0.10)
ITRI_BASE_L = (ICON_RENDER * 0.10, ICON_RENDER * 0.86)
ITRI_BASE_R = (ICON_RENDER * 0.90, ICON_RENDER * 0.86)
ITRI_OUTLINE_W = int(ICON_RENDER * 0.045)

IEYE_CX, IEYE_CY = ICON_RENDER * 0.50, ICON_RENDER * 0.56
IEYE_W, IEYE_H = ICON_RENDER * 0.46, ICON_RENDER * 0.20
IEYE_OUTLINE_W = int(ICON_RENDER * 0.024)
IIRIS_R = ICON_RENDER * 0.10
IPUPIL_R = ICON_RENDER * 0.038

icon_mid_base = (
    (ITRI_BASE_L[0] + ITRI_BASE_R[0]) / 2,
    (ITRI_BASE_L[1] + ITRI_BASE_R[1]) / 2,
)
icon_draw.line(
    [icon_mid_base, ITRI_BASE_R, ITRI_APEX, ITRI_BASE_L, icon_mid_base],
    fill=ORANGE,
    width=ITRI_OUTLINE_W,
    joint="curve",
)

icon_eye_outline = almond_points(IEYE_CX, IEYE_CY, IEYE_W, IEYE_H)

icon_eye_mask = Image.new("L", (ICON_RENDER, ICON_RENDER), 0)
ImageDraw.Draw(icon_eye_mask).polygon(icon_eye_outline, fill=255)

icon_iris_layer = Image.new("RGBA", (ICON_RENDER, ICON_RENDER), (0, 0, 0, 0))
icon_iris_draw = ImageDraw.Draw(icon_iris_layer)
icon_iris_draw.ellipse(
    [IEYE_CX - IIRIS_R, IEYE_CY - IIRIS_R, IEYE_CX + IIRIS_R, IEYE_CY + IIRIS_R],
    fill=ORANGE,
)
icon_iris_draw.ellipse(
    [IEYE_CX - IPUPIL_R, IEYE_CY - IPUPIL_R, IEYE_CX + IPUPIL_R, IEYE_CY + IPUPIL_R],
    fill=BG,
)
icon_iris_alpha = icon_iris_layer.split()[-1]
icon_iris_layer.putalpha(ImageChops.multiply(icon_iris_alpha, icon_eye_mask))
icon_img.paste(icon_iris_layer, (0, 0), icon_iris_layer)

icon_draw.line(
    icon_eye_outline + [icon_eye_outline[0]],
    fill=ORANGE,
    width=IEYE_OUTLINE_W,
    joint="curve",
)

icon_small = icon_img.resize((ICON_OUT, ICON_OUT), Image.LANCZOS)
icon_path = os.path.join(os.path.dirname(__file__), "icon.png")
icon_small.save(icon_path)
print(f"Saved to {icon_path}")
