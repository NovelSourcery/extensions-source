import html
import sys
import json
from pathlib import Path
import shutil

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath("main/repo")

# defensively parse delete list argument
try:
    to_delete: list[str] = json.loads(sys.argv[1])
except Exception:
    to_delete = []

print(f"Remote repo: {REMOTE_REPO}")
print(f"Local repo (main/repo): {LOCAL_REPO}")
print(f"Modules to delete: {to_delete}")

for module in to_delete:
    apk_name = f"tsundoku-{module}-v*.*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.extension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

# Copy APKs/icons from local generated repo into the remote repo, if present
local_apk_dir = LOCAL_REPO.joinpath("apk")
local_icon_dir = LOCAL_REPO.joinpath("icon")
remote_apk_dir = REMOTE_REPO.joinpath("apk")
remote_icon_dir = REMOTE_REPO.joinpath("icon")

if local_apk_dir.exists():
    print(f"Copying APKs from {local_apk_dir} to {remote_apk_dir}")
    remote_apk_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src=local_apk_dir, dst=remote_apk_dir, dirs_exist_ok=True)
else:
    print(f"Warning: local apk dir {local_apk_dir} does not exist; skipping APK copy")

if local_icon_dir.exists():
    print(f"Copying icons from {local_icon_dir} to {remote_icon_dir}")
    remote_icon_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src=local_icon_dir, dst=remote_icon_dir, dirs_exist_ok=True)
else:
    print(f"Warning: local icon dir {local_icon_dir} does not exist; skipping icon copy")

# Load remote index.json if present, else default to empty list
remote_index_path = REMOTE_REPO.joinpath("index.json")
if remote_index_path.exists():
    with remote_index_path.open() as remote_index_file:
        try:
            remote_index = json.load(remote_index_file)
        except Exception:
            print(f"Warning: failed to parse {remote_index_path}; using empty remote index")
            remote_index = []
else:
    print(f"Warning: {remote_index_path} not found; starting with empty remote index")
    remote_index = []

# Load local index.min.json if present, else default to empty list
local_index_path = LOCAL_REPO.joinpath("index.min.json")
if local_index_path.exists():
    with local_index_path.open() as local_index_file:
        try:
            local_index = json.load(local_index_file)
        except Exception:
            print(f"Warning: failed to parse {local_index_path}; using empty local index")
            local_index = []
else:
    print(f"Warning: {local_index_path} not found; using empty local index")
    local_index = []

# Merge indices: remove deleted modules from remote_index then extend with local_index
index = [
    item for item in remote_index
    if not any([item.get("pkg", "").endswith(f".{module}") for module in to_delete])
]
index.extend(local_index)
index.sort(key=lambda x: x.get("pkg", ""))

# Write merged index.json
with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

# Remove versionId from sources and write index.min.json
for item in index:
    for source in item.get("sources", []):
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

# Generate index.html listing
with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry.get("apk", ""))
        name_escaped = html.escape(entry.get("name", ""))
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>')
