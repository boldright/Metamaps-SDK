#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
flutter_version_file="$script_dir/flutter-sdk.version"
dart_version_file="$script_dir/dart-sdk.version"

if ! command -v flutter >/dev/null; then
  echo "Required command is unavailable: flutter" >&2
  exit 69
fi

if [[ ! -f "$flutter_version_file" ]]; then
  echo "Flutter SDK version file is missing: $flutter_version_file" >&2
  exit 66
fi

if [[ ! -f "$dart_version_file" ]]; then
  echo "Dart SDK version file is missing: $dart_version_file" >&2
  exit 66
fi

expected_flutter_version="$(tr -d '[:space:]' <"$flutter_version_file")"
expected_dart_version="$(tr -d '[:space:]' <"$dart_version_file")"
version_json="$(flutter --version --machine)"
actual_version="$(printf '%s\n' "$version_json" | sed -n 's/.*"flutterVersion": "\([^"]*\)".*/\1/p')"
dart_version="$(printf '%s\n' "$version_json" | sed -n 's/.*"dartSdkVersion": "\([^"]*\)".*/\1/p')"

if [[ -z "$actual_version" || -z "$dart_version" ]]; then
  echo "Could not read Flutter/Dart versions from: flutter --version --machine" >&2
  exit 65
fi

if [[ "$actual_version" != "$expected_flutter_version" ]]; then
  echo "Flutter SDK mismatch: expected=$expected_flutter_version actual=$actual_version" >&2
  exit 65
fi

if [[ "$dart_version" != "$expected_dart_version" ]]; then
  echo "Dart SDK mismatch: expected=$expected_dart_version actual=$dart_version" >&2
  exit 65
fi

printf 'Flutter SDK %s / Dart %s\n' "$actual_version" "$dart_version"
