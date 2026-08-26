# ============================================================================
# Docker Desktop 自动安装脚本 (PowerShell)
# 需要管理员权限运行
# ============================================================================

# 检查管理员权限
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "错误：此脚本需要管理员权限运行" -ForegroundColor Red
    Write-Host "请右键点击PowerShell，选择'以管理员身份运行'" -ForegroundColor Yellow
    pause
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Docker Desktop 自动安装脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 步骤 1: 检查是否已安装
Write-Host "[1/5] 检查Docker是否已安装..." -ForegroundColor Yellow
try {
    $dockerVersion = docker --version 2>&1
    Write-Host "Docker已安装：$dockerVersion" -ForegroundColor Green
    Write-Host "无需重复安装" -ForegroundColor Green
    pause
    exit 0
} catch {
    Write-Host "Docker未安装，继续安装流程" -ForegroundColor Gray
}
Write-Host ""

# 步骤 2: 检查winget是否可用
Write-Host "[2/5] 检查winget包管理器..." -ForegroundColor Yellow
try {
    winget --version | Out-Null
    Write-Host "✓ winget可用" -ForegroundColor Green
} catch {
    Write-Host "✗ winget不可用" -ForegroundColor Red
    Write-Host "请手动下载安装：https://www.docker.com/products/docker-desktop/" -ForegroundColor Yellow
    pause
    exit 1
}
Write-Host ""

# 步骤 3: 启用WSL 2（如果尚未启用）
Write-Host "[3/5] 检查并启用WSL 2..." -ForegroundColor Yellow
try {
    $wslVersion = wsl --version 2>&1
    Write-Host "✓ WSL已启用" -ForegroundColor Green
} catch {
    Write-Host "正在启用WSL功能..." -ForegroundColor Gray
    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
    Write-Host "✓ WSL功能已启用（需要重启后生效）" -ForegroundColor Green
}
Write-Host ""

# 步骤 4: 使用winget安装Docker Desktop
Write-Host "[4/5] 安装Docker Desktop..." -ForegroundColor Yellow
Write-Host "这可能需要5-10分钟，请耐心等待..." -ForegroundColor Gray
Write-Host ""

try {
    winget install --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements
    Write-Host ""
    Write-Host "✓ Docker Desktop安装成功" -ForegroundColor Green
} catch {
    Write-Host "✗ 安装失败" -ForegroundColor Red
    Write-Host "请尝试手动安装：https://www.docker.com/products/docker-desktop/" -ForegroundColor Yellow
    pause
    exit 1
}
Write-Host ""

# 步骤 5: 提示重启
Write-Host "[5/5] 安装完成" -ForegroundColor Yellow
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "重要提示" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. 请重启计算机以完成安装" -ForegroundColor Yellow
Write-Host "2. 重启后从开始菜单启动 Docker Desktop" -ForegroundColor Yellow
Write-Host "3. 等待Docker引擎启动（系统托盘鲸鱼图标停止转动）" -ForegroundColor Yellow
Write-Host "4. 验证安装：在PowerShell中运行 'docker --version'" -ForegroundColor Yellow
Write-Host ""
Write-Host "是否立即重启计算机？(Y/N)" -ForegroundColor Yellow
$restart = Read-Host

if ($restart -eq "Y" -or $restart -eq "y") {
    Write-Host "正在重启..." -ForegroundColor Green
    Restart-Computer
} else {
    Write-Host "请稍后手动重启计算机" -ForegroundColor Gray
}
