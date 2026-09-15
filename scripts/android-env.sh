#!/usr/bin/env bash
# Source this file before invoking Gradle from a terminal.
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    elif [[ -x /usr/local/opt/openjdk@17/bin/java ]]; then
        export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    elif [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)"
        export JAVA_HOME
    fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"
