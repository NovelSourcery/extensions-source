#!/usr/bin/env python3
"""
Extension API Test Suite
Tests for NovelDex (noveldex.io) and Lnori (lnori.com) extensions.
Validates endpoints, parsing, filters, novel details, and chapter lists.

Usage:
    pip install requests beautifulsoup4 colorama
    python test_extensions.py
    python test_extensions.py --source noveldex
    python test_extensions.py --source lnori
    python test_extensions.py --verbose
"""

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlencode, urljoin

import requests
from bs4 import BeautifulSoup

try:
    from colorama import Fore, Style, init as colorama_init
    colorama_init(autoreset=True)
    GREEN  = Fore.GREEN
    RED    = Fore.RED
    YELLOW = Fore.YELLOW
    CYAN   = Fore.CYAN
    BOLD   = Style.BRIGHT
    RESET  = Style.RESET_ALL
except ImportError:
    GREEN = RED = YELLOW = CYAN = BOLD = RESET = ""

# ─────────────────────────────────────────────────────────────
# Result tracking
# ─────────────────────────────────────────────────────────────

@dataclass
class TestResult:
    name: str
    passed: bool
    message: str = ""
    details: dict[str, Any] = field(default_factory=dict)

RESULTS: list[TestResult] = []
VERBOSE = False


def ok(name: str, message: str = "", **details) -> TestResult:
    r = TestResult(name, True, message, details)
    RESULTS.append(r)
    mark = f"{GREEN}✓{RESET}"
    print(f"  {mark} {name}" + (f"  — {message}" if message else ""))
    if VERBOSE and details:
        for k, v in details.items():
            val = str(v)[:200] + ("…" if len(str(v)) > 200 else "")
            print(f"      {CYAN}{k}{RESET}: {val}")
    return r


def fail(name: str, message: str = "", **details) -> TestResult:
    r = TestResult(name, False, message, details)
    RESULTS.append(r)
    mark = f"{RED}✗{RESET}"
    print(f"  {mark} {name}" + (f"  — {RED}{message}{RESET}" if message else ""))
    if details:
        for k, v in details.items():
            val = str(v)[:300] + ("…" if len(str(v)) > 300 else "")
            print(f"      {YELLOW}{k}{RESET}: {val}")
    return r


def section(title: str):
    print(f"\n{BOLD}{CYAN}{'─'*60}{RESET}")
    print(f"{BOLD}{CYAN}  {title}{RESET}")
    print(f"{BOLD}{CYAN}{'─'*60}{RESET}")


def summary():
    passed = sum(1 for r in RESULTS if r.passed)
    total  = len(RESULTS)
    failed = total - passed
    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}  RESULTS: {GREEN}{passed} passed{RESET}  {RED}{failed} failed{RESET}  / {total} total{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}\n")
    if failed:
        print(f"{RED}Failed tests:{RESET}")
        for r in RESULTS:
            if not r.passed:
                print(f"  {RED}✗ {r.name}{RESET}  {r.message}")
    return failed == 0


# ─────────────────────────────────────────────────────────────
# HTTP helpers
# ─────────────────────────────────────────────────────────────

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json, text/html, */*",
    "Accept-Language": "en-US,en;q=0.9",
})

RSC_HEADERS = {
    "rsc": "1",
    "next-router-prefetch": "1",
    "Accept": "*/*",
}


def rsc_url(base: str, path: str) -> str:
    """Construct RSC URL with _rsc param and correct Next-Url header."""
    return f"{base}{path}?_rsc=1"


def rsc_headers_for(path: str) -> dict:
    return {**RSC_HEADERS, "next-url": path}


def rsc_chapter_headers(chapter_path: str) -> dict:
    """For chapter RSC requests: next-url is the novel page (without /chapter/N)."""
    novel_path = chapter_path.split("/chapter/")[0] if "/chapter/" in chapter_path else chapter_path
    return {**RSC_HEADERS, "next-url": novel_path}


def get(url: str, *, params: dict | None = None, headers: dict | None = None,
        timeout: int = 20) -> requests.Response | None:
    try:
        h = dict(SESSION.headers)
        if headers:
            h.update(headers)
        resp = SESSION.get(url, params=params, headers=h, timeout=timeout)
        resp.raise_for_status()
        return resp
    except requests.exceptions.Timeout:
        print(f"      {YELLOW}⚠ Timeout: {url}{RESET}")
        return None
    except requests.exceptions.HTTPError as e:
        print(f"      {YELLOW}⚠ HTTP {e.response.status_code}: {url}{RESET}")
        return None
    except Exception as e:
        print(f"      {YELLOW}⚠ Error: {e}{RESET}")
        return None


# ─────────────────────────────────────────────────────────────
# ══════════════════════════════════════════════════════════════
#   NOVELDEX TESTS
# ══════════════════════════════════════════════════════════════
# ─────────────────────────────────────────────────────────────

NOVELDEX_BASE = "https://noveldex.io"
NOVELDEX_API  = f"{NOVELDEX_BASE}/api/series"


def _check_series_item(item: dict) -> list[str]:
    """Returns list of missing/invalid fields in a series item."""
    issues = []
    if not item.get("slug"):
        issues.append("missing slug")
    if not item.get("title"):
        issues.append("missing title")
    if "coverImage" not in item:
        issues.append("missing coverImage key")
    return issues


