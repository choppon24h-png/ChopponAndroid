Write-Host "==================================================="
Write-Host "   CORRECAO DEFINITIVA DO BLUETOOTH CHOPPON"
Write-Host "==================================================="
Write-Host ""
Write-Host "Este script vai baixar o arquivo BluetoothServiceIndustrial.java"
Write-Host "diretamente do GitHub e substituir o seu arquivo local."
Write-Host "Isso garante que o codigo novo (HMAC-SHA256) seja usado."
Write-Host ""
Pause

$url = "https://raw.githubusercontent.com/choppon24h-png/ChopponAndroid/main/app/src/main/java/com/example/choppontap/BluetoothServiceIndustrial.java"
$dest = ".\app\src\main\java\com\example\choppontap\BluetoothServiceIndustrial.java"

Write-Host "Baixando arquivo correto do GitHub..."
Invoke-WebRequest -Uri $url -OutFile $dest

if ($?) {
    Write-Host "Arquivo substituido com sucesso!"
    Write-Host ""
    Write-Host "Limpando cache do Gradle..."
    .\gradlew clean
    Write-Host ""
    Write-Host "==================================================="
    Write-Host "   TUDO PRONTO! AGORA NO ANDROID STUDIO:"
    Write-Host "==================================================="
    Write-Host "1. Clique em File -> Sync Project with Gradle Files"
    Write-Host "2. Clique em Build -> Rebuild Project"
    Write-Host "3. Clique no Play verde para instalar no tablet"
    Write-Host ""
    Write-Host "IMPORTANTE: Va no Bluetooth do tablet e ESQUECA o CHOPP_ antes de testar!"
} else {
    Write-Host "ERRO: Falha ao baixar o arquivo. Verifique sua internet."
}

Write-Host ""
Pause
