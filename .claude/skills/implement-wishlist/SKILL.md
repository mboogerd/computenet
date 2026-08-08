---
name: implement-wishlist
description: Fetch latest main, create WISHLIST.md on a new branch, commit, push, and open a PR. Use when the user says "implement wishlist" or "/implement-wishlist".
---

Run these steps in order:

```bash
git fetch origin main
git checkout -b wishlist-$(date +%Y%m%d%H%M%S) origin/main
```

Create `WISHLIST.md` at repo root (ask the user for content if not provided; otherwise start with a single `# Wishlist` heading and an empty list).

```bash
git add WISHLIST.md
git commit -m "Add WISHLIST.md"
git push -u origin HEAD
gh pr create --title "Add WISHLIST.md" --body "Adds an initial WISHLIST.md file." --base main
```

Report the PR URL when done.
