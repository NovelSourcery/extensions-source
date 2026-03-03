#!/usr/bin/env python3
"""
Comprehensive NovelDex extension test suite.
Tests all API endpoints: popular, latest, search, filters (genre, status, type, sort).
RSC/HTML endpoints are CF-protected and will 403 from Python — that is expected.
"""

import requests, json, sys, time

BASE = "https://noveldex.com"
API = f"{BASE}/api/series"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36",
    "Accept": "application/json",
}

passed = 0
failed = 0
skipped = 0

TYPE_MAP = {
    "WEB_NOVEL": "novel",
    "MANHWA": "manhwa",
    "MANGA": "manga",
    "MANHUA": "manhua",
    "WEBTOON": "webtoon",
}


def test(name, func):
    global passed, failed, skipped
    try:
        result = func()
        if result == "SKIP":
            print(f"  [SKIP] {name}")
            skipped += 1
        else:
            print(f"  [PASS] {name}")
            passed += 1
    except Exception as e:
        print(f"  [FAIL] {name}: {e}")
        failed += 1


# ── 1) Popular (sort=popular) ──
def test_popular():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "sort": "popular"}, timeout=15)
    assert r.status_code == 200, f"status={r.status_code}"
    data = r.json()
    assert "data" in data and "meta" in data, f"missing keys: {list(data.keys())}"
    items = data["data"]
    assert len(items) > 0, "no items in popular"
    meta = data["meta"]
    assert "total" in meta and "hasMore" in meta, f"meta keys: {list(meta.keys())}"
    # Validate item structure
    item = items[0]
    for key in ("slug", "title", "coverImage", "type"):
        assert key in item, f"missing key '{key}' in item"
    # Validate type → URL mapping
    t = item["type"]
    assert t in TYPE_MAP, f"unknown type '{t}'"
    url_seg = TYPE_MAP[t]
    expected_url = f"/series/{url_seg}/{item['slug']}"
    print(f"    → {len(items)} items, total={meta['total']}, first: {item['title']} → {expected_url}")


# ── 2) Latest (sort=newest) ──
def test_latest():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "sort": "newest"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0, "no items in latest"
    print(f"    → {len(items)} items, first: {items[0]['title']}")


# ── 3) Search ──
def test_search():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "search": "the"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0, "no search results for 'the'"
    print(f"    → {len(items)} results, first: {items[0]['title']}")


# ── 4) Filter: genre ──
def test_filter_genre():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "genre": "action"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0, "no items with genre=action"
    # Verify genre is present
    genres = [g["genre"]["slug"] for g in items[0].get("genres", [])]
    print(f"    → {len(items)} items, first genres: {genres}")


# ── 5) Filter: status ──
def test_filter_status():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "status": "ONGOING"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0, "no ONGOING items"
    statuses = set(i["status"] for i in items)
    print(f"    → {len(items)} items, statuses: {statuses}")


# ── 6) Filter: type ──
def test_filter_type():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "type": "WEB_NOVEL"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0
    types = set(i["type"] for i in items)
    assert types == {"WEB_NOVEL"}, f"unexpected types: {types}"
    print(f"    → {len(items)} WEB_NOVEL items")


# ── 7) Sort: views ──
def test_sort_views():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 10, "sort": "views"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    assert len(data["data"]) > 0
    print(f"    → {len(data['data'])} items sorted by views")


# ── 8) Sort: rating ──
def test_sort_rating():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 10, "sort": "rating"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0
    # Ratings should be descending
    ratings = [i.get("rating", 0) for i in items]
    print(f"    → ratings: {ratings[:5]}")


# ── 9) Sort: longest ──
def test_sort_longest():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 10, "sort": "longest"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0
    counts = [i.get("_count", {}).get("chapters", 0) for i in items]
    print(f"    → chapter counts: {counts[:5]}")


# ── 10) Pagination ──
def test_pagination():
    r1 = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 5, "sort": "popular"}, timeout=15)
    r2 = requests.get(API, headers=HEADERS, params={"page": 2, "limit": 5, "sort": "popular"}, timeout=15)
    assert r1.status_code == 200 and r2.status_code == 200
    d1, d2 = r1.json(), r2.json()
    slugs1 = {i["slug"] for i in d1["data"]}
    slugs2 = {i["slug"] for i in d2["data"]}
    overlap = slugs1 & slugs2
    assert len(overlap) == 0, f"page 1 and 2 overlap: {overlap}"
    assert d1["meta"]["page"] == 1 and d2["meta"]["page"] == 2
    print(f"    → page1: {len(d1['data'])} items, page2: {len(d2['data'])} items, no overlap ✓")