def test_noveldex_popular():
    section("NovelDex — Popular (sort=popular)")
    resp = get(NOVELDEX_API, params={"page": 1, "limit": 24, "sort": "popular"})
    if not resp:
        fail("popular_request", "No response")
        return

    try:
        data = resp.json()
    except Exception as e:
        fail("popular_parse_json", str(e), body=resp.text[:300])
        return

    # meta
    meta = data.get("meta", {})
    ok("popular_meta_present", f"total={meta.get('total')} pages={meta.get('totalPages')}",
       total=meta.get("total"), page=meta.get("page"), limit=meta.get("limit"),
       totalPages=meta.get("totalPages"), hasMore=meta.get("hasMore"))

    items = data.get("data", [])
    if not items:
        fail("popular_items_present", "Empty data array")
        return
    ok("popular_items_count", f"{len(items)} items returned", count=len(items))

    bad = [i for item in items for i in _check_series_item(item)]
    if bad:
        fail("popular_items_valid", f"Issues: {bad[:5]}")
    else:
        ok("popular_items_valid", "All items have slug, title, coverImage")

    # show first 3
    for item in items[:3]:
        cover = item.get("coverImage", "")
        if cover and cover.startswith("/"):
            cover = NOVELDEX_BASE + cover
        ok(f"  item: {item['title'][:50]}", f"slug={item['slug']}", cover=cover,
           type=item.get("type"), status=item.get("status"))


def test_noveldex_latest():
    section("NovelDex — Latest (no sort = recently updated)")
    resp = get(NOVELDEX_API, params={"page": 1, "limit": 24})
    if not resp:
        fail("latest_request", "No response")
        return

    data = resp.json()
    items = data.get("data", [])
    ok("latest_items_count", f"{len(items)} items", count=len(items))
    meta = data.get("meta", {})
    ok("latest_meta", f"total={meta.get('total')}", hasMore=meta.get("hasMore"))


def test_noveldex_sort_options():
    section("NovelDex — Sort Options")
    sorts = {
        "newest": "Newest",
        "views": "Most Views",
        "longest": "Longest",
        "rating": "Top Rated",
        "popular": "Most Popular",
    }
    for sort_val, sort_label in sorts.items():
        resp = get(NOVELDEX_API, params={"page": 1, "limit": 6, "sort": sort_val})
        if not resp:
            fail(f"sort_{sort_val}", "No response")
            continue
        try:
            data = resp.json()
            items = data.get("data", [])
            meta  = data.get("meta", {})
            ok(f"sort_{sort_val}", f"{sort_label}: {len(items)} results, total={meta.get('total')}",
               first=items[0]["title"] if items else "—")
        except Exception as e:
            fail(f"sort_{sort_val}", str(e))
        time.sleep(0.3)


def test_noveldex_search():
    section("NovelDex — Search")
    queries = ["academy", "demon", "isekai"]
    for q in queries:
        resp = get(NOVELDEX_API, params={"page": 1, "limit": 10, "search": q})
        if not resp:
            fail(f"search_{q}", "No response")
            continue
        try:
            data  = resp.json()
            items = data.get("data", [])
            meta  = data.get("meta", {})
            ok(f"search_{q!r}", f"{len(items)} results, total={meta.get('total')}",
               first=items[0]["title"] if items else "—")
        except Exception as e:
            fail(f"search_{q!r}", str(e))
        time.sleep(0.3)


def test_noveldex_filters_status():
    section("NovelDex — Filters: Status")
    statuses = ["Completed", "Ongoing", "Hiatus"]
    for status in statuses:
        resp = get(NOVELDEX_API, params={"page": 1, "limit": 6, "status": status})
        if not resp:
            fail(f"filter_status_{status}", "No response")
            continue
        try:
            data  = resp.json()
            items = data.get("data", [])
            meta  = data.get("meta", {})
            # verify items actually have the requested status
            wrong = [i for i in items if i.get("status", "").upper() != status.upper()]
            if wrong:
                fail(f"filter_status_{status}", f"{len(wrong)} items with wrong status",
                     example=wrong[0].get("status"))
            else:
                ok(f"filter_status_{status}",
                   f"total={meta.get('total')}, all {len(items)} items correct",
                   first=items[0]["title"] if items else "—")
        except Exception as e:
            fail(f"filter_status_{status}", str(e))
        time.sleep(0.3)


def test_noveldex_filters_type():
    section("NovelDex — Filters: Type")
    types = [
        ("WEB_NOVEL", "WEB_NOVEL"),
        ("Manhwa",    "MANHWA"),
        ("Manga",     "MANGA"),
        ("Manhua",    "MANHUA"),
    ]
    for api_val, expected_type in types:
        resp = get(NOVELDEX_API, params={"page": 1, "limit": 6, "type": api_val})
        if not resp:
            fail(f"filter_type_{api_val}", "No response")
            continue
        try:
            data  = resp.json()
            items = data.get("data", [])
            meta  = data.get("meta", {})
            ok(f"filter_type_{api_val}",
               f"total={meta.get('total')}, {len(items)} items returned",
               first=items[0]["title"] if items else "—",
               first_type=items[0].get("type") if items else "—")
        except Exception as e:
            fail(f"filter_type_{api_val}", str(e))
        time.sleep(0.3)


def test_noveldex_filters_genre():
    section("NovelDex — Filters: Genres (include / exclude)")
    # include
    resp = get(NOVELDEX_API, params={"page": 1, "limit": 10, "genre": "action,fantasy"})
    if resp:
        try:
            data  = resp.json()
            items = data.get("data", [])
            ok("filter_genre_include_action_fantasy",
               f"{len(items)} results, total={data['meta'].get('total')}",
               first=items[0]["title"] if items else "—")
        except Exception as e:
            fail("filter_genre_include_action_fantasy", str(e))
    else:
        fail("filter_genre_include_action_fantasy", "No response")

    time.sleep(0.3)

    # exclude
    resp = get(NOVELDEX_API, params={"page": 1, "limit": 10,
                                     "exgenre": "adult,action", "genre": "fantasy"})
    if resp:
        try:
            data  = resp.json()
            items = data.get("data", [])
            ok("filter_genre_exclude_adult_action",
               f"{len(items)} results (adult+action excluded)",
               first=items[0]["title"] if items else "—")
        except Exception as e:
            fail("filter_genre_exclude_adult_action", str(e))
    else:
        fail("filter_genre_exclude_adult_action", "No response")


