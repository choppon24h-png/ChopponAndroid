@echo off
echo ===================================================
echo   ATUALIZACAO OBRIGATORIA DO CHOPPON ANDROID
echo ===================================================
echo.
echo O log confirmou que o seu Android Studio esta rodando
echo uma versao ANTIGA do codigo (enviando AUTH^|259087).
echo.
echo Este script vai forcar o download do codigo correto
echo do GitHub (com a autenticacao HMAC-SHA256).
echo.
pause

echo.
echo Baixando atualizacoes do GitHub...
git fetch origin main
if %errorlevel% neq 0 (
    echo ERRO: Falha ao conectar com o GitHub. Verifique sua internet.
    pause
    exit /b %errorlevel%
)

echo.
echo Forcando sincronizacao com o codigo oficial...
git reset --hard origin/main
if %errorlevel% neq 0 (
    echo ERRO: Falha ao resetar o codigo.
    pause
    exit /b %errorlevel%
)

echo.
echo Limpando cache do Gradle...
call gradlew clean

echo.
echo ===================================================
echo   ATUALIZACAO CONCLUIDA COM SUCESSO!
echo ===================================================
echo.
echo AGORA FACA O SEGUINTE NO ANDROID STUDIO:
echo 1. Clique em File -^> Sync Project with Gradle Files
echo 2. Clique em Build -^> Rebuild Project
echo 3. Clique em Run (Play verde) para instalar no tablet
echo.
echo IMPORTANTE: Va nas configuracoes de Bluetooth do tablet
echo e ESQUECA (desparelhe) o dispositivo CHOPP_ antes de testar!
echo.
pause
