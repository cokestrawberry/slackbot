# Slack Bot (Go) — Phase 1

Thin proxy that receives Slack Events API callbacks and forwards them to the
Spring Boot server. The bot does **not** validate the Slack HMAC; it passes
the raw body and `X-Slack-Signature` / `X-Slack-Request-Timestamp` headers
through so Spring Boot's `SlackSignatureFilter` can validate them.

**Go 1.25+ required** (transitive requirement from `github.com/slack-go/slack v0.22.0`).

## Endpoints

| Method | Path             | Purpose                                          |
|--------|------------------|--------------------------------------------------|
| POST   | `/slack/events`  | Slack Events API entry point (URL verify + forward) |
| GET    | `/health`        | Liveness probe                                   |

- `url_verification` envelopes are answered locally with the challenge.
- Everything else (`event_callback`, etc.) is forwarded verbatim to
  `SPRING_EVENT_URL` (default `http://localhost:8080/api/slack/event`).

## Environment variables

Loaded from the repo-root `.env` if present (`source ../.env` from inside
`bot/`), else from the shell environment.

| Variable                | Required | Default                                    | Notes |
|-------------------------|----------|--------------------------------------------|-------|
| `SLACK_SIGNING_SECRET`  | yes      | —                                          | Validated at startup; unused at runtime (Spring does HMAC) |
| `SLACK_BOT_TOKEN`       | no       | —                                          | Reserved for future outbound Slack calls |
| `PORT`                  | no       | `3000`                                     | Bot HTTP port |
| `SPRING_EVENT_URL`      | no       | `http://localhost:8080/api/slack/event`    | Spring Boot endpoint |

## Run locally

```bash
cd bot
go mod download
go run .
```

Expose to Slack via ngrok:

```bash
ngrok http 3000
# → copy the https URL, e.g. https://abc123.ngrok-free.app
```

In the Slack App dashboard → **Event Subscriptions**:

- **Request URL**: `https://<ngrok-host>/slack/events`
- **Subscribe to bot events**: `message.channels`, `message.im`, `app_mention`
- Slack will send a `url_verification` probe → the bot echoes the challenge.

## Required Slack App config

- **OAuth scopes (Bot Token Scopes)**: `chat:write`, `channels:history`,
  `im:history`, `app_mentions:read`
- **Event Subscriptions** (see above)
- Install the app to your workspace to get the `xoxb-` bot token.

## Forwarded payload shape

The bot forwards the **raw Slack body** unchanged. Spring Boot receives a
standard Slack Events API envelope:

```json
{
  "token": "...",
  "team_id": "T...",
  "api_app_id": "A...",
  "event": {
    "type": "message",
    "user": "U...",
    "text": "로그인 페이지에서 500 에러 남",
    "channel": "C...",
    "ts": "1712345678.000100"
  },
  "type": "event_callback",
  "event_id": "Ev...",
  "event_time": 1712345678
}
```

Forwarded headers (only these; hop-by-hop headers like `Connection`, `Host`
are stripped):

- `Content-Type`
- `X-Slack-Signature`
- `X-Slack-Request-Timestamp`
- `X-Slack-Retry-Num` (when Slack retries)
- `X-Slack-Retry-Reason`

## Reliability

- 5-second HTTP timeout on outbound calls to Spring Boot.
- One retry with 250 ms backoff on connection errors or 5xx.
- 4xx from Spring Boot is treated as permanent (bot returns 502, does **not**
  retry — Slack itself will retry per its own schedule).
- SIGINT/SIGTERM triggers `http.Server.Shutdown` with a 10 s drain timeout.

## Deployment note: do not expose the bot port directly

The bot does NOT verify Slack's HMAC; it relies on Spring to do so. If port
`3000` is exposed to the public internet without ngrok (or another tunnel
that fronts the bot), an attacker can call `/slack/events` with arbitrary
payloads. The intended topology is **Slack → ngrok → bot:3000 → spring:8080**;
the bot port must remain behind the tunnel or behind a firewall.

## Tests

```bash
go test ./...
```

Covers: header/body passthrough, 5xx retry, 4xx no-retry, connection-error
retry, URL verification echo, event_callback forwarding.
