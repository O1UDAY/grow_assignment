#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
jar="$root/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$jar" ]; then
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' EXIT
    curl -fLsS https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar -o "$tmp"
    sum=$(shasum -a 256 "$tmp" | cut -d ' ' -f 1)
    [ "$sum" = 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17 ]
    cp "$tmp" "$jar"
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$jar" org.gradle.wrapper.GradleWrapperMain "$@"
