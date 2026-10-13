#!/bin/sh
set -e
mkdir -p out
javac -d out src/*.java
echo "build ok"
