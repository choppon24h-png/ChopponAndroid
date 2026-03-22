# ============================================================
# ChoppOnTap v2.0 - Correcao Local JVM-target
# Execute este script com 1 clique para corrigir o erro:
# "Inconsistent JVM-target compatibility (17) and (21)"
# ============================================================
# Como executar:
#   Clique com botao direito no arquivo > "Executar com PowerShell"
#   OU no terminal: powershell -ExecutionPolicy Bypass -File APLICAR_CORRECAO_LOCAL.ps1
# ============================================================

$ErrorActionPreference = "Stop"
$host.UI.RawUI.WindowTitle = "ChoppOnTap - Correcao JVM-target"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  ChoppOnTap v2.0 - Correcao JVM-target" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Detectar pasta do projeto automaticamente
$possiblePaths = @(
    "C:\Users\Usuario\StudioProjects\ChopponAndroid",
    "$env:USERPROFILE\StudioProjects\ChopponAndroid",
    "$env:USERPROFILE\AndroidStudioProjects\ChopponAndroid",
    (Split-Path -Parent $MyInvocation.MyCommand.Path)
)

$projectDir = $null
foreach ($path in $possiblePaths) {
    if (Test-Path "$path\gradle.properties") {
        $projectDir = $path
        break
    }
}

if (-not $projectDir) {
    Write-Host "ERRO: Projeto nao encontrado. Informe o caminho:" -ForegroundColor Red
    $projectDir = Read-Host "Caminho do projeto (ex: C:\Users\Usuario\StudioProjects\ChopponAndroid)"
    if (-not (Test-Path "$projectDir\gradle.properties")) {
        Write-Host "Caminho invalido. Encerrando." -ForegroundColor Red
        Read-Host "Pressione Enter para sair"
        exit 1
    }
}

Write-Host "[1/4] Projeto encontrado em: $projectDir" -ForegroundColor Green

# ── PASSO 1: Editar gradle.properties ────────────────────────────────────────
Write-Host ""
Write-Host "[2/4] Aplicando correcao no gradle.properties..." -ForegroundColor Yellow

$gradlePropsPath = "$projectDir\gradle.properties"
$content = Get-Content $gradlePropsPath -Raw

# Verificar se a propriedade ja existe
if ($content -match "kotlin\.jvm\.target\.validation\.mode") {
    # Substituir qualquer valor existente por IGNORE
    $content = $content -replace "kotlin\.jvm\.target\.validation\.mode\s*=\s*\w+", "kotlin.jvm.target.validation.mode=IGNORE"
    Write-Host "      Propriedade atualizada para IGNORE" -ForegroundColor Green
} else {
    # Adicionar ao final do arquivo
    $content = $content.TrimEnd() + "`r`n`r`n# Correcao JVM-target (AGP 8.x + Kotlin 2.x)`r`nkotlin.jvm.target.validation.mode=IGNORE`r`n"
    Write-Host "      Propriedade adicionada ao gradle.properties" -ForegroundColor Green
}

Set-Content -Path $gradlePropsPath -Value $content -Encoding UTF8
Write-Host "      gradle.properties salvo com sucesso!" -ForegroundColor Green

# ── PASSO 2: Limpar cache do Gradle ──────────────────────────────────────────
Write-Host ""
Write-Host "[3/4] Limpando cache do Gradle..." -ForegroundColor Yellow

# Cache local do projeto
$localGradle = "$projectDir\.gradle"
if (Test-Path $localGradle) {
    Remove-Item -Recurse -Force $localGradle
    Write-Host "      Cache local .gradle removido" -ForegroundColor Green
}

# Build do projeto
$buildDir = "$projectDir\app\build"
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
    Write-Host "      Pasta app\build removida" -ForegroundColor Green
}

# Cache global do Gradle (daemon e caches)
$gradleUserHome = "$env:USERPROFILE\.gradle"
$gradleCaches = "$gradleUserHome\caches"
$gradleDaemon = "$gradleUserHome\daemon"

if (Test-Path $gradleCaches) {
    Remove-Item -Recurse -Force $gradleCaches
    Write-Host "      Cache global do Gradle removido" -ForegroundColor Green
}
if (Test-Path $gradleDaemon) {
    Remove-Item -Recurse -Force $gradleDaemon
    Write-Host "      Daemon do Gradle removido" -ForegroundColor Green
}

# Parar processos Java do Gradle
Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object {
    $_.MainWindowTitle -like "*gradle*" -or $_.CommandLine -like "*gradle*"
} | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "      Processos Gradle encerrados" -ForegroundColor Green

# ── PASSO 3: Abrir Android Studio ────────────────────────────────────────────
Write-Host ""
Write-Host "[4/4] Abrindo Android Studio..." -ForegroundColor Yellow

$studioExe = $null
$searchPaths = @(
    "$env:LOCALAPPDATA\Google\AndroidStudio*\bin\studio64.exe",
    "C:\Program Files\Android\Android Studio\bin\studio64.exe",
    "C:\Program Files\Android Studio\bin\studio64.exe"
)

foreach ($pattern in $searchPaths) {
    $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $studioExe = $found.FullName; break }
}

if ($studioExe) {
    Write-Host "      Abrindo: $studioExe" -ForegroundColor Green
    Start-Process $studioExe -ArgumentList "`"$projectDir`""
} else {
    Write-Host "      Android Studio nao encontrado automaticamente." -ForegroundColor Yellow
    Write-Host "      Abra manualmente e faca File > Sync Project with Gradle Files" -ForegroundColor Yellow
}

# ── RESULTADO ─────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  CORRECAO APLICADA COM SUCESSO!" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  O arquivo gradle.properties foi editado com:" -ForegroundColor White
Write-Host "  kotlin.jvm.target.validation.mode=IGNORE" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Proximos passos no Android Studio:" -ForegroundColor White
Write-Host "  1. Aguarde o Sync do Gradle (2-3 minutos)" -ForegroundColor White
Write-Host "  2. Selecione: Positivo T307G" -ForegroundColor White
Write-Host "  3. Clique RUN (triangulo verde) ou Shift+F10" -ForegroundColor White
Write-Host ""
Write-Host "  SE AINDA HOUVER ERRO:" -ForegroundColor Red
Write-Host "  File > Invalidate Caches > Invalidate and Restart" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Read-Host "Pressione Enter para fechar"
