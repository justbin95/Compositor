param(
    [string]$JavaHome = 'C:\Android\jdk21',
    [string]$SdkRoot = 'C:\Android\Sdk',
    [string]$SigningConfig = "$env:USERPROFILE\.android\compositor-signing\signing.json",
    [string[]]$Tasks = @(':app:assembleDebug')
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    $JavaHome = Get-ChildItem -LiteralPath $JavaHome -Directory | Select-Object -First 1 -ExpandProperty FullName
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Configure a complete JDK 21.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$missingSigning = @('COMPOSITOR_DEBUG_KEYSTORE','COMPOSITOR_DEBUG_KEYSTORE_PASSWORD','COMPOSITOR_DEBUG_KEY_ALIAS','COMPOSITOR_DEBUG_KEY_PASSWORD') |
    Where-Object { ![Environment]::GetEnvironmentVariable($_) }
if (!$missingSigning) {
    # Explicit environment configuration takes priority over this machine's fallback key.
} elseif (Test-Path -LiteralPath $SigningConfig) {
    $signing = Get-Content -LiteralPath $SigningConfig -Raw | ConvertFrom-Json
    $env:COMPOSITOR_DEBUG_KEYSTORE = $signing.keystore
    $env:COMPOSITOR_DEBUG_KEYSTORE_PASSWORD = $signing.storePassword
    $env:COMPOSITOR_DEBUG_KEY_ALIAS = $signing.alias
    $env:COMPOSITOR_DEBUG_KEY_PASSWORD = $signing.keyPassword
} else {
    throw 'Provide the persistent signing config or all four COMPOSITOR_DEBUG signing variables.'
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat @Tasks --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
    @('COMPOSITOR_DEBUG_KEYSTORE_PASSWORD','COMPOSITOR_DEBUG_KEY_PASSWORD') | ForEach-Object {
        [Environment]::SetEnvironmentVariable($_, $null, 'Process')
    }
}
