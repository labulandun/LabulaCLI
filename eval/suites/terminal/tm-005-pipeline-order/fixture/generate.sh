#!/bin/sh
# Produces data/raw.txt from the seed list.
set -e
mkdir -p data
tr ',' '\n' < seed.csv > data/raw.txt
echo "generated data/raw.txt"