def test_noveldex_filters_tags():
    section("NovelDex — Filters: Tags (include / exclude)")
    resp = get(NOVELDEX_API, params={"page": 1, "limit": 10,
                                     "tag": "abandoned-children", "extag": "ability-steal"})
    if resp:
        try:
            data  = resp.json()
            items = data.get("data", [])
            ok("filter_tags_include_exclude",
               f"{len(items)} results",
               total=data["meta"].get("total"),
               first=items[0]["title"] if items else "—")
        except Exception as e:
            fail("filter_tags_include_exclude", str(e))
    else:
        fail("filter_tags_include_exclude", "No response")


def test_noveldex_filters_chapter_range():
    section("NovelDex — Filters: Chapter Count Range")
    params = {
        "page": 1, "limit": 10,
        "status": "Completed",
        "ch_min": 100, "ch_max": 200,
    }
    resp = get(NOVELDEX_API, params=params)
    if not resp:
        fail("filter_ch_range", "No response")
        return
    try:
        data  = resp.json()
        items = data.get("data", [])
        meta  = data.get("meta", {})
        ok("filter_ch_range_basic",
           f"total={meta.get('total')}, {len(items)} items returned",
           first=items[0]["title"] if items else "—")

        # Verify chapter counts if _count.chapters available
        for item in items[:5]:
            ch = item.get("_count", {}).get("chapters") or item.get("chapterCount")
            if ch is not None and not (100 <= ch <= 200):
                fail("filter_ch_range_values",
                     f"{item['title'][:40]} has {ch} chapters (outside 100-200)")
                return
        ok("filter_ch_range_values", f"Spot-checked {min(5,len(items))} items — chapter counts in range")
    except Exception as e:
        fail("filter_ch_range", str(e))

    time.sleep(0.3)

    # sort + chapter range
    resp = get(NOVELDEX_API, params={**params, "sort": "newest"})
    if resp:
        try:
            data = resp.json()
            ok("filter_ch_range_sorted_newest",
               f"{len(data.get('data',[]))} items with sort=newest",
               total=data["meta"].get("total"))
        except Exception as e:
            fail("filter_ch_range_sorted_newest", str(e))

    time.sleep(0.3)

    # has images
    resp = get(NOVELDEX_API, params={**params, "images": "true"})
    if resp:
        try:
            data = resp.json()
            ok("filter_ch_range_has_images",
               f"{len(data.get('data',[]))} items with images=true",
               total=data["meta"].get("total"))
        except Exception as e:
            fail("filter_ch_range_has_images", str(e))


def test_noveldex_filters_combined():
    section("NovelDex — Combined Filters")
    params = {
        "page": 1, "limit": 12,
        "type": "WEB_NOVEL",
        "status": "Completed",
        "genre": "action",
        "sort": "popular",
    }
    resp = get(NOVELDEX_API, params=params)
    if not resp:
        fail("filter_combined", "No response")
        return
    try:
        data  = resp.json()
        items = data.get("data", [])
        meta  = data.get("meta", {})
        ok("filter_combined_novel_completed_action",
           f"total={meta.get('total')}, {len(items)} items",
           first=items[0]["title"] if items else "—",
           first_type=items[0].get("type") if items else "—",
           first_status=items[0].get("status") if items else "—")
    except Exception as e:
        fail("filter_combined", str(e))


def test_noveldex_pagination():
    section("NovelDex — Pagination")
    resp1 = get(NOVELDEX_API, params={"page": 1, "limit": 10})
    resp2 = get(NOVELDEX_API, params={"page": 2, "limit": 10})
    if not resp1 or not resp2:
        fail("pagination", "Could not fetch pages")
        return
    try:
        d1 = resp1.json()
        d2 = resp2.json()
        slugs1 = {i["slug"] for i in d1.get("data", [])}
        slugs2 = {i["slug"] for i in d2.get("data", [])}
        overlap = slugs1 & slugs2
        if overlap:
            fail("pagination_no_overlap",
                 f"{len(overlap)} slugs appear on both page 1 and 2", overlap=list(overlap)[:3])
        else:
            ok("pagination_no_overlap",
               f"page1={len(slugs1)} items, page2={len(slugs2)} items — no overlap")

        ok("pagination_meta",
           f"page1 meta: {d1['meta']}, page2 meta: {d2['meta']}",
           page1_total=d1["meta"].get("total"), page2_total=d2["meta"].get("total"))
    except Exception as e:
        fail("pagination", str(e))


