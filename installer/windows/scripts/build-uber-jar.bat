@echo off
REM Build uber-jar for Pdfalyzer UI (Windows)
cd /d %~dp0\..\..\..

REM Clean and package with dependencies
mvn clean package -DskipTests

REM Find the uber-jar (assuming maven-shade-plugin is used)
for /f "delims=" %%j in ('dir /b /s target\*-jar-with-dependencies.jar') do set JAR=%%j
if not defined JAR (
  echo Uber-jar not found. Ensure maven-shade-plugin is configured.
  exit /b 1
)

REM Copy to installer\windows\scripts for packaging
copy "%JAR%" installer\windows\scripts\PdfalyzerStudio-standalone.jar

echo Uber-jar built and copied to installer\windows\scripts\PdfalyzerStudio-standalone.jar
