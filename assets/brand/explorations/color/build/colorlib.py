"""Colour maths shared by the Huginn colour explorations.

WCAG 2.x relative luminance / contrast (same formula as tools/contrast.py) plus
CIELAB L* so state colours can be ordered by lightness, not only by hue.
"""
import colorsys


def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hexs(r, g, b):
    return '#%02X%02X%02X' % (max(0, min(255, round(r))), max(0, min(255, round(g))), max(0, min(255, round(b))))


def _lin(c):
    c /= 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(h):
    r, g, b = rgb(h)
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(a, b):
    l1, l2 = sorted((lum(a), lum(b)), reverse=True)
    return (l1 + 0.05) / (l2 + 0.05)


def grade(r):
    return 'AAA' if r >= 7 else 'AA' if r >= 4.5 else 'AA-large' if r >= 3 else 'FAIL'


def lstar(h):
    y = lum(h)
    e = (6 / 29) ** 3
    f = y ** (1 / 3) if y > e else y / (3 * (6 / 29) ** 2) + 4 / 29
    return 116 * f - 16


def hsl(h):
    r, g, b = (c / 255 for c in rgb(h))
    hh, l, s = colorsys.rgb_to_hls(r, g, b)
    return hh * 360, s * 100, l * 100


def mix(a, b, t):
    ra, rb = rgb(a), rgb(b)
    return hexs(*(ra[i] + (rb[i] - ra[i]) * t for i in range(3)))


def alpha_over(fg, bg, a):
    """The flat colour you see when fg at alpha a sits on bg (what a 0.7-alpha verb ink becomes)."""
    return mix(bg, fg, a)
