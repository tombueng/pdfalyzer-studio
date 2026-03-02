@echo off
REM Windows packaging script for Pdfalyzer UI
REM Assumes PdfalyzerUi-standalone.jar is built and available
cd /d %~dp0

REM Use Launch4j to wrap jar as exe (requires launch4j installed)
set JAR=PdfalyzerUi-standalone.jar
set EXE=PdfalyzerUi.exe
set ICON=..\assets\app-icon.ico

if not exist %JAR% (
  echo Uber-jar not found. Run build-uber-jar.bat first.
  exit /b 1
)

REM Generate launch4j config
set CFG=launch4j-config.xml
(
  echo ^<launch4jConfig^>
  echo   ^<jar^>%JAR%^</jar^>
  echo   ^<outfile^>%EXE%^</outfile^>
  echo   ^<icon^>%ICON%^</icon^>
  echo   ^<headerType^>gui^</headerType^>
  echo   ^<jre^>
  echo     ^<minVersion^>11^</minVersion^>
  echo   ^</jre^>
  echo ^</launch4jConfig^>
) > %CFG%

REM Run launch4j
launch4j %CFG%
if errorlevel 1 exit /b 1

echo EXE built: %EXE%
