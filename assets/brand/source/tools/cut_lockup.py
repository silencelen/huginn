"""Cut the Huginn wordmark and lockup as outlines: lowercase `huginn` in IBM Plex Mono
SemiBold, shaped with HarfBuzz, tracking -6 % of the em, the decided raven standing
where the cursor would be. Writes four SVGs into assets/brand/."""
import sys, os
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
FONT='/tmp/claude-0/-home-user-huginn/dc9f86f2-f60d-5445-8434-59f9b7f51183/scratchpad/fonts/IBMPlexMono-600.ttf'
OUT='/home/user/huginn/assets/brand/'
INK, BONE = '#141615', '#E2E6E1'
RAVEN='M76.5,28 L67.5,24.6 L54,17.8 L45,17.8 L36,21.2 L32.18,26.972 L41.616,51.925 L32.334,65.952 L30.666,64.848 L39.384,51.675 L31.106,29.786 L22.5,68.8 L40.5,68.8 L54,62 L58.5,62 L63,48.4 L63,34.8 Z M48.55,26.3 a3.2,3.2 0 1,0 6.4,0 a3.2,3.2 0 1,0 -6.4,0 Z'
# raven box in path space: x 22.5-76.5, y 17.8-68.8 (54 x 51)
tt=TTFont(FONT); upm=tt['head'].unitsPerEm; glyphset=tt.getGlyphSet()
blob=hb.Blob(open(FONT,'rb').read()); face=hb.Face(blob); font=hb.Font(face); font.scale=(upm,upm)
buf=hb.Buffer(); buf.add_str('huginn'); buf.guess_segment_properties(); hb.shape(font,buf,{'kern':True,'liga':False})
TRACK=-0.06*upm
x=0; paths=[]; order=tt.getGlyphOrder()
for info,pos in zip(buf.glyph_infos, buf.glyph_positions):
    name=order[info.codepoint]
    pen=SVGPathPen(glyphset); tp=TransformPen(pen,(1,0,0,-1,x+pos.x_offset,pos.y_offset))  # flip y for SVG
    glyphset[name].draw(tp); paths.append(pen.getCommands())
    x+=pos.x_advance+TRACK
word_w=x-TRACK
# metrics
h_bounds=None
from fontTools.pens.boundsPen import BoundsPen
bp=BoundsPen(glyphset); glyphset['h'].draw(bp); h_top=bp.bounds[3]
bp=BoundsPen(glyphset); glyphset['g'].draw(bp); g_bot=bp.bounds[1]
# the raven: bottom on the baseline, top at the h's height; scale 54x51 box to height h_top
s=h_top/51.0; gap=0.25*upm
rx=word_w+gap; ry=0  # baseline at y=0; svg y goes down: raven top at -h_top
raven_t=f'translate({rx:.1f},{-h_top:.1f}) scale({s:.4f}) translate(-22.5,-17.8)'
total_w=rx+54*s
pad=0.08*upm
vb=(-pad, -h_top-pad, total_w+2*pad, h_top+(-g_bot)+2*pad)
def svg(color, with_raven, name):
    body=''.join(f'<path fill="{color}" d="{p}"/>' for p in paths)
    rv=f'<g transform="{raven_t}"><path fill="{color}" fill-rule="evenodd" d="{RAVEN}"/></g>' if with_raven else ''
    w=total_w if with_raven else word_w
    vbw = (vb[2] if with_raven else word_w+2*pad)
    H=vb[3]
    out=(f'<svg xmlns="http://www.w3.org/2000/svg" width="{vbw/upm*100:.1f}" height="{H/upm*100:.1f}" viewBox="{vb[0]:.1f} {vb[1]:.1f} {vbw:.1f} {H:.1f}">\n'
         f'  <!-- Huginn {name}: lowercase huginn in IBM Plex Mono SemiBold as outlines (HarfBuzz shaped, kerning on, tracking -6 % em){", the brand raven (assets/brand/raven.svg path) standing where the cursor would be, bottom on the baseline, top at the h" if with_raven else ""}. Font units: {upm}/em. Ink {INK} / bone {BONE}. Regenerate with assets/brand/source/.. cut_lockup.py; the artwork needs no font. -->\n'
         f'  {body}{rv}\n</svg>\n')
    return out
open(OUT+'wordmark.svg','w').write(svg(INK,False,'wordmark'))
open(OUT+'wordmark-dark.svg','w').write(svg(BONE,False,'wordmark, bone for dark grounds'))
open(OUT+'lockup.svg','w').write(svg(INK,True,'lockup'))
open(OUT+'lockup-dark.svg','w').write(svg(BONE,True,'lockup, bone for dark grounds'))
print('upm',upm,'h_top',h_top,'g_bot',g_bot,'word_w',word_w,'raven scale',round(s,3),'vb',[round(v,1) for v in vb])