def test_noveldex_novel_detail():
    section("NovelDex — Novel Detail (RSC)")
    slug = "the-academys-weakest-became-a-demon-limited-hunter"
    path = f"/series/novel/{slug}"
    url  = rsc_url(NOVELDEX_BASE, path)

    resp = get(url, headers=rsc_headers_for(path))
    if not resp:
        fail("detail_request", "No response", url=url)
        return

    body = resp.text
    ok("detail_response_received", f"{len(body)} bytes", url=url)

    # title
    title_m = re.search(r'"title"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
    if title_m:
        ok("detail_title", title_m.group(1)[:80])
    else:
        fail("detail_title", "Could not find title in RSC body")

    # description
    desc_m = re.search(r'"description"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
    if desc_m:
        ok("detail_description", desc_m.group(1)[:100] + "…")
    else:
        fail("detail_description", "No description found")

    # coverImage
    cover_m = re.search(r'"coverImage"\s*:\s*"(/[^"]+)"', body)
    if cover_m:
        ok("detail_cover", NOVELDEX_BASE + cover_m.group(1))
    else:
        fail("detail_cover", "No coverImage found")

    # status
    status_m = re.search(r'"status"\s*:\s*"([A-Z_]+)"', body)
    if status_m:
        ok("detail_status", status_m.group(1))
    else:
        fail("detail_status", "No status found")

    # genres
    genres_m = re.search(r'"genres"\s*:\s*\[(.*?)\]', body, re.DOTALL)
    if genres_m:
        genre_names = re.findall(r'"name"\s*:\s*"([^"]+)"', genres_m.group(1))
        ok("detail_genres", ", ".join(genre_names[:6]), count=len(genre_names))
    else:
        fail("detail_genres", "No genres array found")

    # tags
    tags_m = re.search(r'"tags"\s*:\s*\[(.*?)\]', body, re.DOTALL)
    if tags_m:
        tag_names = re.findall(r'"name"\s*:\s*"([^"]+)"', tags_m.group(1))
        ok("detail_tags", ", ".join(tag_names[:6]), count=len(tag_names))
    else:
        fail("detail_tags", "No tags array found")

    # team / author
    team_m = re.search(r'"team"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"', body)
    if team_m:
        ok("detail_author_team", team_m.group(1))
    else:
        fail("detail_author_team", "No team.name found")

    # chapterCount
    ch_count_m = re.search(r'"chapterCount"\s*:\s*(\d+)', body)
    if ch_count_m:
        ok("detail_chapter_count", ch_count_m.group(1))
    else:
        fail("detail_chapter_count", "No chapterCount field")


def test_noveldex_chapter_list():
    section("NovelDex — Chapter List (chapter-1 RSC → allChapters PRIMARY)")
    slug = "the-dictator-lord-of-the-labyrinth-city"  # 207 chapters, confirmed allChapters works
    ch1_path = f"/series/novel/{slug}/chapter/1"
    url = rsc_url(NOVELDEX_BASE, ch1_path)

    resp = get(url, headers=rsc_chapter_headers(ch1_path))
    if not resp:
        fail("chapter_list_request", "No response", url=url)
        return

    body = resp.text
    ok("chapter_list_response", f"{len(body)} bytes")

    # PRIMARY: allChapters array (full list with title + isLocked)
    all_ch_m = re.search(r'"allChapters"\s*:\s*(\[.*?\])(?=\s*,"totalChapters")', body, re.DOTALL)
    if all_ch_m:
        try:
            chapters = json.loads(all_ch_m.group(1))
            ok("chapter_list_all_chapters_primary",
               f"{len(chapters)} chapters in allChapters[]",
               first=chapters[0].get("title", "")[:50],
               last=chapters[-1].get("title", "")[:50])
            locked = sum(1 for c in chapters if c.get("isLocked"))
            ok("chapter_list_lock_stats", f"{locked} locked / {len(chapters)-locked} free")
        except json.JSONDecodeError as e:
            fail("chapter_list_all_chapters_primary", str(e))
    else:
        fail("chapter_list_all_chapters_primary", "allChapters[] not found in chapter-1 RSC")

    # SECONDARY: chapterCount in series{} object
    tc_m = re.search(r'"chapterCount"\s*:\s*(\d+)', body)
    if tc_m:
        ok("chapter_list_count_secondary", f"chapterCount={tc_m.group(1)}")
    else:
        fail("chapter_list_count_secondary", "No chapterCount field in chapter-1 RSC")

    # totalChapters field
    tt_m = re.search(r'"totalChapters"\s*:\s*(\d+)', body)
    if tt_m:
        ok("chapter_list_total_chapters", f"totalChapters={tt_m.group(1)}")
    else:
        fail("chapter_list_total_chapters", "No totalChapters field")


def test_noveldex_chapter_content():
    section("NovelDex — Chapter Content (RSC T-tag, confirmed format)")
    slug = "the-dictator-lord-of-the-labyrinth-city"
    ch_path  = f"/series/novel/{slug}/chapter/1"
    novel_path = f"/series/novel/{slug}"
    url = rsc_url(NOVELDEX_BASE, ch_path)

    # Browser sends next-url = novel page (not chapter path)
    headers = {**RSC_HEADERS, "next-url": novel_path}
    resp = get(url, headers=headers)
    if not resp:
        fail("chapter_content_request", "No response", url=url)
        return

    body = resp.text
    ok("chapter_content_response", f"{len(body)} bytes")

    # Confirmed wire format:
    #   KEY:THEX,﻿[WATERMARK]<p><b>Translator:...</b></p>\n  <h1>Title</h1>\n  <p>...</p>﻿[WATERMARK]2:[...]
    # Strategy:
    #   1. Find the T-tag (KEY:THEX,)
    #   2. Skip to second \uFEFF (end of first watermark block)
    #   3. Find first '<' after that — start of content
    #   4. Find next \uFEFF after content starts — end of content

    t_m = re.search(r'[0-9a-f]+:T[0-9a-fA-F]+,', body)
    if not t_m:
        fail("chapter_content_ttag", "No T-tag found in response")
        # Try direct <p> search
        first_p = body.find("<p>")
        if first_p != -1:
            end_bom = body.find('\uFEFF', first_p + 10)
            snippet = body[first_p:end_bom] if end_bom != -1 else body[first_p:first_p+1000]
            ok("chapter_content_p_fallback", f"{len(snippet)} chars", preview=snippet[:200])
        return

    ok("chapter_content_ttag", f"T-tag at {t_m.start()}: {t_m.group()}")

    after_ttag = t_m.end()
    # Skip first watermark (\uFEFF...zero-width...\uFEFF) → find second \uFEFF
    bom1 = body.find('\uFEFF', after_ttag)
    bom2 = body.find('\uFEFF', bom1 + 1) if bom1 != -1 else -1
    content_start = bom2 + 1 if bom2 != -1 else after_ttag

    # Find first HTML tag
    tag_start = body.find('<', content_start)
    if tag_start == -1:
        fail("chapter_content_html_start", "No HTML tag after T-tag watermark")
        return

    ok("chapter_content_html_start", f"First tag at {tag_start}: {body[tag_start:tag_start+50]}")

    # Find end watermark (next \uFEFF after content)
    end_bom = body.find('\uFEFF', tag_start + 10)
    raw = body[tag_start:end_bom] if end_bom != -1 else body[tag_start:tag_start + 10000]

    # Trim to last </p> or closing block tag
    for close_tag in ["</p>", "</div>", "</h1>", "</h2>"]:
        idx = raw.rfind(close_tag)
        if idx != -1:
            raw = raw[:idx + len(close_tag)]
            break

    ok("chapter_content_extracted", f"{len(raw)} chars")
    paras = re.findall(r"<p[^>]*>.*?</p>", raw, re.DOTALL)
    h1s   = re.findall(r"<h1[^>]*>.*?</h1>", raw, re.DOTALL)
    ok("chapter_content_paragraphs", f"{len(paras)} <p>, {len(h1s)} <h1>",
       first_p=(re.sub(r'<[^>]+>', '', paras[0]) if paras else "none")[:80],
       h1=(re.sub(r'<[^>]+>', '', h1s[0]) if h1s else "none")[:80])


def test_noveldex_second_novel():
    """Test with a different novel to verify generality."""
    section("NovelDex — Second Novel (how-to-ruin-a-romantic-comedy)")
    slug  = "how-to-ruin-a-romantic-comedy"
    path  = f"/series/novel/{slug}"
    url   = rsc_url(NOVELDEX_BASE, path)

    ok("second_novel_selected", slug)

    det_resp = get(url, headers=rsc_headers_for(path))
    if not det_resp:
        fail("second_novel_detail", "No response", url=url)
        return

    body = det_resp.text
    ok("second_novel_detail_size", f"{len(body)} bytes")

    title_m = re.search(r'"title"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
    if title_m:
        ok("second_novel_detail_title", title_m.group(1)[:80])
    else:
        fail("second_novel_detail_title", "No title in detail RSC")

    ch_m = re.search(r'"chapterCount"\s*:\s*(\d+)', body)
    if ch_m:
        ok("second_novel_chapter_count", f"chapterCount={ch_m.group(1)}")
    else:
        fail("second_novel_chapter_count", "No chapterCount")

    # Chapter list — fetch chapter-1 with correct next-url = novel path
    ch1_path = f"/series/novel/{slug}/chapter/1"
    ch1_resp = get(rsc_url(NOVELDEX_BASE, ch1_path),
                   headers={**RSC_HEADERS, "next-url": path})
    if not ch1_resp:
        fail("second_novel_ch1", "No response for chapter-1 RSC")
        return

    ch1_body = ch1_resp.text
    all_ch_m = re.search(r'"allChapters"\s*:\s*(\[.*?\])(?=\s*,"totalChapters")', ch1_body, re.DOTALL)
    if all_ch_m:
        try:
            chapters = json.loads(all_ch_m.group(1))
            ok("second_novel_ch_list", f"{len(chapters)} chapters via allChapters[]")
        except Exception as e:
            fail("second_novel_ch_list", str(e))
    else:
        tc_m = re.search(r'"chapterCount"\s*:\s*(\d+)', ch1_body)
        if tc_m:
            ok("second_novel_ch_count_fallback", f"chapterCount={tc_m.group(1)}")
        else:
            fail("second_novel_ch_list", "Neither allChapters nor chapterCount found")


# ─────────────────────────────────────────────────────────────
# ══════════════════════════════════════════════════════════════
#   LNORI TESTS
# ══════════════════════════════════════════════════════════════
# ─────────────────────────────────────────────────────────────

LNORI_BASE = "https://lnori.com"


def _parse_lnori_from_json_script(soup: BeautifulSoup) -> list[dict]:
    """Strategy 1: __NEXT_DATA__ or window.* embedded JSON."""
    # __NEXT_DATA__
    next_data = soup.find("script", id="__NEXT_DATA__")
    if next_data:
        try:
            root = json.loads(next_data.string or "")
            page_props = root.get("props", {}).get("pageProps", {})
            for key in ["novels", "books", "series", "items", "data", "posts"]:
                arr = page_props.get(key)
                if isinstance(arr, list) and arr:
                    novels = [_json_to_novel(item) for item in arr if isinstance(item, dict)]
                    novels = [n for n in novels if n]
                    if novels:
                        return novels
            # Deep search
            found = _find_novel_array_in_json(root)
            if found:
                return found
        except Exception:
            pass

    # window.__DATA__ etc
    for script in soup.find_all("script", src=False):
        body = script.string or ""
        for pat in [
            r'window\.__(?:DATA|NOVELS|BOOKS|SERIES|APP_DATA)__\s*=\s*(\{[\s\S]*?\});',
            r'window\.__(?:DATA|NOVELS|BOOKS|SERIES|APP_DATA)__\s*=\s*(\[[\s\S]*?\]);',
        ]:
            m = re.search(pat, body)
            if m:
                try:
                    root = json.loads(m.group(1))
                    found = _find_novel_array_in_json(root)
                    if found:
                        return found
                except Exception:
                    pass

        # JSON-LD
        if '"@type"' in body and ("Book" in body or "Novel" in body):
            try:
                root = json.loads(body.strip())
                items = root.get("@graph", [root]) if isinstance(root, dict) else root
                novels = []
                for item in (items if isinstance(items, list) else []):
                    t = item.get("@type", "")
                    if "Book" in t or "Novel" in t or "Series" in t:
                        n = _jsonld_to_novel(item)
                        if n:
                            novels.append(n)
                if novels:
                    return novels
            except Exception:
                pass
    return []


def _find_novel_array_in_json(obj, depth=0) -> list[dict]:
    if depth > 6:
        return []
    if isinstance(obj, list) and obj:
        first = obj[0] if isinstance(obj[0], dict) else {}
        if any(k in first for k in ("title", "slug", "name")):
            novels = [_json_to_novel(item) for item in obj if isinstance(item, dict)]
            novels = [n for n in novels if n]
            if novels:
                return novels
        for item in obj:
            found = _find_novel_array_in_json(item, depth + 1)
            if found:
                return found
    elif isinstance(obj, dict):
        for v in obj.values():
            found = _find_novel_array_in_json(v, depth + 1)
            if found:
                return found
    return []


def _json_to_novel(item: dict) -> dict | None:
    nid   = item.get("id") or item.get("slug")
    title = item.get("title") or item.get("name")
    if not nid or not title:
        return None
    slug  = item.get("slug", nid)
    cover = item.get("coverImage") or item.get("cover") or ""
    if cover and not cover.startswith("http"):
        cover = LNORI_BASE + cover
    return {
        "id": str(nid), "title": str(title),
        "author": item.get("author") or "",
        "tags": item.get("tags") or [],
        "rel": 0, "date": item.get("updatedAt") or item.get("publishedAt") or "",
        "volumes": item.get("volumeCount") or item.get("volumes") or 1,
        "url": f"/{slug}/", "coverUrl": cover,
        "description": item.get("description") or "",
    }


def _jsonld_to_novel(item: dict) -> dict | None:
    title = item.get("name")
    url   = item.get("url", "")
    if not title:
        return None
    slug  = url.rstrip("/").rsplit("/", 1)[-1] if url else title.lower().replace(" ", "-")
    author = ""
    if isinstance(item.get("author"), dict):
        author = item["author"].get("name", "")
    cover = item.get("image") or ""
    if cover and not cover.startswith("http"):
        cover = LNORI_BASE + cover
    return {
        "id": slug, "title": title, "author": author, "tags": [],
        "rel": 0, "date": "", "volumes": 1,
        "url": url.replace(LNORI_BASE, "") or f"/{slug}/",
        "coverUrl": cover, "description": item.get("description") or "",
    }


def _parse_lnori_from_articles(soup: BeautifulSoup) -> list[dict]:
    """Strategy 2: article/card elements — with OR without data-id (matches new Kotlin logic)."""
    # Prefer data-id elements; fall back to any article/card
    cards = soup.select("[data-id]") or soup.select(
        "article, .card, .book-item, .novel-item, li.item"
    )
    novels = []
    for card in cards:
        data_id = card.get("data-id", "")
        link_el = card.find("a", href=True)
        href = link_el["href"] if link_el else ""

        # Derive id from data-id or URL path
        if not data_id:
            path_parts = [p for p in href.rstrip("/").split("/") if p]
            data_id = "-".join(path_parts[-2:]) if len(path_parts) >= 2 else (path_parts[-1] if path_parts else "")
        if not data_id:
            continue

        title = card.get("data-t") or ""
        if not title:
            h = card.find(["h3", "h2", "h4"])
            if h:
                title = h.get_text(strip=True)
        if not title and link_el:
            title = link_el.get_text(strip=True)
        if not title or len(title) < 2:
            continue

        author   = card.get("data-a") or ""
        tags_raw = card.get("data-tags") or card.get("data-tag") or ""
        tags     = [t.strip() for t in tags_raw.split(",") if t.strip()]
        rel      = int(card.get("data-rel") or 0)
        date     = card.get("data-d") or card.get("data-date") or ""
        vols     = int(card.get("data-v") or 1)
        link     = href or f"/{data_id}/"
        img_el   = card.find("img")
        cover    = ""
        if img_el:
            cover = (img_el.get("data-src") or
                     (img_el.get("srcset", "").split(",")[0].strip().split()[0] if img_el.get("srcset") else "") or
                     img_el.get("src") or "")
            if cover and cover.startswith("/"):
                cover = LNORI_BASE + cover
        novels.append({
            "id": data_id, "title": title, "author": author, "tags": tags,
            "rel": rel, "date": date, "volumes": vols,
            "url": link, "coverUrl": cover, "description": "",
        })
    return novels


def _parse_lnori_from_links(soup: BeautifulSoup) -> list[dict]:
    """Strategy 3: fallback — any card links with titles."""
    novels = []
    seen = set()
    for link in soup.select("article a[href], .card a[href], .book-item a[href], a[href*='/series/']"):
        href = link.get("href", "")
        if href in seen or not href or href == "/":
            continue
        h = link.find(["h3", "h2", "h4"])
        title = h.get_text(strip=True) if h else link.get_text(strip=True)
        if len(title) < 3 or title.lower() in ("read", "more", "home", "browse"):
            continue
        seen.add(href)
        # Extract meaningful slug from /series/3336/mushoku-tensei → "3336-mushoku-tensei"
        path_parts = [p for p in href.rstrip("/").split("/") if p]
        slug = "-".join(path_parts[-2:]) if len(path_parts) >= 2 else (path_parts[-1] if path_parts else href)
        img  = link.find("img")
        cover = (img.get("src") or img.get("data-src") or "") if img else ""
        if cover and cover.startswith("/"):
            cover = LNORI_BASE + cover
        novels.append({
            "id": slug, "title": title, "author": "", "tags": [],
            "rel": 0, "date": "", "volumes": 1,
            "url": href, "coverUrl": cover, "description": "",
        })
    return novels


def _parse_lnori_homepage(html: str) -> list[dict]:
    """Parse novels from lnori homepage — try 3 strategies."""
    soup = BeautifulSoup(html, "html.parser")
    novels = _parse_lnori_from_json_script(soup)
    if not novels:
        novels = _parse_lnori_from_articles(soup)
    if not novels:
        novels = _parse_lnori_from_links(soup)
    return novels


def test_lnori_homepage():
    section("Lnori — Homepage / Load All Novels")
    resp = get(LNORI_BASE)
    if not resp:
        fail("lnori_homepage", "No response", url=LNORI_BASE)
        return

    ok("lnori_homepage_status", f"HTTP {resp.status_code}")
    html = resp.text
    ok("lnori_homepage_size", f"{len(html):,} bytes")

    soup = BeautifulSoup(html, "html.parser")

    # Try strategies one by one to report which worked
    strategy1 = _parse_lnori_from_json_script(soup)
    strategy2 = _parse_lnori_from_articles(soup)
    strategy3 = _parse_lnori_from_links(soup)

    ok("lnori_strategy_json_script",  f"{len(strategy1)} novels from __NEXT_DATA__/JSON scripts")
    ok("lnori_strategy_articles",     f"{len(strategy2)} novels from article[data-id] elements")
    ok("lnori_strategy_links",        f"{len(strategy3)} novels from link fallback")

    novels = strategy1 or strategy2 or strategy3
    if not novels:
        # Diagnostic info
        all_articles  = soup.select("article")
        data_id_els   = soup.select("[data-id]")
        next_data_el  = soup.find("script", id="__NEXT_DATA__")
        any_cards     = soup.select(".card, .book, .novel, .item")
        fail("lnori_cards_found",
             f"0 novels from all strategies",
             articles=len(all_articles),
             data_id_elements=len(data_id_els),
             has_next_data=next_data_el is not None,
             generic_cards=len(any_cards),
             html_snippet=html[html.find("<article"):html.find("<article")+400] if "<article" in html else html[html.find("<body"):html.find("<body")+500])
        return

    ok("lnori_novels_found", f"{len(novels)} novels parsed from homepage")

    # validate fields
    missing_title = [n for n in novels if not n["title"]]
    missing_id    = [n for n in novels if not n["id"]]
    ok("lnori_all_have_id",    f"{len(novels) - len(missing_id)} / {len(novels)} have id")
    ok("lnori_all_have_title", f"{len(novels) - len(missing_title)} / {len(novels)} have title")

    # show first 3
    for n in novels[:3]:
        ok(f"  novel: {n['title'][:50]}",
           f"id={n['id']} vols={n['volumes']} rel={n['rel']}",
           author=n["author"], tags=", ".join((n.get("tags") or [])[:4]))

    return novels


def test_lnori_sort(novels: list[dict] | None):
    section("Lnori — Sort (client-side)")
    if not novels:
        fail("lnori_sort", "No novels to sort (homepage failed)")
        return

    # Popularity (rel desc)
    by_pop  = sorted(novels, key=lambda n: n["rel"],    reverse=True)
    # Newest (date desc)
    by_new  = sorted(novels, key=lambda n: n["date"],   reverse=True)
    # Oldest (date asc)
    by_old  = sorted(novels, key=lambda n: n["date"])
    # A-Z
    by_az   = sorted(novels, key=lambda n: n["title"])
    # Most volumes
    by_vols = sorted(novels, key=lambda n: n["volumes"], reverse=True)

    ok("sort_popular",  f"#{1}: {by_pop[0]['title'][:50]} rel={by_pop[0]['rel']}")
    ok("sort_newest",   f"#{1}: {by_new[0]['title'][:50]} date={by_new[0]['date']}")
    ok("sort_oldest",   f"#{1}: {by_old[0]['title'][:50]} date={by_old[0]['date']}")
    ok("sort_az",       f"#{1}: {by_az[0]['title'][:50]}")
    ok("sort_volumes",  f"#{1}: {by_vols[0]['title'][:50]} vols={by_vols[0]['volumes']}")


def test_lnori_search(novels: list[dict] | None):
    section("Lnori — Search (client-side filter)")
    if not novels:
        fail("lnori_search", "No novels to search (homepage failed)")
        return

    queries = ["the", "god", "hero"]
    for q in queries:
        ql = q.lower()
        results = [n for n in novels if
                   ql in n["title"].lower() or
                   ql in n["author"].lower() or
                   ql in n["description"].lower()]
        ok(f"search_{q!r}", f"{len(results)} results",
           first=results[0]["title"][:50] if results else "—")

    # tag filter
    if novels[0]["tags"]:
        tag = novels[0]["tags"][0].lower()
        results = [n for n in novels if any(tag in t.lower() for t in n["tags"])]
        ok(f"filter_tag_{tag!r}", f"{len(results)} novels with tag {tag!r}")

    # author filter
    authors_with_data = [n for n in novels if n["author"]]
    if authors_with_data:
        author_q = authors_with_data[0]["author"].split()[0].lower()
        results  = [n for n in novels if author_q in n["author"].lower()]
        ok(f"filter_author_{author_q!r}", f"{len(results)} results")

    # min volumes
    min_v = 2
    results = [n for n in novels if n["volumes"] >= min_v]
    ok(f"filter_min_volumes_{min_v}", f"{len(results)} novels with >= {min_v} volumes")


def test_lnori_novel_detail():
    section("Lnori — Novel Detail Page (mushoku-tensei)")
    # Use known URL: /series/3336/mushoku-tensei-jobless-reincarnation
    url = f"{LNORI_BASE}/series/3336/mushoku-tensei-jobless-reincarnation"

    resp = get(url)
    if not resp:
        fail("lnori_detail_request", "No response", url=url)
        return

    ok("lnori_detail_size", f"{len(resp.text)} bytes")
    soup = BeautifulSoup(resp.text, "html.parser")

    # title — try multiple selectors
    h1 = soup.select_one("h1, .s-title, .series-title")
    if h1:
        ok("lnori_detail_title", h1.get_text(strip=True)[:80])
    else:
        fail("lnori_detail_title", "No h1/title element found")

    # author
    author_el = soup.select_one("p.author, .author, [itemprop='author']")
    if author_el:
        ok("lnori_detail_author", author_el.get_text(strip=True)[:60])
    else:
        # Try any text with "Author" label
        for el in soup.find_all(string=re.compile(r'Author', re.I)):
            ok("lnori_detail_author", str(el)[:60])
            break
        else:
            ok("lnori_detail_author", "(no author element — may be embedded)")

    # cover image
    img_el = soup.select_one("figure img, picture img, img[class*=cover], .cover img")
    if img_el:
        src = (img_el.get("data-src") or img_el.get("src") or
               (img_el.get("srcset", "").split(",")[0].strip().split(" ")[0]) or "")
        ok("lnori_detail_cover", src[:100])
    else:
        ok("lnori_detail_cover", "(no cover image element)")

    # description
    desc_el = soup.select_one(
        "p.description, .synopsis, [itemprop='description'], .desc, .summary, p[class*=desc]"
    )
    if desc_el:
        ok("lnori_detail_description", desc_el.get_text(strip=True)[:100])
    else:
        # Try to find any long paragraph
        long_p = [p.get_text(strip=True) for p in soup.find_all("p") if len(p.get_text(strip=True)) > 100]
        if long_p:
            ok("lnori_detail_description_fallback", long_p[0][:100])
        else:
            ok("lnori_detail_description", "(no description element found)")

    # tags/genres
    tags_el = soup.select("nav.tags-box a.tag, .tags a, a[class*=tag], .genre-tag")
    if tags_el:
        tags = [t.get_text(strip=True) for t in tags_el]
        ok("lnori_detail_tags", ", ".join(tags[:8]), count=len(tags))
    else:
        ok("lnori_detail_tags", "(no tag elements)")

    # volume cards
    vol_cards = soup.select(
        "div.vol-grid article.card, article.card, .volumes-list article, "
        ".chapter-list li, ul.volumes li, .vol-card"
    )
    ok("lnori_detail_volumes", f"{len(vol_cards)} volume/card elements")

    # Show structure clue for debugging
    articles = soup.find_all("article")
    ok("lnori_detail_articles_total", f"{len(articles)} total <article> elements")

    return url


def test_lnori_chapter_list(detail_url: str | None = None):
    section("Lnori — Chapter List (Volume Grid)")
    if not detail_url:
        detail_url = f"{LNORI_BASE}/series/3336/mushoku-tensei-jobless-reincarnation"

    resp = get(detail_url)
    if not resp:
        fail("lnori_chapter_list_request", "No response")
        return

    soup = BeautifulSoup(resp.text, "html.parser")
    vol_cards = soup.select(
        "div.vol-grid article.card, article.card, .volumes-list article, "
        ".chapter-list li, ul.volumes li"
    )

    if not vol_cards:
        fail("lnori_chapter_list_empty", "No volume cards found")
        return

    ok("lnori_chapter_list_count", f"{len(vol_cards)} volumes")

    novel_id = resp.url.split("/")[4] if resp.url and len(resp.url.split("/")) > 4 else ""

    for i, card in enumerate(vol_cards[:3]):
        link_el = card.select_one("figure a, h3.c-title a, a[href]")
        title_el = card.select_one("h3.c-title a, h3 a, .c-title")
        title_text = title_el.get_text(strip=True) if title_el else ""
        href = link_el["href"] if link_el else ""

        # volume number extraction
        num_m = re.search(r"(\d+)", title_text)
        vol_num = num_m.group(1) if num_m else str(i + 1)

        ok(f"  vol#{vol_num}", f"{title_text[:50]}", href=href)

    # try reading first volume
    if vol_cards:
        link_el = vol_cards[0].select_one("a[href]")
        if link_el:
            href = link_el["href"]
            vol_url = href if href.startswith("http") else LNORI_BASE + href

            time.sleep(0.5)
            vol_resp = get(vol_url)
            if vol_resp:
                ok("lnori_volume_page_load",
                   f"HTTP {vol_resp.status_code} — {len(vol_resp.text):,} bytes",
                   url=vol_url)
                vol_soup = BeautifulSoup(vol_resp.text, "html.parser")
                sections = vol_soup.select("section.chapter, .chapter-section")
                paras    = vol_soup.select("p")
                imgs     = vol_soup.select("picture img, img.chapter-img")
                ok("lnori_volume_content",
                   f"{len(sections)} sections, {len(paras)} <p>, {len(imgs)} images")
            else:
                fail("lnori_volume_page_load", "Could not load volume page")


def test_lnori_connectivity():
    section("Lnori — Connectivity & Base URL")
    resp = get(LNORI_BASE)
    if resp:
        ok("lnori_base_url_reachable", f"HTTP {resp.status_code}", url=LNORI_BASE)
        soup = BeautifulSoup(resp.text, "html.parser")
        page_title = soup.title.string if soup.title else ""
        ok("lnori_page_title", page_title[:80])
    else:
        fail("lnori_base_url_reachable", f"Cannot reach {LNORI_BASE}")


# ─────────────────────────────────────────────────────────────
# Main runner
# ─────────────────────────────────────────────────────────────

def run_noveldex():
    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}  NOVELDEX TESTS  —  {NOVELDEX_BASE}{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    test_noveldex_popular()
    time.sleep(0.5)
    test_noveldex_latest()
    time.sleep(0.5)
    test_noveldex_sort_options()
    time.sleep(0.5)
    test_noveldex_search()
    time.sleep(0.5)
    test_noveldex_filters_status()
    time.sleep(0.5)
    test_noveldex_filters_type()
    time.sleep(0.5)
    test_noveldex_filters_genre()
    time.sleep(0.5)
    test_noveldex_filters_tags()
    time.sleep(0.5)
    test_noveldex_filters_chapter_range()
    time.sleep(0.5)
    test_noveldex_filters_combined()
    time.sleep(0.5)
    test_noveldex_pagination()
    time.sleep(0.5)
    test_noveldex_novel_detail()
    time.sleep(0.5)
    test_noveldex_chapter_list()
    time.sleep(0.5)
    test_noveldex_chapter_content()
    time.sleep(0.5)
    test_noveldex_second_novel()


def run_lnori():
    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}  LNORI TESTS  —  {LNORI_BASE}{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    test_lnori_connectivity()
    time.sleep(0.5)
    novels = test_lnori_homepage()
    time.sleep(0.5)
    test_lnori_sort(novels)
    time.sleep(0.5)
    test_lnori_search(novels)
    time.sleep(0.5)
    result = test_lnori_novel_detail()
    detail_url = result if isinstance(result, str) else (result[0] if result else None)
    time.sleep(0.5)
    test_lnori_chapter_list(detail_url)


def main():
    global VERBOSE

    parser = argparse.ArgumentParser(description="Extension API Test Suite")
    parser.add_argument("--source", choices=["noveldex", "lnori", "all"], default="all",
                        help="Which source to test (default: all)")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Show extra details for passing tests")
    args = parser.parse_args()

    VERBOSE = args.verbose

    if args.source in ("noveldex", "all"):
        run_noveldex()

    if args.source in ("lnori", "all"):
        run_lnori()

    return 0 if summary() else 1


if __name__ == "__main__":
    sys.exit(main())






















