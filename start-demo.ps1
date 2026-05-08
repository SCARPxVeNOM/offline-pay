# OfflinePay one-click demo launcher (Windows / PowerShell).
#
# Starts a fresh local Polygon node, deploys the contracts, starts the
# backend, opens the dashboard in your browser, and prints a step-by-step
# log of what just happened. Use this for any live demo.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSCommandPath

Write-Host "OfflinePay demo launcher" -ForegroundColor Cyan
Write-Host "---------------------------"

# 1. Kill any stale processes from a previous run.
Get-Process -Name node -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep 1

# 2. Start a fresh hardhat node in the background.
Write-Host "[1/4] starting local chain..." -ForegroundColor Yellow
Set-Location "$root\contracts"
Start-Process -FilePath "npx.cmd" -ArgumentList "hardhat","node","--port","8545" `
  -WindowStyle Hidden `
  -RedirectStandardOutput "$env:TEMP\offlinepay-hardhat.log" `
  -RedirectStandardError  "$env:TEMP\offlinepay-hardhat.err"
Start-Sleep 6

# 3. Deploy.
Write-Host "[2/4] deploying contracts..." -ForegroundColor Yellow
& npx.cmd hardhat run scripts/deploy.js --network localhost 2>&1 | Select-Object -Last 4

# 4. Reset the backend SQLite and start the server.
Write-Host "[3/4] starting backend..." -ForegroundColor Yellow
Set-Location "$root\backend"
Remove-Item -Path "data\offlinepay.db" -ErrorAction SilentlyContinue
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }
Start-Process -FilePath "node.exe" -ArgumentList "src\server.js" `
  -WindowStyle Hidden `
  -RedirectStandardOutput "$env:TEMP\offlinepay-backend.log" `
  -RedirectStandardError  "$env:TEMP\offlinepay-backend.err"
Start-Sleep 4

# 5. Open the dashboard.
Write-Host "[4/4] opening dashboard..." -ForegroundColor Yellow
Start-Process "$root\dashboard\index.html"

Write-Host ""
Write-Host "Ready. Click 'Run end-to-end demo' in the browser." -ForegroundColor Green
Write-Host ""
Write-Host "Or run from CLI: cd simulator; node demo.js" -ForegroundColor Gray
Write-Host "Logs:" -ForegroundColor Gray
Write-Host "  hardhat: $env:TEMP\offlinepay-hardhat.log"
Write-Host "  backend: $env:TEMP\offlinepay-backend.log"
Write-Host ""
Write-Host "To stop, run: Get-Process node | Stop-Process -Force" -ForegroundColor Gray
