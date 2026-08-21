#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.5.0"
GRADLE_SHA256="553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CACHE_DIR="$SCRIPT_DIR/.gradle-bootstrap"
DIST_DIR="$CACHE_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"

if command -v gradle >/dev/null 2>&1; then
  INSTALLED_VERSION=$(gradle --version 2>/dev/null | awk '/^Gradle / { print $2; exit }')
  if [ "$INSTALLED_VERSION" = "$GRADLE_VERSION" ]; then
    exec gradle "$@"
  fi
  echo "Ignoring system Gradle ${INSTALLED_VERSION:-unknown}; Camera requires Gradle $GRADLE_VERSION."
fi

mkdir -p "$CACHE_DIR"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  if [ ! -f "$ZIP_FILE" ]; then
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Downloading Gradle $GRADLE_VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$URL"
    else
      echo "Error: install curl or wget, or install Gradle $GRADLE_VERSION." >&2
      exit 1
    fi
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP_FILE" | awk '{print $1}')
    if [ "$ACTUAL" != "$GRADLE_SHA256" ]; then
      echo "Gradle ZIP checksum mismatch." >&2
      rm -f "$ZIP_FILE"
      exit 1
    fi
  fi

  command -v unzip >/dev/null 2>&1 || { echo "Error: unzip is required." >&2; exit 1; }
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP_FILE" -d "$CACHE_DIR"
fi

exec "$DIST_DIR/bin/gradle" "$@"
