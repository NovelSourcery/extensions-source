#!/usr/bin/env python3
"""
Probe script to inspect lnori.com HTML structure and noveldex chapter/detail RSC.
Output is written to probe_output.txt for inspection.
"""
import sys
import re
import json
import urllib.request

OUTPUT = []

def p(msg):
    OUTPUT.append(str(msg))
    print(msg)

HEADERS_BROWSER = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
}

RSC_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    'Accept': '*/*',
    'rsc': '1',
    'next-router-prefetch': '1',
}

def fetch(url, headers=None, timeout=20):
    h = {**HEADERS_BROWSER, **(headers or {})}
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode('utf-8', errors='replace')
    except Exception as e:
        p(f"  ERROR fetching {url}: {e}")
        return None

# ─── LNORI HOMEPAGE ───────────────────────────────────────────────────────────
p("=" * 60)
p("LNORI HOMEPAGE: https://lnori.com")
p("=" * 60)
html = fetch("https://lnori.com")
if html:
    p(f"Size: {len(html)} bytes")

    arts = re.findall(r'<article[^>]*>', html)
    p(f"\nTotal <article> tags: {len(arts)}")
    for a in arts[:5]:
        p(f"  {repr(a[:200])}")

    data_ids = re.findall(r'data-id=["\']([^"\']+)["\']', html)
    p(f"\ndata-id count: {len(data_ids)}, first 3: {data_ids[:3]}")

    data_t = re.findall(r'data-t=["\']([^"\']+)["\']', html)
    p(f"data-t count: {len(data_t)}, first 3: {data_t[:3]}")

    series_links = re.findall(r'href=["\'](/series/[^"\']+)["\']', html)
    p(f"\nLinks with /series/: {len(series_links)}, first 5: {series_links[:5]}")

    h3s = re.findall(r'<h3[^>]*>(.*?)</h3>', html, re.DOTALL)
    p(f"\n<h3> elements: {len(h3s)}, first 5:")
    for h in h3s[:5]:
        cleaned = re.sub(r'<[^>]+>', '', h).strip()
        p(f"  {repr(cleaned[:80])}")

    scripts = re.findall(r'<script([^>]*)>(.*?)</script>', html, re.DOTALL)
    p(f"\nScript blocks: {len(scripts)}")
    for attrs, body_s in scripts:
        if any(kw in body_s.lower() for kw in ['novel', 'book', 'series', 'slug', 'title']):
            p(f"  Script attrs={attrs.strip()[:50]}: {body_s[:400]}")

    body_start = html.find('<body')
    if body_start == -1:
        body_start = 0
    p(f"\n--- HTML body (first 3000 chars) ---")
    p(html[body_start:body_start+3000])

    with open('lnori_homepage.html', 'w', encoding='utf-8') as f:
        f.write(html)
    p("Saved lnori_homepage.html")

# ─── LNORI SERIES PAGE ───────────────────────────────────────────────────────
p("\n" + "=" * 60)
p("LNORI SERIES: /series/3336/mushoku-tensei-jobless-reincarnation")
p("=" * 60)
series_html = fetch("https://lnori.com/series/3336/mushoku-tensei-jobless-reincarnation")
if series_html:
    p(f"Size: {len(series_html)} bytes")
    arts2 = re.findall(r'<article[^>]*>', series_html)
    p(f"<article> count: {len(arts2)}, first 3:")
    for a in arts2[:3]:
        p(f"  {repr(a[:200])}")

    vol_grid = re.search(r'class="[^"]*vol-grid[^"]*"', series_html)
    p(f"vol-grid class found: {vol_grid is not None}")

    a_cards = re.findall(r'<article[^>]*class="[^"]*card[^"]*"[^>]*>', series_html)
    p(f"article.card count: {len(a_cards)}")

    h3s2 = re.findall(r'<h3[^>]*>(.*?)</h3>', series_html, re.DOTALL)
    p(f"\n<h3> count: {len(h3s2)}, first 5:")
    for h in h3s2[:5]:
        p(f"  {re.sub(r'<[^>]+>', '', h).strip()[:80]}")

    body_s = series_html.find('<body')
    if body_s == -1:
        body_s = 0
    p(f"\n--- SERIES PAGE body (first 4000 chars) ---")
    p(series_html[body_s:body_s+4000])

    with open('lnori_series.html', 'w', encoding='utf-8') as f:
        f.write(series_html)
    p("Saved lnori_series.html")

# ─── NOVELDEX CHAPTER-1 RSC ──────────────────────────────────────────────────
p("\n" + "=" * 60)
p("NOVELDEX chapter-1 RSC: how-to-ruin-a-romantic-comedy")
p("=" * 60)
slug = "how-to-ruin-a-romantic-comedy"
ch1_url = f"https://noveldex.io/series/novel/{slug}/chapter/1?_rsc=1"
ch1_body = fetch(ch1_url, headers={**RSC_HEADERS, 'next-url': f'/series/novel/{slug}/chapter/1'})
if ch1_body:
    p(f"Size: {len(ch1_body)} bytes")

    ac_m = re.search(r'"allChapters"\s*:\s*(\[.*?\])(?=\s*,"totalChapters")', ch1_body, re.DOTALL)
    if ac_m:
        try:
            chs = json.loads(ac_m.group(1))
            p(f"allChapters: {len(chs)} entries")
            p(f"  first: {chs[0]}")
            p(f"  last:  {chs[-1]}")
        except Exception as e:
            p(f"JSON parse error: {e}")
    else:
        p("allChapters NOT FOUND in RSC")
        tc = re.search(r'"totalChapters"\s*:\s*(\d+)', ch1_body)
        p(f"totalChapters field: {tc.group(1) if tc else 'NOT FOUND'}")

    cc = re.search(r'"chapterCount"\s*:\s*(\d+)', ch1_body)
    p(f"chapterCount: {cc.group(1) if cc else 'NOT FOUND'}")

    t_idx = ch1_body.find(":T")
    p(f"T-tag at index: {t_idx}")
    if t_idx != -1:
        h1_idx = ch1_body.find('<h1 class="chapter-title">', t_idx)
        p(f'h1.chapter-title at: {h1_idx}')
        if h1_idx != -1:
            bom_after = ch1_body.find('\uFEFF', h1_idx + 10)
            p(f"BOM after h1 at: {bom_after}")
            p(f"Content preview: {repr(ch1_body[h1_idx:h1_idx+500])}")

    with open('noveldex_ch1.txt', 'w', encoding='utf-8') as f:
        f.write(ch1_body[:50000])
    p("Saved noveldex_ch1.txt (first 50k)")

with open('probe_output.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(OUTPUT))
p("Done — see probe_output.txt, lnori_homepage.html, lnori_series.html, noveldex_ch1.txt")

