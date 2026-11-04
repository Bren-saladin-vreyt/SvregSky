import sys
from PIL import Image
out, *ins = sys.argv[1:]
ims = [Image.open(p) for p in ins]
w = max(i.width for i in ims); h = sum(i.height for i in ims)
s = Image.new('RGB', (w, h)); y = 0
for i in ims: s.paste(i, (0, y)); y += i.height
s.save(out)
