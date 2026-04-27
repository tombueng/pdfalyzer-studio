@echo off
REM ============================================================================
REM  PDFalyzer Studio - Windows Installer Builder
REM  Uses components from bundle\ (downloaded by update-components.ps1)
REM  Produces: PdfalyzerStudioInstaller.msi
REM ============================================================================
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "BUNDLE_DIR=%SCRIPT_DIR%bundle"
set "LAUNCH4J=%BUNDLE_DIR%\launch4j\launch4jc.exe"
set "CANDLE=%BUNDLE_DIR%\wix\candle.exe"
set "LIGHT=%BUNDLE_DIR%\wix\light.exe"
set "OUTPUT_DIR=%SCRIPT_DIR%output"

REM ── Preflight checks ────────────────────────────────────────────────────────
echo.
echo  PDFalyzer Studio - Installer Builder
echo  =================================
echo.

if not exist "%BUNDLE_DIR%\app\pdfalyzer-studio.jar" (
    echo ERROR: App JAR not found. Run update-components.ps1 first.
    exit /b 1
)
if not exist "%BUNDLE_DIR%\jre\bin\java.exe" (
    echo ERROR: JRE not found in bundle\jre. Run update-components.ps1 first.
    exit /b 1
)
if not exist "%BUNDLE_DIR%\chromium\chrome.exe" (
    echo ERROR: Chromium not found in bundle\chromium. Run update-components.ps1 first.
    exit /b 1
)
if not exist "%LAUNCH4J%" (
    echo ERROR: Launch4j not found. Run update-components.ps1 first.
    exit /b 1
)
if not exist "%CANDLE%" (
    echo ERROR: WiX candle.exe not found. Run update-components.ps1 first.
    exit /b 1
)

echo All components found. Building installer...

REM ── Create output directory ─────────────────────────────────────────────────
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM ── Step 1: Create Launch4j EXE wrapper ─────────────────────────────────────
echo.
echo [1/3] Creating EXE wrapper with Launch4j...

set "L4J_CONFIG=%OUTPUT_DIR%\launch4j-config.xml"
set "APP_EXE=%OUTPUT_DIR%\PdfalyzerStudio.exe"

(
  echo ^<?xml version="1.0" encoding="UTF-8"?^>
  echo ^<launch4jConfig^>
  echo   ^<dontWrapJar^>true^</dontWrapJar^>
  echo   ^<headerType^>gui^</headerType^>
  echo   ^<jar^>app\pdfalyzer-studio.jar^</jar^>
  echo   ^<outfile^>%APP_EXE%^</outfile^>
  echo   ^<errTitle^>PDFalyzer Studio^</errTitle^>
  echo   ^<cmdLine^>--server.port=8080^</cmdLine^>
  echo   ^<chdir^>^</chdir^>
  echo   ^<priority^>normal^</priority^>
  echo   ^<stayAlive^>true^</stayAlive^>
  echo   ^<restartOnCrash^>false^</restartOnCrash^>
  echo   ^<icon^>%SCRIPT_DIR%assets\app-icon.ico^</icon^>
  echo   ^<jre^>
  echo     ^<path^>jre^</path^>
  echo     ^<requiresJdk^>false^</requiresJdk^>
  echo     ^<requires64Bit^>true^</requires64Bit^>
  echo     ^<minVersion^>21^</minVersion^>
  echo     ^<initialHeapSize^>256^</initialHeapSize^>
  echo     ^<maxHeapSize^>1024^</maxHeapSize^>
  echo   ^</jre^>
  echo ^</launch4jConfig^>
) > "%L4J_CONFIG%"

"%LAUNCH4J%" "%L4J_CONFIG%"
if errorlevel 1 (
    echo ERROR: Launch4j failed.
    exit /b 1
)
echo   EXE created: %APP_EXE%

REM ── Step 2: Compile WiX installer ──────────────────────────────────────────
echo.
echo [2/3] Compiling WiX installer...

pushd "%SCRIPT_DIR%"

REM Harvest the JRE directory into a WiX fragment
echo   Harvesting JRE directory...
"%BUNDLE_DIR%\wix\heat.exe" dir "%BUNDLE_DIR%\jre" -nologo -cg JreComponents -dr JreDir -gg -sfrag -srd -sreg -var var.JreSource -out "%OUTPUT_DIR%\jre-fragment.wxs"
if errorlevel 1 (
    echo ERROR: heat.exe failed for JRE.
    popd
    exit /b 1
)

REM Harvest the Chromium directory into a WiX fragment
echo   Harvesting Chromium directory...
"%BUNDLE_DIR%\wix\heat.exe" dir "%BUNDLE_DIR%\chromium" -nologo -cg ChromiumComponents -dr ChromiumDir -gg -sfrag -srd -sreg -var var.ChromiumSource -out "%OUTPUT_DIR%\chromium-fragment.wxs"
if errorlevel 1 (
    echo ERROR: heat.exe failed for Chromium.
    popd
    exit /b 1
)

REM Compile all WiX sources
echo   Compiling WiX sources...
"%CANDLE%" -nologo -arch x64 ^
    -dJreSource="%BUNDLE_DIR%\jre" ^
    -dChromiumSource="%BUNDLE_DIR%\chromium" ^
    -dAppJar="%BUNDLE_DIR%\app\pdfalyzer-studio.jar" ^
    -dAppExe="%APP_EXE%" ^
    -dAppIcon="%SCRIPT_DIR%assets\app-icon.ico" ^
    -dLauncherVbs="%BUNDLE_DIR%\pdfalyzer.vbs" ^
    -dLauncherBat="%BUNDLE_DIR%\pdfalyzer.bat" ^
    -out "%OUTPUT_DIR%\\" ^
    PdfalyzerInstaller.wxs "%OUTPUT_DIR%\jre-fragment.wxs" "%OUTPUT_DIR%\chromium-fragment.wxs"
if errorlevel 1 (
    echo ERROR: candle.exe failed.
    popd
    exit /b 1
)

REM ── Step 3: Link into MSI ──────────────────────────────────────────────────
echo.
echo [3/3] Linking MSI installer...

"%LIGHT%" -nologo -ext WixUIExtension -ext WixUtilExtension ^
    -out "%OUTPUT_DIR%\PdfalyzerStudioInstaller.msi" ^
    "%OUTPUT_DIR%\PdfalyzerInstaller.wixobj" "%OUTPUT_DIR%\jre-fragment.wixobj" "%OUTPUT_DIR%\chromium-fragment.wixobj"
if errorlevel 1 (
    echo ERROR: light.exe failed.
    popd
    exit /b 1
)

popd

echo.
echo ============================================================================
echo  SUCCESS: %OUTPUT_DIR%\PdfalyzerStudioInstaller.msi
echo ============================================================================
echo.