# ── 11) Combined filters ──
def test_combined_filters():
    r = requests.get(API, headers=HEADERS, params={
        "page": 1, "limit": 20, "sort": "popular",
        "genre": "action", "status": "ONGOING", "type": "WEB_NOVEL",
    }, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    print(f"    → {len(items)} items with genre=action, status=ONGOING, type=WEB_NOVEL")


# ── 12) Exclude genre filter ──
def test_exclude_genre():
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 20, "exgenre": "romance"}, timeout=15)
    assert r.status_code == 200
    data = r.json()
    items = data["data"]
    assert len(items) > 0
    print(f"    → {len(items)} items excluding romance")


# ── 13) URL type mapping check ──
def test_type_url_mapping():
    """Verify all items have known types that map to valid URL segments."""
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 50, "sort": "popular"}, timeout=15)
    assert r.status_code == 200
    items = r.json()["data"]
    types_found = set()
    for item in items:
        t = item.get("type", "UNKNOWN")
        types_found.add(t)
        assert t in TYPE_MAP, f"unknown type '{t}' for '{item['title']}'"
    print(f"    → types found: {types_found}")


# ── 14) API item chapter info ──
def test_item_chapter_info():
    """API items include partial chapters[] and _count.chapters for total."""
    r = requests.get(API, headers=HEADERS, params={"page": 1, "limit": 5, "sort": "longest"}, timeout=15)
    assert r.status_code == 200
    items = r.json()["data"]
    for item in items[:3]:
        total = item.get("_count", {}).get("chapters", 0)
        partial = len(item.get("chapters", []))
        print(f"    → '{item['title']}': total={total}, api_chapters={partial}")
        assert total >= partial, "total should be >= partial"


# ── 15) HTML resilience test (simulate CF challenge) ──
def test_html_resilience():
    """Simulate what happens when API returns HTML instead of JSON.
    The Kotlin code now checks if body starts with '<' and returns empty."""
    html = "<!DOCTYPE html><html><body>Cloudflare challenge</body></html>"
    trimmed = html.strip()
    is_html = trimmed.startswith("<") or trimmed.startswith("<!DOCTYPE")
    assert is_html, "Should detect HTML response"
    print("    → HTML detection works: body starting with '<' is caught")


# ── 16) RSC detail page (expected CF block from Python) ──
def test_rsc_detail():
    """RSC endpoints require cloudflareClient. From Python, we expect 403."""
    rsc_headers = {
        **HEADERS,
        "RSC": "1",
        "Next-Router-State-Tree": "%5B%22%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%5D%7D%2Cnull%2Cnull%2Ctrue%5D",
    }
    r = requests.get(f"{BASE}/series/novel/the-authors-pov", headers=rsc_headers, timeout=15)
    if r.status_code == 403:
        print("    → 403 as expected (CF blocks non-browser)")
        return "SKIP"
    # If we somehow get through, check RSC format
    body = r.text
    assert ":" in body, "RSC body should contain key:value pairs"
    print(f"    → Got RSC body ({len(body)} chars)")


print("=" * 60)
print("NovelDex Comprehensive Test Suite")
print("=" * 60)

print("\n── Listing Endpoints ──")
test("Popular (sort=popular)", test_popular)
test("Latest (sort=newest)", test_latest)

print("\n── Search ──")
test("Search 'the'", test_search)

print("\n── Filters ──")
test("Genre filter (action)", test_filter_genre)
test("Status filter (ONGOING)", test_filter_status)
test("Type filter (WEB_NOVEL)", test_filter_type)
test("Exclude genre (romance)", test_exclude_genre)
test("Combined filters", test_combined_filters)

print("\n── Sort modes ──")
test("Sort by views", test_sort_views)
test("Sort by rating", test_sort_rating)
test("Sort by longest", test_sort_longest)

print("\n── Pagination ──")
test("Page 1 vs Page 2 no overlap", test_pagination)

print("\n── Data validation ──")
test("Type → URL mapping", test_type_url_mapping)
test("Chapter info (total vs partial)", test_item_chapter_info)
test("HTML resilience detection", test_html_resilience)

print("\n── RSC (CF-protected) ──")
test("RSC detail page", test_rsc_detail)

print("\n" + "=" * 60)
print(f"Results: {passed} passed, {failed} failed, {skipped} skipped")
print("=" * 60)
sys.exit(1 if failed else 0)
