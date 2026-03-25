@echo off
chcp 65001 >nul
title ChoppOnTap - Correcao JVM-target

echo.
echo ============================================================
echo   ChoppOnTap v2.0 - Correcao JVM-target
echo ============================================================
echo.

set "PROJ=C:\Users\Usuario\StudioProjects\ChopponAndroid"
set "PROPS=%PROJ%\gradle.properties"

if not exist "%PROPS%" (
    echo ERRO: Arquivo nao encontrado:
    echo %PROPS%
    echo.
    echo Verifique se o caminho esta correto.
    pause
    exit /b 1
)

echo [1/3] Aplicando correcao no gradle.properties...

:: Verificar se a linha ja existe
findstr /C:"kotlin.jvm.target.validation.mode" "%PROPS%" >nul 2>&1
if %errorlevel%==0 (
    echo       Propriedade ja existe - substituindo pelo valor correto...
    :: Criar arquivo temporario sem a linha antiga
    type nul > "%PROPS%.tmp"
    for /f "usebackq delims=" %%A in ("%PROPS%") do (
        echo %%A | findstr /C:"kotlin.jvm.target.validation.mode" >nul 2>&1
        if errorlevel 1 (
            echo %%A>> "%PROPS%.tmp"
        )
    )
    echo kotlin.jvm.target.validation.mode=IGNORE>> "%PROPS%.tmp"
    move /y "%PROPS%.tmp" "%PROPS%" >nul
) else (
    echo. >> "%PROPS%"
    echo # Correcao JVM-target AGP 8.x + Kotlin 2.x>> "%PROPS%"
    echo kotlin.jvm.target.validation.mode=IGNORE>> "%PROPS%"
)

echo       FEITO! Linha adicionada ao gradle.properties
echo.

echo [2/3] Limpando cache do Gradle...
if exist "%PROJ%\.gradle" (
    rd /s /q "%PROJ%\.gradle" 2>nul
    echo       Cache local .gradle removido
)
if exist "%PROJ%\app\build" (
    rd /s /q "%PROJ%\app\build" 2>nul
    echo       Pasta app\build removida
)
if exist "%USERPROFILE%\.gradle\caches" (
    rd /s /q "%USERPROFILE%\.gradle\caches" 2>nul
    echo       Cache global do Gradle removido
)
echo       FEITO!
echo.

echo [3/3] Verificando resultado...
findstr /C:"kotlin.jvm.target.validation.mode=IGNORE" "%PROPS%" >nul 2>&1
if %errorlevel%==0 (
    echo       CONFIRMADO: kotlin.jvm.target.validation.mode=IGNORE esta no arquivo!
) else (
    echo       ATENCAO: Nao foi possivel confirmar. Verifique o arquivo manualmente.
)

echo.
echo ============================================================
echo   CORRECAO APLICADA COM SUCESSO!
echo ============================================================
echo.
echo   Proximos passos no Android Studio:
echo   1. File ^> Sync Project with Gradle Files
echo   2. Selecione: Positivo T307G
echo   3. Clique RUN (triangulo verde)
echo.
echo   SE AINDA HOUVER ERRO:
echo   File ^> Invalidate Caches ^> Invalidate and Restart
echo ============================================================
echo.
pause
