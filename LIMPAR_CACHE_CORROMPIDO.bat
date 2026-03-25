@echo off
chcp 65001 >nul
title ChoppOnTap - Limpeza de Cache Corrompido

echo.
echo ============================================================
echo   ChoppOnTap - Limpeza de Cache Corrompido do Gradle
echo ============================================================
echo.
echo   Erro identificado: instrumentation-hierarchy.bin corrompido
echo   Solucao: deletar cache global do Gradle 8.11.1
echo.

:: Fechar Android Studio se estiver aberto
echo [1/4] Fechando Android Studio e processos Java...
taskkill /f /im studio64.exe >nul 2>&1
taskkill /f /im java.exe >nul 2>&1
taskkill /f /im javaw.exe >nul 2>&1
timeout /t 3 /nobreak >nul
echo       Processos encerrados.
echo.

:: Deletar cache corrompido especifico (transforms do Gradle 8.11.1)
echo [2/4] Deletando arquivos de cache corrompidos...

set "GRADLE_CACHE=C:\Users\%USERNAME%\.gradle\caches\8.11.1"

if exist "%GRADLE_CACHE%\transforms" (
    echo       Deletando transforms corrompidos...
    rd /s /q "%GRADLE_CACHE%\transforms" 2>nul
    if exist "%GRADLE_CACHE%\transforms" (
        echo       Tentando com permissao elevada...
        takeown /f "%GRADLE_CACHE%\transforms" /r /d y >nul 2>&1
        icacls "%GRADLE_CACHE%\transforms" /grant %USERNAME%:F /t >nul 2>&1
        rd /s /q "%GRADLE_CACHE%\transforms" 2>nul
    )
    echo       Transforms deletados!
) else (
    echo       Pasta transforms nao encontrada - OK
)

:: Deletar todo o cache 8.11.1 para garantir
if exist "%GRADLE_CACHE%" (
    echo       Deletando cache completo 8.11.1...
    rd /s /q "%GRADLE_CACHE%" 2>nul
    echo       Cache 8.11.1 deletado!
)

:: Deletar tambem o daemon
if exist "C:\Users\%USERNAME%\.gradle\daemon" (
    echo       Deletando daemon do Gradle...
    rd /s /q "C:\Users\%USERNAME%\.gradle\daemon" 2>nul
    echo       Daemon deletado!
)

echo.

:: Deletar cache local do projeto
echo [3/4] Limpando cache local do projeto...
set "PROJ=C:\Users\%USERNAME%\StudioProjects\ChopponAndroid"

if exist "%PROJ%\.gradle" (
    rd /s /q "%PROJ%\.gradle" 2>nul
    echo       Cache local .gradle removido
)
if exist "%PROJ%\app\build" (
    rd /s /q "%PROJ%\app\build" 2>nul
    echo       Pasta app\build removida
)
if exist "%PROJ%\build" (
    rd /s /q "%PROJ%\build" 2>nul
    echo       Pasta build raiz removida
)
echo       Cache local limpo!
echo.

:: Abrir Android Studio
echo [4/4] Abrindo Android Studio...
set "STUDIO="
for /d %%D in ("%LOCALAPPDATA%\Google\AndroidStudio*") do (
    if exist "%%D\bin\studio64.exe" set "STUDIO=%%D\bin\studio64.exe"
)
if not defined STUDIO (
    if exist "C:\Program Files\Android\Android Studio\bin\studio64.exe" (
        set "STUDIO=C:\Program Files\Android\Android Studio\bin\studio64.exe"
    )
)

if defined STUDIO (
    echo       Abrindo: %STUDIO%
    start "" "%STUDIO%" "%PROJ%"
) else (
    echo       Android Studio nao encontrado automaticamente.
    echo       Abra manualmente.
)

echo.
echo ============================================================
echo   CACHE LIMPO COM SUCESSO!
echo ============================================================
echo.
echo   Proximos passos no Android Studio:
echo   1. Aguarde o Sync do Gradle (pode demorar 3-5 min)
echo      pois vai baixar as dependencias novamente
echo   2. Selecione: Positivo T307G
echo   3. Clique RUN (triangulo verde)
echo.
echo   IMPORTANTE: Na primeira vez apos a limpeza,
echo   o Gradle vai baixar todas as dependencias da internet.
echo   Certifique-se de ter conexao ativa.
echo ============================================================
echo.
pause
