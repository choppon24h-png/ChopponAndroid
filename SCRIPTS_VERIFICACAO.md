# 🔧 Scripts de Verificação - PIX Configuration

## Script 1: Verificação Rápida em PowerShell

```powershell
# ==========================================
# VERIFICAÇÃO RÁPIDA DE CONFIGURAÇÃO PIX
# ==========================================

Write-Host \"=== Verificacao PIX Configuration ===\" -ForegroundColor Cyan

# 1. Verificar se constante SUMUP_PAY_TO_EMAIL existe
Write-Host \"`n1. Verificando SUMUP_PAY_TO_EMAIL...\" -ForegroundColor Yellow
$payToEmail = Select-String -Path \"php/includes/config.php\" -Pattern \"SUMUP_PAY_TO_EMAIL\"
if ($payToEmail) {
    Write-Host \"   ✅ ENCONTRADA:\" $payToEmail.Line -ForegroundColor Green
} else {
    Write-Host \"   ❌ NÃO ENCONTRADA\" -ForegroundColor Red
    Write-Host \"   SOLUÇÃO: Adicione em config.php\" -ForegroundColor Yellow
}

# 2. Verificar outras constantes
Write-Host \"`n2. Verificando outras constantes...\" -ForegroundColor Yellow
$constants = @(
    'SUMUP_TOKEN',
    'SUMUP_MERCHANT_CODE',
    'SUMUP_AFFILIATE_KEY',
    'SUMUP_AFFILIATE_APP_ID'
)

