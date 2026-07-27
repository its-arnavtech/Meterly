param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$target = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"

if ((Test-Path -LiteralPath $target) -and -not $Force) {
    throw ".env already exists. Re-run with -Force only when you intend to rotate local secrets."
}

function New-RandomSecret([int]$byteCount = 32) {
    $bytes = [byte[]]::new($byteCount)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
}

$content = @(
    "POSTGRES_DB=meterly"
    "POSTGRES_OWNER_USER=meterly_owner"
    "POSTGRES_OWNER_PASSWORD=$(New-RandomSecret)"
    "POSTGRES_APP_USER=meterly_app"
    "POSTGRES_APP_PASSWORD=$(New-RandomSecret)"
    "JWT_SECRET=$(New-RandomSecret 48)"
    "INTERNAL_SERVICE_KEY=$(New-RandomSecret)"
    "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=$(New-RandomSecret)"
    "KEYCLOAK_ADMIN_CLIENT_SECRET=$(New-RandomSecret)"
) -join [Environment]::NewLine

[System.IO.File]::WriteAllText($target, $content + [Environment]::NewLine)
Write-Output "Created ignored local configuration at .env; secret values were not printed."
