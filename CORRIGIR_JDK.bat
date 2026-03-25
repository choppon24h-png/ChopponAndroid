@echo off
chcp 65001 >nul
title ChoppOnTap - Correcao JDK

echo.
echo ============================================================
echo   ChoppOnTap - Correcao Gradle JDK
echo ============================================================
echo   Erro: Undefined java.home (GRADLE_LOCAL_JAVA_HOME)
echo ============================================================
echo.

set "PROJ=C:\Users\%USERNAME%\StudioProjects\ChopponAndroid"
set "GRADLE_DIR=%PROJ%\gradle"
set "CONFIG=%GRADLE_DIR%\config.properties"

:: Criar pasta gradle se nao existir
if not exist "%GRADLE_DIR%" (
    mkdir "%GRADLE_DIR%"
    echo [1/3] Pasta gradle\ criada.
) else (
    echo [1/3] Pasta gradle\ ja existe.
)

:: Detectar JDK embutido do Android Studio
echo [2/3] Detectando JDK embutido do Android Studio...
set "JBR="

:: Tentar caminhos conhecidos
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JBR=C:\\Program Files\\Android\\Android Studio\\jbr"
)
if not defined JBR (
    for /d %%D in ("%LOCALAPPDATA%\Google\AndroidStudio*") do (
        if exist "%%D\jbr\bin\java.exe" (
            set "JBR=%%D\jbr"
        )
    )
)
if not defined JBR (
    if exist "C:\Program Files\Android Studio\jbr\bin\java.exe" (
        set "JBR=C:\\Program Files\\Android Studio\\jbr"
    )
)

if not defined JBR (
    echo       ATENCAO: JDK embutido nao encontrado automaticamente.
    echo       Usando caminho padrao do Android Studio...
    set "JBR=C:\\Program Files\\Android\\Android Studio\\jbr"
) else (
    echo       JDK encontrado: %JBR%
)

:: Escapar barras para o formato .properties (\ -> \\)
set "JBR_ESC=%JBR:\=\\%"

:: Escrever o arquivo config.properties
echo [3/3] Criando gradle\config.properties...
(
    echo # Gradle JDK configuration - gerado por CORRIGIR_JDK.bat
    echo # Aponta para o JDK embutido do Android Studio ^(JBR 17^)
    echo java.home=%JBR_ESC%
) > "%CONFIG%"

echo       Arquivo criado: %CONFIG%
echo       Conteudo:
type "%CONFIG%"
echo.

echo ============================================================
echo   CORRECAO APLICADA!
echo ============================================================
echo.
echo   Proximos passos no Android Studio:
echo   1. File ^> Sync Project with Gradle Files
echo   2. O aviso "Invalid Gradle JDK" deve desaparecer
echo   3. Selecione Positivo T307G e clique RUN
echo.
echo   SE AINDA APARECER O AVISO:
echo   Clique em "Use Embedded JDK" no proprio aviso
echo   do Android Studio - isso sobrescreve o arquivo
echo   com o caminho correto automaticamente.
echo ============================================================
echo.
pause
