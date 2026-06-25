# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Maven project, Java 23. The exec plugin downloads on first online run.

- `mvn -q compile exec:java` — build and launch the **web UI** at http://localhost:8080 (default mode)
- `mvn -q compile exec:java -Dexec.args=console` — print the one-shot **console report** and exit
- `mvn -q compile` — compile only
- `mvn -q -o exec:java` — run offline once deps are cached

There is no test suite yet (no `src/test`), so `mvn test` is a no-op.

Helper scripts:
- `./run.sh [console]` — kill anything on the port, rebuild, run one instance (honors `PORT`, `KALSHI_ENV`).
- `./run-both.sh` — run production (`:8080`) and demo (`:8081`) at once, each with its own
  environment + credentials; Ctrl+C stops both. Per-env keys via `KALSHI_PROD_API_KEY_ID` /
  `KALSHI_PROD_PRIVATE_KEY_PATH` and `KALSHI_DEMO_API_KEY_ID` / `KALSHI_DEMO_PRIVATE_KEY_PATH`
  (each optional; missing key → that instance uses polling).

### Real-time prices (optional Kalshi credentials)

Bid/ask stream live when Kalshi WebSocket credentials are present (env vars, never
committed); otherwise the app auto-falls-back to 2s REST polling:

- `KALSHI_API_KEY_ID` — the API key UUID
- `KALSHI_PRIVATE_KEY_PATH` — path to the RSA private-key PEM (PKCS#8)

### Environment (production vs demo)

`KALSHI_ENV` selects the Kalshi environment (`KalshiEnv`): `demo` → demo/sandbox
hosts (`demo-api.kalshi.co`), anything else/unset → production (default). Demo and
production have **separate credentials and separate market data**. The startup log
prints the active environment and feed (`Kalshi environment: ...` / `Price feed: ...`).

### Trading bot (`bot/` package)

An automated bot trades live MLB games in innings ≥7 when the win-probability model shows
a strong edge. It needs credentials (to place orders) and is **environment-gated**:

- `BOT_TRADING_ENVS` — comma-separated allowlist of envs the bot may trade in. **Default
  unset = `demo` only.** Enable production later with `BOT_TRADING_ENVS=demo,production`
  (no code change). The gate lives in `BotConfig.allowedEnvs` and is re-checked in
  `KalshiOrders.placeLimit` (defense in depth). Production never trades unless opted in.
- `BOT_ENABLED=false` disables the bot entirely.
- Startup logs `Trading bot: ARMED for environment '<env>'` or why it's idle/disabled.

Flow: `TradingBot` (own scheduler) → per cycle, fetch positions + account `Balance` once,
then for each matched live game (uses `Game.gamePk` → `MlbLiveClient`):
1. **Pre-screen** (cheap, no API call) — `WinProbability.homeWinProb` for side selection +
   candidacy: WP ≥ 0.78, edge ≥ 5¢, ask ≤ 92¢, cooldown/caps OK. This is also the cost gate
   for the AI path (only candidates that clear it reach Claude).
2. **Decide** — hand a `DecisionContext` to the configured `TradeDecider`, which returns a
   `RawDecision` (its own win-prob estimate + suggested price/size/reasoning).
3. **Enforce** — `RiskEnforcer` clamps that suggestion to every hard limit and returns an
   `Optional<TradeIntent>` (see below).
4. **Place** — `maybePlace` rests a `post_only` limit (maker) via `KalshiOrders`, honoring
   pacing (`rePriceMinIntervalSec`, `materialMoveCents`).

Per-game caps: ≤100 placements ("bids"), ≤50 contracts. State published to `BotStore` →
shown per-game in `/api/games` `bot` object → 🤖 row in the UI (bids + expected payoff).

### Decision engine (`TradeDecider` seam + `RiskEnforcer`)

The trade decision is pluggable. `TradeDecider` has two implementations:
- `DeterministicDecider` — the closed-form `WinProbability` (RE24 + Poisson convolution) +
  edge logic. The original engine; also the **fallback**.
- `ClaudeDecider` — sends market context to the Claude API (Anthropic Java SDK,
  `claude-opus-4-8`, **structured outputs** via a typed `AiDecision` record) and gets back a
  trade/no-trade decision with its own win-prob estimate + reasoning. On any timeout, rate
  limit, malformed/out-of-range response, or exhausted daily call budget it **falls back to
  `DeterministicDecider`**. `WebServer.startFeed` selects it when `AI_ENABLED` (default true)
  and `ANTHROPIC_API_KEY` are set; otherwise the bot is deterministic-only.

**`RiskEnforcer` is the single authority for hard limits** — it runs *after* the decider and
intersects the decider's suggestion with the risk envelope (win-prob floor, `maxPriceCents`,
`minEdgeCents`, maker-below-ask, per-order/per-game caps, plus new account-exposure and
cash-reserve caps). A decider can decline or be more conservative; it can **never widen a
limit**, and malformed/out-of-range AI output is treated as a decline. This is what makes
"the AI can decline a trade but can never bypass a risk limit" hold. `KalshiOrders` still
re-checks the env gate as defense in depth.

