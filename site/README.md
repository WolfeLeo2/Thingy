# site/

Source for the public privacy policy. `privacy-policy.html` here is the one to edit — the
published copy lives on the `gh-pages` branch as `index.html` and is not auto-synced.

## To publish an edit

```sh
git worktree add /tmp/thingy-gh-pages gh-pages
cp site/privacy-policy.html /tmp/thingy-gh-pages/index.html
cd /tmp/thingy-gh-pages && git commit -am "Update privacy policy" && git push
git worktree remove /tmp/thingy-gh-pages
```

## One-time setup (not done yet)

GitHub repo → Settings → Pages → Source: "Deploy from a branch" → Branch: `gh-pages`, folder `/ (root)`.
Once enabled, the policy is live at the URL GitHub shows there.
