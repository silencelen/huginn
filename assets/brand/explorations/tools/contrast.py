#!/usr/bin/env python3
"""contrast.py '#fg' '#bg' [...]  — WCAG contrast ratio for each fg/bg pair."""
import sys
def lin(c):
    c/=255; return c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
def lum(h):
    h=h.lstrip('#'); r,g,b=(int(h[i:i+2],16) for i in (0,2,4)); return 0.2126*lin(r)+0.7152*lin(g)+0.0722*lin(b)
a=sys.argv[1:]
for fg,bg in zip(a[::2],a[1::2]):
    l1,l2=sorted((lum(fg),lum(bg)),reverse=True); r=(l1+0.05)/(l2+0.05)
    grade='AAA' if r>=7 else 'AA' if r>=4.5 else 'AA-large' if r>=3 else 'FAIL'
    print(f"{fg} on {bg}: {r:.2f}:1  {grade}")
