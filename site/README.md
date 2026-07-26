# site/

Source for the public privacy policy, live at https://wolfeleo2.github.io/Thingy/

Edit `privacy-policy.html` and push to `main` — `.github/workflows/pages.yml` copies it to the
published root as `index.html` and deploys. There is no second copy to keep in sync.

## One-time setup

GitHub repo → Settings → Pages → Source: **GitHub Actions** (not "Deploy from a branch").
The old `gh-pages` branch is no longer used and can be deleted.
