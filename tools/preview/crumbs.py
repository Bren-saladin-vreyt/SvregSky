# count loose crumbs: cloud blobs of <= 3 low-res pixels in the cloud mask (these blink in and out while moving)
import sys
import numpy as np
from PIL import Image
from scipy import ndimage
S = 4
for f in sys.argv[1:]:
    a = np.array(Image.open(f).convert('RGB')).astype(int)[S // 2::S, S // 2::S]
    sky = (a[..., 0] < 120)
    lab, n = ndimage.label(~sky)
    sizes = ndimage.sum(np.ones_like(lab), lab, range(1, n + 1))
    print(f.split('/')[-1], 'blobs', n, 'crumbs<=3px', int((sizes <= 3).sum()))