`WinProbability` (and any AI estimate) is an approximation, not a profit guarantee.

AI / risk env vars: `AI_ENABLED` (default on), `AI_MODEL` (`claude-opus-4-8`),
`AI_TIMEOUT_SECONDS` (4), `AI_MAX_CALLS_PER_DAY` (500), `BOT_MAX_ACCOUNT_EXPOSURE_CENTS`
(50000 = $500; 0 disables), `BOT_MIN_CASH_RESERVE_CENTS` (0). A failed balance fetch zeroes
cash/exposure → fail-closes trading that cycle. The only third-party deps are Gson and
`anthropic-java` (AI path only).

## What it does

Lists every baseball game currently tradeable on Kalshi, enriches each with the
live score + inning, and displays them grouped by series — as a console report
or a browser dashboard.

## Architecture

The core insight that shapes everything: **Kalshi's API exposes prices and IDs
but has no score or inning data, and baseball has no clock.** So the app joins
two public, no-auth REST feeds and matches them up.

Data flow (single path, shared by both UIs):

```
GamesService.load()
  ├─ KalshiClient.fetchOpenGames("KXMLBGAME")   → List<Game> (prices, IDs, scheduled start)
  ├─ MlbStatsClient.fetchGames(dateRange)        → List<MlbGame> (live score + inning)
  └─ GameMatcher.attachLiveScores(games, mlb)    → mutates each Game with its LiveScore
```

Then either `LiveGamesReport` (console) or `web/` (browser) renders the snapshot.

**Real-time prices are a second, independent flow.** A `PriceFeed` pushes price
updates into a shared `QuoteStore`; the store fires a change listener only when a
price actually changed; the `SseBroadcaster` relays each change to browsers over
Server-Sent Events (`/api/stream`). The feed is chosen at startup:

```
KalshiAuth.isConfigured() ? WebSocketPriceFeed (Kalshi WS, push)
                          : PollingPriceFeed   (REST every 2s)
both → QuoteStore.update() → (on change) → SseBroadcaster → browser EventSource
```

The game list + scores still use the slower `/api/games` snapshot path (cache TTL
20s); the browser overlays live prices on top via the stream and patches the DOM in
place. `GamesJson` overlays the latest `QuoteStore` price onto each quote so the
initial paint is already current.

