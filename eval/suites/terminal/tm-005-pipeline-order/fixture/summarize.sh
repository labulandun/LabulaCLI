#!/bin/sh
# Summarises data/raw.txt into data/summary.txt (sorted unique values + a count line).
set -e
if [ ! -f data/raw.txt ]; then
    echo "ERROR: data/raw.txt missing, run the generate step first" >&2
    exit 2
fi
sort -u data/raw.txt | grep -v '^$' > data/summary.txt
echo "COUNT $(wc -l < data/summary.txt | tr -d ' ')" >> data/summary.txt
echo "summarized"
