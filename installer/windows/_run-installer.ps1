param([switch]$Uninstall, [switch]$Silent)
$msi = Join-Path $PSScriptRoot 'output\PdfalyzerUiInstaller.msi'
$log = Join-Path $PSScriptRoot 'output\install-log.txt'
if (-not (Test-Path $msi)) { Write-Host "MSI not found"; exit 1 }
$a = if ($Uninstall) { "/x" } else { "/i" }
$a += " `"$msi`" /l*v `"$log`""
if ($Silent) { $a += " /qn" }
$proc = Start-Process msiexec.exe -ArgumentList $a -Wait -PassThru -Verb RunAs
Write-Host "Exit: $($proc.ExitCode)"
