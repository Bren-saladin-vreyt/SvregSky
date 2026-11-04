# composite translucent clouds over a painted background, from two renders: C1 (real sky gradient behind) and
# C2 (magenta behind). (1 - a) = (C1 - C2) / (sky - magenta), taken on the green channel (magenta has none).
import sys, numpy as np
from PIL import Image
bg = np.array(Image.open(sys.argv[1]).convert('RGB')).astype(float) / 255
c1 = np.array(Image.open(sys.argv[2]).convert('RGB')).astype(float) / 255
c2 = np.array(Image.open(sys.argv[3]).convert('RGB')).astype(float) / 255
tod = sys.argv[5] if len(sys.argv) > 5 else 'day'
top, hor = {'day': ((0.16, 0.52, 0.98), (0.55, 0.82, 1.0)), 'gold': ((0.92, 0.45, 0.10), (1.0, 0.70, 0.25))}[tod]
h = c1.shape[0]
uvy = (np.arange(h)[::-1] + 0.5) / h                # canvas row 0 is the top
k = uvy ** 0.6
sky = (np.array(hor)[None, :] * (1 - k[:, None]) + np.array(top)[None, :] * k[:, None])[:, None, :]
sky = np.broadcast_to(sky, c1.shape)
ia = np.clip((c1[..., 1] - c2[..., 1]) / np.maximum(sky[..., 1], 1e-3), 0, 1)   # 1 - a
cloudPre = c1 - ia[..., None] * sky                    # cloud * a
out = cloudPre + ia[..., None] * bg
Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save(sys.argv[4])
