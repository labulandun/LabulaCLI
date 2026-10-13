#!/bin/sh
# Compiles the project into ./out
set -e
mkdir -p out
javac -d build src/*.java
echo "build ok"
