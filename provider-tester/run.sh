#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/provider-tester.jar"
if [ ! -f "$JAR" ]; then
    echo "Building..."
    (cd "$SCRIPT_DIR" && ./gradlew build --no-daemon -q 2>/dev/null)
fi
java -cp "$JAR" tester.MainKt "$@"
