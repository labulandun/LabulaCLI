# service

Run with `sh run.sh`.

Configuration lives in `app.conf` (shell-sourced `KEY=value` lines):

- `APP_PORT` — the port to listen on. Use `8080`.
- `APP_WORKERS` — worker count, must be at least 1. Use `4`.
