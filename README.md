# ⚾ Kalshi Live Baseball

A real-time dashboard and automated trading bot for **Kalshi** MLB game markets. It
lists every baseball game currently tradeable on Kalshi, streams live bid/ask prices,
enriches each game with the live score and inning from the MLB Stats API, and — on an
opt-in basis — runs a trading bot on late-inning games whose trade decisions can be
driven either by a built-in analytical model **or by the Claude API**.

The whole thing is a single self-contained Java app: a built-in HTTP server serves a
live web UI, prices stream over the Kalshi WebSocket (with a polling fallback). The
core dashboard depends only on Gson; the optional AI decision engine adds the Anthropic
Java SDK.

---

## Screenshots

### Live dashboard
Games grouped by series, **LIVE** games (with score + inning) pinned to the top, each card
showing both teams' market ID, bid, ask, and an implied win-% bar. Prices update in place
in real time.

![Dashboard](docs/screenshots/dashboard.png)

### Bid / ask sizes
Each price shows the resting order size beneath it.

![Bid/ask sizes](docs/screenshots/sizes.png)

---

## Features

- **Live market dashboard** — all tradeable Kalshi MLB games (`KXMLBGAME`), grouped by
  series, live games first, as a responsive dark card grid.
- **Real-time bid/ask** — prices, sizes, and implied win-% stream over the Kalshi
  WebSocket and patch the page in place (Server-Sent Events); automatic **2s REST polling
  fallback** when no API credentials are configured.
- **Live score & inning** — joined from the free MLB Stats API (Kalshi has no score data),
  matched to each Kalshi game by team + start time.
- **Bid/ask sizes** — resting contract size shown under each price.
- **Account balance** — cash, portfolio value, and total shown in the header when API
  credentials are present.
- **Environment switch** — run against Kalshi **production** or **demo**; a colored header
  badge (blue `PRODUCTION` / amber `DEMO`) shows which you're on. Run both at once on two ports.
- **Automated trading bot (opt-in)** — watches live games in **innings 7+**, judges each
  candidate (win-probability estimate + edge from live game state: score, inning, outs,
  runners, bullpen changes), and rests **careful post-only limit orders** only on strong,
  high-confidence edges. A small number of high-quality trades (hard caps: **≤100 order
  placements and ≤50 contracts per game**, plus account-wide exposure and cash-reserve
  limits). Per traded game the UI shows a 🤖 row with bids placed and expected payoff.
  **Environment-gated and defaults to demo only.**
- **Pluggable decision engine** — the trade decision runs through a `TradeDecider` seam with
  two implementations: a **deterministic** closed-form win-probability model, and a
  **Claude-driven** decider that sends each candidate's market context to the Claude API and
  gets back a structured trade/no-trade decision with reasoning. A separate `RiskEnforcer`
  applies every hard risk limit *after* the decider, so the AI can decline a trade but can
  never bypass a risk limit. Claude is only called for candidates that pass a cheap
  deterministic pre-screen (cost control), and **falls back to the deterministic model** on
  any API timeout, rate limit, malformed response, or exhausted daily call budget.

> ⚠️ Neither the analytical model nor the AI is a profit guarantee. The bot is disabled by
> default outside the demo environment and exists for educational use. The AI decider is a
> demonstration of LLM-in-the-loop decisioning, not trading advice.

---

## Architecture

```
Kalshi REST/WebSocket ─┐
                       ├─► QuoteStore ──(SSE)──► browser (in-place price patching)
MLB Stats API ─────────┘        ▲
                                │
GamesService (fetch + match) ───┘

TradingBot ─► pre-screen ─► TradeDecider ─► RiskEnforcer ─► KalshiOrders (post-only limits)
                            ├─ DeterministicDecider (analytical win-prob model)
                            └─ ClaudeDecider ──► Claude API (structured decision)
                                    └─(on failure)─► DeterministicDecider (fallback)
```

- Built-in JDK `com.sun.net.httpserver` serves the UI; JDK WebSocket client + RSA signing;
  Gson for JSON. No web framework. The optional AI decider uses the Anthropic Java SDK.
- Key packages under `src/main/java/com/kg/predictions/`: `kalshi/` (REST, auth, orders),
  `feed/` (WebSocket + polling price feeds), `mlb/` (schedule + live feed), `bot/`
  (win-probability model, decider seam, risk enforcer, engine), `web/` (server, JSON, SSE),
  `model/`.
- **Decision flow:** `TradingBot` runs a cheap deterministic pre-screen, asks the configured
  `TradeDecider` for a raw judgment, then `RiskEnforcer` clamps that suggestion to every hard
  limit (win-prob floor, price ceiling, min edge, per-order/per-game/account/cash caps) before
  any order is placed. `KalshiOrders` re-checks the environment gate as defense in depth.
- See `CLAUDE.md` for a full architecture map.

---

## Prerequisites & requirements

