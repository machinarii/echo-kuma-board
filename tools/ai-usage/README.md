# Claude and Codex weekly usage on Kuma Board

The board's bottom strip shows weekly plan usage, plus the separate weekly windows for frontier models, for example
`Claude ▬▬▭ 71% left  Fable ▬▭ 56%  resets Sun 9 PM    Codex ▬▭▭ 22% left  Astra ▭ 4%  resets Thu 9 PM`.
The numbers are what is **left** of each weekly window (100 − used).
When a tool and its model windows reset at the same time, the reset text is shown once, at the end of the group; otherwise each shows its own.

## Security model

- **No credentials anywhere.** Nothing here reads, stores or sends a Claude or Codex login token. The numbers come from what the tools already write locally:
  - Claude Code passes `rate_limits.seven_day.used_percentage` and `resets_at` to its status line command (documented). `kuma-usage tap` saves those two numbers, then runs your normal status line.
  - Claude's per-model weekly windows (for example "Fable") never reach the status line. Claude Code reports them only through its `get_usage` SDK request, which needs a claude.ai login. `kuma-usage login` (run once by you) signs a **dedicated** Claude Code config folder in (`~/.local/state/kuma-board-usage/claude-login`, mode 0700). The login token stays with Claude Code in Keychain, and this script never reads it. Every 10 minutes `collect` starts Claude Code with that folder and sends only `get_usage` (no prompt, so no quota is used). Hooks are off, there are no MCP servers, no session is saved, and the environment holds no inherited tokens or API keys. Only the percentages, reset times and model names are kept. The rest of the output, including account details, is discarded and never logged. The same call also gives the all-models week, so with the login the status line tap is optional.
  - Codex writes `rate_limits` into its session logs (`~/.codex/sessions`). `kuma-usage collect` reads only weekly entries (`window_minutes: 10080`), one per limit bucket: `codex` is the plan-wide one, and others become model windows (`premium` is shown as **Astra**, the gpt-6-astra window) once Codex reports a number for them. The logs also contain prompts, and nothing else is read from them.
- **Numbers only leave the Mac.** `usage.json` is built from an allowlist: percent, reset time and "as of" time per tool.
- **Tailnet only.** A localhost server (`127.0.0.1:31995`) answers exactly `GET /usage.json` (404 for anything else, no directory listing). `tailscale serve` proxies `https://your-mac.your-tailnet.ts.net/kuma-usage/usage.json` to it, on the tailnet only, never Funnel.
- **The board trusts nothing in the file.** It accepts https, or http only to a private host, with no credentials, query or fragment in the URL. It reads at most 16 KB and parses strictly: invalid values show as `—`, never as a number. Model names must be 1–16 letters, digits, spaces or `. + -`; other entries, and anything beyond 4 model windows, are dropped.

## Install (on the Mac that runs Claude Code / Codex)

```sh
tools/ai-usage/install.sh          # collector every 2 min + localhost server + tailscale serve path
tools/ai-usage/install.sh --uninstall
```

For Claude's numbers, including frontier models, sign the dedicated folder in once, in your own Terminal. Use the claude.ai account whose plan you want shown:

```sh
/usr/bin/python3 -I ~/.local/bin/kuma-usage login     # runs `claude auth login` for that folder only
/usr/bin/python3 -I ~/.local/bin/kuma-usage collect --now
```

Undo: `CLAUDE_CONFIG_DIR=~/.local/state/kuma-board-usage/claude-login claude auth logout`, then delete that folder.

Optional, without the login (all-models week only): switch the Claude Code status line in `~/.claude/settings.json` so it runs through the tap:

```json
"statusLine": { "type": "command", "command": "/usr/bin/python3 -I ~/.local/bin/kuma-usage tap -- npx -y ccstatusline@latest", "padding": 0 }
```

## Point the board at it

```sh
adb shell am broadcast -n dev.kumaboard/.ProvisionReceiver -a dev.kumaboard.PROVISION \
  --es usageUrl https://your-mac.your-tailnet.ts.net/kuma-usage/usage.json
```

`--es usageUrl ""` hides the strip again. You can also use `"usageUrl"` in `kuma-board.json`.

## What the strip shows

- A meter and the percentage left per tool. The meter shrinks as the week is used. Its colour blends through the palette: red (0% left), peach (25%), yellow (50%), green (100%). The number turns yellow at ≤ 20% left and red at ≤ 5%. The number is always shown, so colour is never the only signal. "left" is written once per group, on the tool.
- `resets Fri 7 PM` in the device's time zone.
- Grey `as of …` when the number is more than 24 h old. Numbers only update while that tool is in use on the Mac.
- Grey `—` with `window reset` once the reset time has passed and no new number has arrived.
- `—` when there's no data.
- Model windows follow their tool, slightly closer together (law of proximity). When the strip runs out of room, reset texts are dropped before any number is.
- The board refetches the file every 5 minutes, and after 1 minute if a fetch fails. A failed fetch never affects the Kuma tiles.

## Known limits

- `get_usage` is marked experimental by Claude Code, so its shape may change in an update. If it breaks, the Claude model windows go grey (`as of …`) after 24 h and later disappear. The tap and Codex keep working.
- The login is a full claude.ai login on the Mac, in its own config folder. Anyone running code as your user could use it through Claude Code, just like your normal Claude Code login.

- A Mac may fail to fetch its own `*.ts.net` address (TLS handshake). Test from another tailnet device, or locally with `curl http://127.0.0.1:31995/usage.json`.
- The Mac's port 443 is reachable by every device on your tailnet that the ACL already allows. To narrow it, add a Tailscale grant that allows only the Echo to reach it.
