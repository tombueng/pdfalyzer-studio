
# Build and Packaging Instructions

## Windows (64-bit)
1. Run `scripts\build-uber-jar.bat` to build the standalone jar.
2. Run `scripts\package-exe.bat` to wrap the jar as an EXE (requires Launch4j).
3. Use WiX Toolset to build the installer from `PdfalyzerInstaller.wxs`.
4. (Optional) Sign the EXE and installer if you have a certificate.

## Linux
1. Run `scripts/build-uber-jar.sh` to build the standalone jar.
2. Run `scripts/package-appimage.sh` to create an AppImage (requires appimagetool).
3. (Optional) Add .deb/.rpm packaging scripts as needed.

## Maven Shade Plugin
Add the following to your pom.xml to enable building an uber-jar:

```xml
<build>
	<plugins>
		<plugin>
			<groupId>org.apache.maven.plugins</groupId>
			<artifactId>maven-shade-plugin</artifactId>
			<version>3.4.1</version>
			<executions>
				<execution>
					<phase>package</phase>
					<goals>
						<goal>shade</goal>
					</goals>
					<configuration>
						<transformers>
							<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
								<mainClass>io.pdfalyzer.PdfalyzerUiApplication</mainClass>
							</transformer>
						</transformers>
					</configuration>
				</execution>
			</executions>
		</plugin>
	</plugins>
</build>
```

Or see `installer/maven-shade-plugin-snippet.xml` for a copy-pasteable snippet.

## Chromium
- Bundle Chromium binaries in the installer as needed (add instructions/files to assets/).

## Icons
- Place your app icon in `assets/app-icon.ico` (Windows) and `assets/app-icon.png` (Linux).

## Signing
- If you have a code signing certificate, use it to sign the EXE and installer for Windows.

---

For details, see the README.md files in each platform directory.