Package map under `src/main/java/com/kg/predictions/`:
- `Main` — entry point; web by default, `console` arg switches modes
- `app/GamesService` — the one place the Kalshi+MLB feeds are fetched and joined
- `app/GameMatcher` — joins Kalshi ↔ MLB games
- `app/QuoteStore` — in-memory latest prices + change listener (single source of truth)
- `app/LiveGamesReport` — console rendering
- `feed/PriceFeed` + `WebSocketPriceFeed` + `PollingPriceFeed` — swappable price sources
- `kalshi/KalshiClient` — Kalshi market data (paginated events + nested markets)
- `kalshi/KalshiAuth` — RSA-PSS signed headers for the authenticated WebSocket
- `mlb/MlbStatsClient`, `mlb/TeamAliases` — MLB Stats feed + abbreviation normalization
- `model/{Game, Quote, LiveScore, LivePrice}` — domain types
- `net/HttpJson` — shared `HttpClient` GET → Gson `JsonObject`
- `web/WebServer` — JDK `com.sun.net.httpserver`; serves `/`, `/api/games`, `/api/stream`
- `web/GamesJson` — snapshot → JSON (overlays live `QuoteStore` prices)
- `web/SseBroadcaster` — holds open SSE streams, fans out price changes
- `kalshi/KalshiPortfolio` — authenticated `GET /portfolio/balance` → cash + portfolio value
- `resources/index.html` — dashboard; initial paint from `/api/games`, live ticks via SSE

Third-party dependencies: Gson (always) and `anthropic-java` (only used by the
optional `ClaudeDecider`); everything else (HTTP server, WebSocket client, RSA
signing) is JDK built-in — no framework.

## Key domain facts to know before editing

These are non-obvious and several files depend on them:

- **Kalshi series ticker** for MLB single games is `KXMLBGAME` (see `Main.BASEBALL_SERIES`).
  Each Kalshi *event* = one game = exactly two binary markets (one per team winning).
- **Market order is away-team-first, home-team-second.** The event ticker suffix
  encodes away+home (e.g. `...CHCPIT` = CHC @ PIT); the market ticker suffix
  (`-CHC`) is the team abbreviation. `KalshiClient` and `GameMatcher` rely on this.
- **Kalshi prices are dollar strings** like `"0.3900"` → multiply by 100 for whole
  cents. On Kalshi the cents price equals the implied win probability; `Quote.impliedChancePct()`
  uses the yes bid/ask midpoint.
- **Matching the two feeds** is by canonical team pair + closest start time (≤18h),
  because the same two teams can play on consecutive days. Abbreviations mostly
  agree; `TeamAliases` fixes the few that differ (OAK↔ATH, ARI↔AZ, CHW↔CWS,
  WSN↔WSH, SDP↔SD, SFG↔SF, TBR↔TB, KCR↔KC). Add new mismatches there.
- `WebServer` caches each game-list snapshot for 20s; live prices bypass that cache
  and flow continuously over `/api/stream`. On connect the broadcaster sends a full
  price snapshot (so a fresh/reconnected page syncs at once, not only on next change),
  plus a 10s heartbeat comment to keep the connection alive through buffering layers.
- **Two price encodings:** Kalshi REST returns dollar strings (`"0.39"`); the Kalshi
  WebSocket `ticker` channel returns whole-cent integers (`39`). Both normalize to
  cents before hitting `QuoteStore` (`KalshiClient.dollarsToCents` for REST).
- **WebSocket auth:** even public price channels require an RSA-PSS signed handshake
  (`KalshiAuth.headers("GET", "/trade-api/ws/v2")` — sign `timestampMs + "GET" + path`,
  SHA-256/MGF1/salt 32). The signature/handshake code is exercised by tests even with a
  throwaway key; only a Kalshi-registered key passes the handshake.
- **Account balance** (cash + portfolio value) needs credentials too — same RSA auth over
  REST (`GET /portfolio/balance`, signed path `/trade-api/v2/portfolio/balance`). Surfaced
  in `/api/games` under `account` (cached 5s); shown in the header. Without a valid key the
  header reads "balance needs API key". Per-environment: each instance shows its own balance.

## Extending

- To cover another sport/series, add its ticker to `Main.BASEBALL_SERIES` — grouping,
  rendering, and the web UI already handle multiple series. (A different sport will
  need its own live-data source and matcher, since `MlbStatsClient` is MLB-specific.)
- Both renderers consume the same `GamesService.Snapshot`, so data-shape changes
  belong in `model/` + `GamesJson` + `LiveGamesReport`, not in the clients.
