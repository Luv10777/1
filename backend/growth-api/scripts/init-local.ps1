$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $backendRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    Write-Output '.env 已存在，保留原配置。需要时请手动补充 JWT_SECRET。'
    exit 0
}
$bytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($bytes)
} finally {
    $rng.Dispose()
}
$secret = [Convert]::ToBase64String($bytes)
$template = [IO.File]::ReadAllText((Join-Path $backendRoot '.env.example'))
$content = [regex]::Replace($template, '(?m)^JWT_SECRET=.*$', ('JWT_SECRET=' + $secret))
[IO.File]::WriteAllText($envFile, $content, (New-Object Text.UTF8Encoding($false)))
Write-Output '已创建本地 .env 并生成随机 JWT 密钥。不要提交或共享此文件。'
