import urllib.request, re, json

req = urllib.request.Request('https://lnori.com', headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
})
html = urllib.request.urlopen(req, timeout=15).read().decode('utf-8', errors='replace')
print(f"HTML size: {len(html)} bytes")

# Find article tags
arts = re.findall(r'<article[^>]*>', html)
print(f'\nTotal <article> tags: {len(arts)}')
for a in arts[:5]:
    print(' ', repr(a[:300]))

# data-id anywhere
data_ids = re.findall(r'data-id=["\']([^"\']+)["\']', html)
print(f'\ndata-id occurrences: {len(data_ids)}, first 3: {data_ids[:3]}')

data_t = re.findall(r'data-t=["\']([^"\']+)["\']', html)
print(f'data-t occurrences: {len(data_t)}, first 3: {data_t[:3]}')

# Look for JSON embedded data
json_matches = re.findall(r'window\.__[A-Z_]+\s*=\s*(\{.*?\});', html, re.DOTALL)
print(f'\nwindow.__ JSON blocks: {len(json_matches)}')

# Look for <script> with novel data
scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL)
print(f'Script blocks: {len(scripts)}')
for i, s in enumerate(scripts):
    if 'novel' in s.lower() or 'book' in s.lower() or 'slug' in s.lower():
        print(f'  Script {i}: {s[:300]}')

# Save the first 5000 chars of html body area
body_pos = html.find('<body')
print(f'\n--- HTML from <body (first 3000 chars) ---')
print(html[body_pos:body_pos+3000])

# Save to file for inspection
with open('lnori_homepage.html', 'w', encoding='utf-8') as f:
    f.write(html)
print('\nSaved to lnori_homepage.html')

