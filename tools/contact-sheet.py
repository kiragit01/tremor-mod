"""Contact sheet of autotest screenshots: tools/contact-sheet.py <report dir> <out.png> <name> [name ...] (2 per row,
480x270 each; the names without .png, from <report dir>/screenshots). Needs Pillow."""
import sys
from PIL import Image
report, out, names = sys.argv[1], sys.argv[2], sys.argv[3:]
ims = [Image.open(f'{report}/screenshots/{n}.png').convert('RGB').resize((480, 270)) for n in names]
rows = (len(ims) + 1) // 2
sheet = Image.new('RGB', (960, 270 * rows))
for i, im in enumerate(ims):
    sheet.paste(im, ((i % 2) * 480, (i // 2) * 270))
sheet.save(out)
