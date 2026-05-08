# OfflinePay Dashboard

Single-page live console. Zero build step — just open `index.html` (or serve
the folder with any static server).

## Run

While the backend is up on `http://localhost:4000`:

```bash
# Option A — Python static server
cd dashboard && python -m http.server 5173

# Option B — Node http-server
npx http-server dashboard -p 5173 -c-1

# Option C — open the file directly in your browser
# (Chrome/Edge are fine; CORS is handled by the backend)
```

Then visit <http://localhost:5173>.

## What it shows

- Live counts: vouchers issued / redeemed (offline) / settled (on-chain)
- Cumulative settled USDC volume
- Real-time voucher feed (auto-refresh every 1.5 s)
- Demo console — click-through end-to-end (top-up → issue → tap → settle)
- Health pill: chain id + latest block

## Configuring API endpoint

If your backend runs on a different host, set `window.OFFLINEPAY_API` before
the main script tag, or open the dev tools and patch `API`.
