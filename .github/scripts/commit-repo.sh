#!/bin/bash
set -e

git config --global user.email "<>"
git config --global user.name "GitHub Actions Bot"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    curl https://purge.jsdelivr.net/gh/novelsourcery/extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
