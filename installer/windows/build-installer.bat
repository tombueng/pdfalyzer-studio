@echo off
REM Main Windows installer build script for Pdfalyzer UI
REM This script builds the uber-jar, wraps it as an EXE, signs it (if cert available), and builds the installer with WiX.

REM Step 1: Build uber-jar with Maven Shade Plugin


REM Change to the project root where pom.xml is located
pushd "%~dp0..\.."
if not exist pom.xml (
  echo pom.xml not found in %CD%. Exiting.
  exit /b 1
)

REM Clean and build regular jar
call mvn clean package -DskipTests
if errorlevel 1 popd & exit /b 1


REM Run Maven Assembly Plugin directly to create uber-jar (without pom.xml modification)
call mvn org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single -DskipTests -Ddescriptor=installer/windows/standalone-assembly.xml -DarchiveBaseName=pdfalyzer-ui-standalone -DfinalName=pdfalyzer-ui-standalone
if errorlevel 1 popd & exit /b 1

REM Step 2: Find the uber-jar (from project root)

REM Step 2: Check for the standalone uber-jar
set JAR=target\pdfalyzer-ui-standalone.jar
if not exist %JAR% (
  echo Uber-jar not found at %JAR%. Ensure the shade plugin command succeeded.
  popd
  exit /b 1
)

REM Step 3: Copy jar to installer/windows directory (absolute path)
copy "%JAR%" "%~dp0PdfalyzerUi-standalone.jar"

popd

REM Step 2: Find the uber-jar
setlocal enabledelayedexpansion
set JAR=
for /f "delims=" %%j in ('dir /b /s target\*-jar-with-dependencies.jar') do set JAR=%%j
if not defined JAR (
  echo Uber-jar not found. Ensure maven-shade-plugin is configured.
  exit /b 1
)

REM Step 3: Copy jar to installer directory
copy "%JAR%" installer\windows\PdfalyzerUi-standalone.jar


REM Step 4: Wrap jar as EXE using Launch4j
pushd "%~dp0"
call scripts\package-exe.bat
if errorlevel 1 popd & exit /b 1
popd

REM Step 5: (Optional) Sign the EXE if certificate is available
REM call scripts\sign-exe.bat PdfalyzerUi.exe


REM Step 6: Build installer with WiX
REM Assumes candle.exe and light.exe are in PATH
pushd "%~dp0"
candle PdfalyzerInstaller.wxs
if errorlevel 1 popd & exit /b 1
light -ext WixUIExtension PdfalyzerInstaller.wixobj -o PdfalyzerUiInstaller.msi
if errorlevel 1 popd & exit /b 1
popd

REM Step 7: (Optional) Sign the MSI if certificate is available
REM call scripts\sign-exe.bat PdfalyzerUiInstaller.msi

echo Installer build complete: installer\windows\PdfalyzerUiInstaller.msi
