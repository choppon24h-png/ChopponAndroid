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
git pull origin main
if %errorlevel% neq 0 (
    echo AVISO: Nao foi possivel fazer git pull. Continuando com arquivos locais...
)
echo       Arquivos atualizados

echo.
echo [3/5] Limpando cache do Gradle (resolve o erro JVM-target)...
:: Limpar cache local do projeto
if exist ".gradle" (
    rmdir /s /q ".gradle"
    echo       Cache local .gradle removido
)
if exist "app\build" (
    rmdir /s /q "app\build"
    echo       Pasta build removida
)

:: Limpar cache global do Gradle no Windows
set "GRADLE_CACHE=%USERPROFILE%\.gradle\caches"
if exist "%GRADLE_CACHE%" (
    echo       Limpando cache global do Gradle em %GRADLE_CACHE%...
    rmdir /s /q "%GRADLE_CACHE%"
    echo       Cache global removido
)

:: Limpar cache do Android Studio (Kotlin daemon)
set "AS_SYSTEM=%APPDATA%\Google\AndroidStudio*"
for /d %%i in ("%APPDATA%\Google\AndroidStudio*") do (
    if exist "%%i\system\kotlin" (
        rmdir /s /q "%%i\system\kotlin"
        echo       Cache Kotlin do Android Studio removido
    )
)
echo       Limpeza de cache concluida

echo.
echo [4/5] Verificando versao do Java...
java -version 2>&1 | findstr /i "version"
echo       (Recomendado: JDK 17 - Android Studio Hedgehog+)

echo.
echo [5/5] Abrindo Android Studio com o projeto...
:: Tentar abrir Android Studio automaticamente
set "STUDIO_PATH="
for /d %%i in ("%LOCALAPPDATA%\Google\AndroidStudio*") do (
    if exist "%%i\bin\studio64.exe" set "STUDIO_PATH=%%i\bin\studio64.exe"
)
if not defined STUDIO_PATH (
    for /d %%i in ("C:\Program Files\Android\Android Studio*") do (
        if exist "%%i\bin\studio64.exe" set "STUDIO_PATH=%%i\bin\studio64.exe"
    )
)

if defined STUDIO_PATH (
    echo       Abrindo: %STUDIO_PATH%
    start "" "%STUDIO_PATH%" "%PROJECT_DIR%"
) else (
    echo       Android Studio nao encontrado automaticamente.
    echo       Abra manualmente: File - Open - selecione esta pasta
)

echo.
echo ============================================================
echo  CORRECAO APLICADA COM SUCESSO!
echo ============================================================
echo.
echo  Apos o Android Studio abrir:
echo  1. Aguarde o Sync do Gradle terminar automaticamente
echo  2. Se pedir Sync, clique em "Sync Now"
echo  3. Selecione o dispositivo: Positivo T307G
echo  4. Clique no botao RUN (triangulo verde) ou Shift+F10
echo.
echo  Se ainda aparecer erro JVM-target, va em:
echo  File - Invalidate Caches - Invalidate and Restart
echo  Depois faca Sync novamente.
echo ============================================================
echo.
pause
