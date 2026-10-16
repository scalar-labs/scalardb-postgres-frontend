#!/bin/zsh
# Runs cases.sql on the reference PostgreSQL and on the frontend and compares them with expected.txt.
# Ports: reference at NATIVE_PORT (default 15460), frontend at FEPORT (default 15472).
cd "$(dirname "$0")"
NATIVE_PORT=${NATIVE_PORT:-15460} FEPORT=${FEPORT:-15472} NATIVE=icu STOP=0 \
  EXPECT=expected.txt python3 ../run.py cases.sql
