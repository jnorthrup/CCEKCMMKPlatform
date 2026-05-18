#!/bin/sh

set -eu

APP_HOME=$(
  cd "${0%/*}" >/dev/null 2>&1
  pwd
)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  echo "Missing Gradle wrapper jar at $CLASSPATH" >&2
  exit 1
fi

exec java ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
  -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"

