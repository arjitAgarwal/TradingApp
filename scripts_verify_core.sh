#!/usr/bin/env bash
# Compiles and runs :core unit tests WITHOUT network, using the Kotlin compiler + JUnit bundled in the Gradle install.
# Normal route is `./gradlew :core:test`. Use this only where Maven/Gradle plugin repos are unreachable.
set -euo pipefail
G=${GRADLE_HOME:-/opt/gradle}/lib
OUT=${TMPDIR:-/tmp}/papertrade-core-build; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
KC="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar"
KT() { java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }
STD="$G/kotlin-stdlib-2.0.21.jar"; JU="$G/junit-4.13.2.jar:$G/hamcrest-core-1.3.jar"
KT -cp "$STD" -d "$OUT/main" core/src/main/kotlin
KT -cp "$STD:$JU:$OUT/main" -d "$OUT/test" core/src/test/kotlin
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$STD:$JU:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES
