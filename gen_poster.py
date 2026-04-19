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
