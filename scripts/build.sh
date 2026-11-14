#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x ./gradlew && -f gradle/wrapper/gradle-wrapper.jar ]]; then
    ./gradlew --no-daemon :app:assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon :app:assembleDebug
else
    printf '%s\n' 'Gradle 8.7 not found. Open this project in Android Studio or install Gradle 8.7.' >&2
    exit 1
fi
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
