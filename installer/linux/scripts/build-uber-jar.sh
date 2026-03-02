#!/bin/bash
# Build uber-jar for Pdfalyzer UI
set -e
cd "$(dirname "$0")/../../.."

# Clean and package with dependencies
mvn clean package -DskipTests

# Find the uber-jar (assuming maven-shade-plugin is used)
JAR=$(find target -name '*-jar-with-dependencies.jar' | head -n 1)
if [ -z "$JAR" ]; then
  echo "Uber-jar not found. Ensure maven-shade-plugin is configured."
  exit 1
fi

# Copy to installer/linux/scripts for packaging
cp "$JAR" installer/linux/scripts/PdfalyzerUi-standalone.jar

echo "Uber-jar built and copied to installer/linux/scripts/PdfalyzerUi-standalone.jar"