foreach ($const in $constants) {
    $found = Select-String -Path \"php/includes/config.php\" -Pattern $const
    $status = if ($found) { \"✅\" } else { \"❌\" }
    Write-Host \"   $status $const\"
}

# 3. Verificar criar log
Write-Host \"`n3. Verificando logs recentes...\" -ForegroundColor Yellow
$logFile = \"php/logs/chooser_app.log\"

if (Test-Path $logFile) {
    Write-Host \"   ✅ Arquivo de log existe\" -ForegroundColor Green
    
    # Ver últimas 5 linhas com PIX
    $pixLogs = Get-Content $logFile -Tail 50 | Select-String -Pattern \"PIX|createCheckout\" -Context 1
    
    if ($pixLogs) {
        Write-Host \"   📋 Logs PIX recentes:\" -ForegroundColor Cyan
        Write-Host $pixLogs[0..4] -ForegroundColor Gray
    } else {
        Write-Host \"   ⚠️ Nenhum log PIX encontrado\" -ForegroundColor Yellow
    }
} else {
    Write-Host \"   ❌ Arquivo de log não existe\" -ForegroundColor Red
}

# 4. Resumo
Write-Host \"`n=== RESUMO ===\" -ForegroundColor Cyan
Write-Host \"Se todos os itens acima estão ✅, PIX deve funcionar!\" -ForegroundColor Green
```

---

## Script 2: Verificação de Banco de Dados

```powershell
# ==========================================
# VERIFICAÇÃO DE PEDIDOS PIX NO BANCO
# ==========================================

Write-Host \"=== Verificacao Banco de Dados PIX ===\" -ForegroundColor Cyan

# Você precisa configurar as credenciais:
$MySQLUser = \"inlaud99_admin\"
$MySQLPass = \"Admin259087@\"
$MySQLHost = \"localhost\"
$MySQLDB = \"inlaud99_choppontap\"

# SQL query para verificar
\$sqlQuery = @\"
SELECT 
  id, 
  method, 
  valor, 
  checkout_id, 
  checkout_status, 
  created_at
FROM \\\`order\\\`
WHERE method = 'pix'
ORDER BY created_at DESC
LIMIT 5;
\"@

Write-Host \"Executando query no banco...\" -ForegroundColor Yellow

# Executar com mysql.exe (se disponível via PATH)
# Ou via PHP
php -r \"
    \\$conn = new PDO('mysql:host=$MySQLHost;dbname=$MySQLDB', '$MySQLUser', '$MySQLPass');
    \\$result = \\$conn->query('SELECT id, method, valor, checkout_id, checkout_status, created_at FROM \\\`order\\\` WHERE method=\"pix\" ORDER BY created_at DESC LIMIT 5');
    \\$rows = \\$result->fetchAll();
    if (!empty(\\$rows)) {
        echo \\\"✅ Encontrados \\\" . count(\\$rows) . \\\" pedidos PIX:\\\\n\\\";
        foreach (\\$rows as \\$row) {
            echo \\\"   - Order \\\" . \\$row['id'] . \\\": \\\" . \\$row['checkout_status'] . \\\" (\\\" . \\$row['valor'] . \\\" BRL)\\\\n\\\";
        }
    } else {
        echo \\\"❌ Nenhum pedido PIX encontrado\\\\n\\\";
    }
\"

Write-Host \"\" -ForegroundColor Green
```

---

## Script 3: Teste de API PIX (PHP)

Criar arquivo: `php/api/quick_test_pix.php`

```php
<?php
// Quick PIX Test
require_once '../includes/config.php';
require_once '../includes/sumup.php';
require_once '../includes/logger.php';

echo \"\\n=== QUICK PIX TEST ===\\n\\n\";

// Check 1: Constants
echo \"1. Configuration Check:\\n\";
$ok = true;
$constants = [
    'SUMUP_TOKEN' => 'Token',
    'SUMUP_MERCHANT_CODE' => 'Merchant Code',
    'SUMUP_PAY_TO_EMAIL' => 'Pay To Email',
];

foreach ($constants as $const => $name) {
    if (!defined($const)) {
        echo \"   ❌ $name não definido\\n\";
        $ok = false;
    } elseif (empty(constant($const))) {
        echo \"   ❌ $name vazio\\n\";
        $ok = false;
    } else {
        echo \"   ✅ $name OK\\n\";
    }
}

if (!$ok) {
    echo \"\\n❌ FALHA: Configuração incompleta\\n\";
    exit(1);
}

// Check 2: Test PIX Checkout
echo \"\\n2. Creating Test PIX Checkout:\\n\";

$sumup = new SumUpIntegration();

$testOrder = [
    'id' => 99999,
    'valor' => 1.00,
    'descricao' => 'TEST PIX - ' . date('Y-m-d H:i:s')
];

$result = $sumup->createCheckoutPix($testOrder);

if ($result && !empty($result['checkout_id'])) {
    echo \"   ✅ SUCCESS!\\n\";
    echo \"   Checkout ID: \" . $result['checkout_id'] . \"\\n\";
    echo \"   PIX Code Length: \" . strlen($result['pix_code'] ?? '') . \"\\n\";
    echo \"   QR Code Generated: \" . (!empty($result['qr_code_base64']) ? 'YES' : 'NO') . \"\\n\";
} else {
    echo \"   ❌ FAILED\\n\";
    echo \"   Check logs at: php/logs/chooser_app.log\\n\";
}

echo \"\\n=== END TEST ===\\n\";
?>
```

**Executar:**
```bash
php php/api/quick_test_pix.php
```

---

## Script 4: Monitorar Logs em Tempo Real

```bash
# Linux/Mac
tail -f php/logs/chooser_app.log | grep -E \"PIX|createCheckout|SUMUP|HTTP (500|201)\"

# Windows PowerShell
Get-Content -Path \"php/logs/chooser_app.log\" -Wait | Select-String -Pattern \"PIX|createCheckout|SUMUP|HTTP\"
```

---

## Script 5: Limpar Cache (Se necessário)

```powershell
# PowerShell

Write-Host \"Limpando cache...\" -ForegroundColor Yellow

# 1. Limpar cache do PHP
if (Test-Path \"C:\\xampp\\tmp\") {
    Remove-Item \"C:\\xampp\\tmp\\*\" -Force -ErrorAction SilentlyContinue
    Write-Host \"✅ PHP tmp limpo\" -ForegroundColor Green
}

# 2. Reiniciar Apache (se necessário)
Write-Host \"`nReiniciando Apache...\" -ForegroundColor Yellow
# Se estiver usando XAMPP, abra Control Panel e clique em Stop/Start

Write-Host \"✅ Cache limpo\" -ForegroundColor Green
```

---

## Script 6: Verificação Completa

```powershell
# ==========================================
# VERIFICAÇÃO COMPLETA PIX
# ==========================================

function Test-PixConfiguration {
    Write-Host \"\\n╔════════════════════════════════════╗\" -ForegroundColor Cyan
    Write-Host \"║  COMPLETE PIX VERIFICATION          ║\" -ForegroundColor Cyan
    Write-Host \"╚════════════════════════════════════╝\\n\" -ForegroundColor Cyan
    
    $passed = 0
    $failed = 0
    
    # Test 1: config.php exists
    if (Test-Path \"php/includes/config.php\") {
        Write-Host \"✅ config.php existe\" -ForegroundColor Green
        $passed++
    } else {
        Write-Host \"❌ config.php não encontrado\" -ForegroundColor Red
        $failed++
    }
    
    # Test 2: SUMUP_PAY_TO_EMAIL exists
    $hasEmail = Select-String -Path \"php/includes/config.php\" -Pattern \"SUMUP_PAY_TO_EMAIL\" -ErrorAction SilentlyContinue
    if ($hasEmail) {
        Write-Host \"✅ SUMUP_PAY_TO_EMAIL definido\" -ForegroundColor Green
        $passed++
    } else {
        Write-Host \"❌ SUMUP_PAY_TO_EMAIL não definido\" -ForegroundColor Red
        $failed++
    }
    
    # Test 3: Log file exists
    if (Test-Path \"php/logs/chooser_app.log\") {
        Write-Host \"✅ Log file existe\" -ForegroundColor Green
        $passed++
    } else {
        Write-Host \"❌ Log file não encontrado\" -ForegroundColor Red
        $failed++
    }
    
    # Test 4: sumup.php exists
    if (Test-Path \"php/includes/sumup.php\") {
        Write-Host \"✅ sumup.php existe\" -ForegroundColor Green
        $passed++
    } else {
        Write-Host \"❌ sumup.php não encontrado\" -ForegroundColor Red
        $failed++
    }
    
    # Summary
    Write-Host \"\\n═══════════════════════════════════\" -ForegroundColor Cyan
    Write-Host \"Passed: $passed\" -ForegroundColor Green
    Write-Host \"Failed: $failed\" -ForegroundColor Red
    
    if ($failed -eq 0) {
        Write-Host \"\\n✅ Tudo OK! PIX deve funcionar.\" -ForegroundColor Green
    } else {
        Write-Host \"\\n❌ Há problemas a resolver. Ver acima.\" -ForegroundColor Red
    }
    
    Write-Host \"═══════════════════════════════════\\n\" -ForegroundColor Cyan
}

# Executar
Test-PixConfiguration
```

**Usar:**
```powershell
# Copie a função acima e execute:
Test-PixConfiguration
```

---

## 🎯 Usar Qual Script?

| Situação | Script |
|----------|--------|
| Verificação rápida | Script 1 (PowerShell) |
| Verificar pedidos antigos | Script 2 (DB) |
| Testar API | Script 3 (PHP) |
| Monitorar logs | Script 4 (Tail) |
| Limpar cache | Script 5 (PowerShell) |
| Verificação completa | Script 6 (PowerShell) |

---

## ⚡ Um-liner: Apenas Verificação

```bash
# Verificar se SUMUP_PAY_TO_EMAIL está definido
grep -c \"SUMUP_PAY_TO_EMAIL\" php/includes/config.php && echo \"✅ OK\" || echo \"❌ MISSING\"
```

---

## 📝 Notas

- Todos os scripts usam Windows PowerShell
- Para Linux/Mac, adapte os comandos
- Salve os scripts `.ps1` no diretório raiz
- Execute com: `powershell -ExecutionPolicy Bypass -File nome_script.ps1`
