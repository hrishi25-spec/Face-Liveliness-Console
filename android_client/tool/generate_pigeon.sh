#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

mkdir -p lib/pigeon android/app/src/main/java/io/mosip/registration/liveness/pigeon

dart run pigeon \
  --input pigeon/liveness.dart \
  --dart_out lib/pigeon/liveness.dart \
  --java_out android/app/src/main/java/io/mosip/registration/liveness/pigeon/Liveness.java \
  --java_package io.mosip.registration.liveness.pigeon

# Pigeon 10.0.1 emits Dart that `dart format` would still rewrite (trailing
# whitespace on codec cases, argument-list wrapping). Format the fresh output
# here so the checked-in binding, a regeneration, and the CI `dart format` gate
# all agree on the same bytes; the Java output is untouched (javac has no
# formatter, and CI compares it byte-for-byte right after this script runs).
dart format lib/pigeon/liveness.dart
