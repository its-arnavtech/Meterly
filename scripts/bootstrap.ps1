$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location $taskRoot
if (Test-Path -LiteralPath '.env') {
    Write-Output 'Existing .env preserved. Start with docker compose up --build -d.'
    exit 0
}
function New-Secret { [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
New-Item -ItemType Directory -Force -Path 'secrets' | Out-Null
if ((Test-Path 'secrets/jwt-private.pem') -or (Test-Path 'secrets/jwt-public.pem')) {
    throw 'Existing key files found without .env. Restore configuration or move keys aside before bootstrapping.'
}
$key = [System.Security.Cryptography.RSA]::Create(3072)
try {
    [System.IO.File]::WriteAllText((Join-Path $taskRoot 'secrets/jwt-private.pem'), $key.ExportPkcs8PrivateKeyPem())
    [System.IO.File]::WriteAllText((Join-Path $taskRoot 'secrets/jwt-public.pem'), $key.ExportSubjectPublicKeyInfoPem())
} finally { $key.Dispose() }
$configuration = @(
    'COMPOSE_PROJECT_NAME=meterly-completion',
    "POSTGRES_PASSWORD=$(New-Secret)",
    "DB_OWNER_PASSWORD=$(New-Secret)",
    "DB_APP_PASSWORD=$(New-Secret)",
    "INTERNAL_TOKEN=$(New-Secret)",
    'WEB_PORT=8080', 'POSTGRES_PORT=55432', 'REDIS_PORT=16379', 'KAFKA_PORT=19092'
)
[System.IO.File]::WriteAllLines((Join-Path $taskRoot '.env'), $configuration)
Write-Output 'Created ignored local .env and RSA keys. Start with docker compose up --build -d.'
