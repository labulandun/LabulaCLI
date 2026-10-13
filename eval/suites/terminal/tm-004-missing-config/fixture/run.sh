#!/bin/sh
# Loads app.conf and prints the resolved settings.
set -e

if [ ! -f app.conf ]; then
    echo "ERROR: app.conf not found" >&2
    exit 2
fi

. ./app.conf

if [ -z "$APP_PORT" ]; then
    echo "ERROR: APP_PORT not set in app.conf" >&2
    exit 3
fi
if [ -z "$APP_WORKERS" ]; then
    echo "ERROR: APP_WORKERS not set in app.conf" >&2
    exit 4
fi
if [ "$APP_WORKERS" -lt 1 ] 2>/dev/null; then
    echo "ERROR: APP_WORKERS must be >= 1" >&2
    exit 5
fi

echo "listening on $APP_PORT with $APP_WORKERS workers"
