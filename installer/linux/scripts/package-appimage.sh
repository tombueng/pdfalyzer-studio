#!/bin/bash
# Linux packaging script for Pdfalyzer UI
set -e
cd "$(dirname "$0")/../.."

JAR="scripts/PdfalyzerUi-standalone.jar"
ICON="assets/app-icon.png"
APPDIR="PdfalyzerUi.AppDir"

# Prepare AppDir structure
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp "$JAR" "$APPDIR/usr/bin/PdfalyzerUi.jar"
cp "$ICON" "$APPDIR/usr/share/icons/hicolor/256x256/apps/PdfalyzerUi.png"

# Create AppRun script
cat > "$APPDIR/AppRun" <<EOF
#!/bin/bash
exec java -jar "$APPDIR/usr/bin/PdfalyzerUi.jar"
EOF
chmod +x "$APPDIR/AppRun"

# Build AppImage (requires appimagetool)
if ! command -v appimagetool &> /dev/null; then
  echo "appimagetool not found. Please install it."
  exit 1
fi
appimagetool "$APPDIR" PdfalyzerUi-x86_64.AppImage

echo "AppImage built: PdfalyzerUi-x86_64.AppImage"