- **Java 23** (JDK)
- **Apache Maven** 3.9+
- macOS / Linux (uses `lsof` in the helper scripts; tested on macOS)
- Internet access to `api.elections.kalshi.com` / `demo-api.kalshi.co` and `statsapi.mlb.com`
- **Optional — for live prices, balance, and the trading bot:** a Kalshi API key
  (key ID + RSA private key). The dashboard works without one via REST polling.
- **Optional — for AI-driven trade decisions:** an Anthropic API key (`ANTHROPIC_API_KEY`).
  Without it (or with `AI_ENABLED=false`) the bot uses the built-in deterministic model.

---

## Installation

```bash
git clone <your-repo-url>
cd PredictionsMarket
mvn -q compile
```

### (Optional) Kalshi API credentials

Live WebSocket prices, account balance, and the trading bot require a Kalshi API key. The
private key must be **PKCS#8** PEM (`-----BEGIN PRIVATE KEY-----`); convert an old-style
key with:

```bash
openssl pkcs8 -topk8 -nocrypt -in old-key.pem -out ~/.kalshi/kalshi-private-key.pem
chmod 600 ~/.kalshi/kalshi-private-key.pem
```

Then export (never commit these):

```bash
export KALSHI_API_KEY_ID="your-key-id-uuid"
export KALSHI_PRIVATE_KEY_PATH="$HOME/.kalshi/kalshi-private-key.pem"
```

---

## Usage

### Run the web UI

```bash
./run.sh                 # web UI at http://localhost:8080
```

`run.sh` frees the port, rebuilds, and runs in the foreground (Ctrl+C to stop). Equivalent to
`mvn -q compile exec:java`.

### Console report instead of the web UI

```bash
./run.sh console         # one-shot text report
```

### Choose environment (production vs demo)

```bash
KALSHI_ENV=demo ./run.sh        # demo/sandbox; unset = production (default)
```

### Run production and demo at the same time

```bash
# each instance uses its own credentials
export KALSHI_PROD_API_KEY_ID="…"   KALSHI_PROD_PRIVATE_KEY_PATH="$HOME/.kalshi/kalshi-private-key.pem"
export KALSHI_DEMO_API_KEY_ID="…"   KALSHI_DEMO_PRIVATE_KEY_PATH="$HOME/.kalshi/kalshi-demo-key.pem"
./run-both.sh            # production on :8080, demo on :8081
```

### Enable the trading bot

The bot needs credentials and is gated to a configurable set of environments. **By default it
only trades on `demo`.**

```bash
KALSHI_ENV=demo ./run.sh                       # bot armed for demo (default)
BOT_TRADING_ENVS=demo,production ./run.sh      # opt in to production after testing
BOT_ENABLED=false ./run.sh                     # disable the bot entirely
```

On startup the log states the bot status, e.g. `Trading bot: ARMED for environment 'demo'`.

### Enable AI-driven trade decisions (optional)

With an Anthropic API key present, the bot routes each candidate trade through the Claude
API instead of the analytical model. The deterministic model remains the pre-screen and the
fallback, and all risk limits are still enforced in code after Claude responds.

```bash
export ANTHROPIC_API_KEY="sk-ant-…"
KALSHI_ENV=demo ./run.sh                        # bot uses Claude, falls back to the model
AI_ENABLED=false ./run.sh                       # force the deterministic model only
AI_MODEL=claude-opus-4-8 ./run.sh               # choose the Claude model (default)
```

On startup the log states which decider is active, e.g.
`Trade decider: Claude (claude-opus-4-8), deterministic fallback`.

### Configuration reference

| Variable | Purpose | Default |
|---|---|---|
| `KALSHI_ENV` | `demo` or production (anything else) | production |
| `KALSHI_API_KEY_ID` / `KALSHI_PRIVATE_KEY_PATH` | Kalshi API credentials | unset (polling) |
| `KALSHI_PROD_*` / `KALSHI_DEMO_*` | per-environment creds for `run-both.sh` | unset |
| `PORT` | web UI port | 8080 |
| `BOT_TRADING_ENVS` | comma-separated envs the bot may trade in | `demo` |
| `BOT_ENABLED` | set to `false` to disable the bot | enabled |
| `BOT_MAX_ACCOUNT_EXPOSURE_CENTS` | cap on total open-position value (`0` disables) | `50000` ($500) |
| `BOT_MIN_CASH_RESERVE_CENTS` | never spend account cash below this | `0` |
| `ANTHROPIC_API_KEY` | Anthropic API key; enables the Claude decider | unset (deterministic) |
| `AI_ENABLED` | set to `false` to force the deterministic model | enabled |
| `AI_MODEL` | Claude model id for the decider | `claude-opus-4-8` |
| `AI_TIMEOUT_SECONDS` | per-call Claude API timeout | `4` |
| `AI_MAX_CALLS_PER_DAY` | hard daily Claude call budget (cost kill switch) | `500` |

---

## Disclaimer

This project is for educational and personal use. It is not financial advice and comes with
no warranty. The trading bot can place real orders on whichever environment you enable —
keep it on `demo` unless you fully understand the risks.
