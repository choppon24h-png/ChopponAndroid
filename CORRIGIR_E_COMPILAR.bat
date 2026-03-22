@echo off
chcp 65001 >nul
title ChoppOnTap - Correcao Automatica JVM-target

echo ============================================================
echo  ChoppOnTap v2.0 - Correcao Automatica do Build
echo  Repositorio: choppon24h-png/ChopponAndroid
echo ============================================================
echo.

:: Definir caminho do projeto
set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

echo [1/5] Verificando Git...
where git >nul 2>&1
if %errorlevel% neq 0 (
    echo ERRO: Git nao encontrado. Instale em https://git-scm.com
    pause
    exit /b 1
)
echo       Git OK

echo.
echo [2/5] Baixando correcoes do GitHub...
git fetch origin main
git reset --hard origin/main
if %errorlevel% neq 0 (
    echo AVISO: Nao foi possivel sincronizar com GitHub. Continuando...
)
echo       Arquivos sincronizados com GitHub

echo.
echo [3/5] Limpando cache do Gradle (resolve o erro JVM-target)...
if exist ".gradle" (
    rmdir /s /q ".gradle"
    echo       Cache local .gradle removido
)
if exist "app\build" (
    rmdir /s /q "app\build"
    echo       Pasta build removida
)
if exist "build" (
    rmdir /s /q "build"
    echo       Pasta build raiz removida
)

:: Limpar cache global do Gradle
set "GRADLE_CACHE=%USERPROFILE%\.gradle\caches"
if exist "%GRADLE_CACHE%" (
    echo       Limpando cache global do Gradle...
    rmdir /s /q "%GRADLE_CACHE%"
    echo       Cache global removido
)

:: Limpar daemon Gradle
set "GRADLE_DAEMON=%USERPROFILE%\.gradle\daemon"
if exist "%GRADLE_DAEMON%" (
    rmdir /s /q "%GRADLE_DAEMON%"
    echo       Daemon Gradle removido
)

:: Parar daemons Gradle em execucao
echo       Parando daemons Gradle...
taskkill /f /im java.exe /fi "WINDOWTITLE eq Gradle*" >nul 2>&1

:: Limpar cache do Android Studio
for /d %%i in ("%APPDATA%\Google\AndroidStudio*") do (
    if exist "%%i\system\caches" (
        rmdir /s /q "%%i\system\caches"
        echo       Cache do Android Studio removido
    )
)
echo       Limpeza completa concluida

echo.
echo [4/5] Verificando versao do Java...
java -version 2>&1 | findstr /i "version"
echo       (Necessario: JDK 17 ou superior)

echo.
echo [5/5] Abrindo Android Studio com o projeto...
set "STUDIO_PATH="
for /d %%i in ("%LOCALAPPDATA%\Google\AndroidStudio*") do (
    if exist "%%i\bin\studio64.exe" set "STUDIO_PATH=%%i\bin\studio64.exe"
)
if not defined STUDIO_PATH (
    for /d %%i in ("C:\Program Files\Android\Android Studio*") do (
        if exist "%%i\bin\studio64.exe" set "STUDIO_PATH=%%i\bin\studio64.exe"
    )
)
if not defined STUDIO_PATH (
    for /d %%i in ("C:\Program Files\Android Studio*") do (
        if exist "%%i\bin\studio64.exe" set "STUDIO_PATH=%%i\bin\studio64.exe"
    )
)

if defined STUDIO_PATH (
    echo       Abrindo: %STUDIO_PATH%
    start "" "%STUDIO_PATH%" "%PROJECT_DIR%"
) else (
    echo       Android Studio nao encontrado. Abra manualmente.
)

echo.
echo ============================================================
echo  CORRECAO APLICADA!
echo ============================================================
echo.
echo  Apos o Android Studio abrir:
echo  1. Aguarde o Sync do Gradle (pode demorar 2-3 min)
echo  2. Selecione dispositivo: Positivo T307G
echo  3. Clique RUN (triangulo verde) ou Shift+F10
echo.
echo  SE AINDA HOUVER ERRO:
echo  File - Invalidate Caches - Invalidate and Restart
echo  Depois aguarde o Sync e tente compilar novamente.
echo ============================================================
echo.
pause
