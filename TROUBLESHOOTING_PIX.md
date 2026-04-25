# Troubleshooting - Se o PIX Ainda Não Funciona

## ⚠️ Pré-checklist

Antes de iniciar troubleshooting, confirme que já aplicou:

- [ ] Adicionou `SUMUP_PAY_TO_EMAIL` a `php/includes/config.php`
- [ ] Salvou o arquivo
- [ ] Fez refresh/reload do aplicativo Android
- [ ] Esperou cache do PHP atualizar (~5 segundos)

---

## Passo 1: Verificar se a Constante Foi Configurada

### Terminal PowerShell:

```powershell
# Procurar a linha que define SUMUP_PAY_TO_EMAIL
Get-Content "php/includes/config.php" | Select-String "SUMUP_PAY_TO_EMAIL"
```

**Resultado Esperado:**
```
define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');
```

**Se NÃO aparecer:**
- ❌ A constante não foi adicionada
- ✅ Solução: Re-adicionar a linha em config.php

### Se a Verificação Retornar Vazio:

```powershell
# Abrir arquivo para editar
code php/includes/config.php

# Procurar: define('SUMUP_AFFILIATE_APP_ID'
# Adicionar após: define('SUMUP_PAY_TO_EMAIL', 'seu_email@dominio.com');
# Salvar
```

---

## Passo 2: Validar o Email

O email deve ser válido. Opções válidas:

### ✅ Email do Proprietário SumUp
```php
define('SUMUP_PAY_TO_EMAIL', 'seu_email_login@sumup.com');
```

### ✅ Email Empresarial
```php
define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');
```

### ✅ Email Verificado na SumUp
```
Acesse: https://me.sumup.com/settings/payment-methods/pix
Copie o email autorizado de lá
```

### ❌ NUNCA use:
- Email vazio: `''`
- Email de teste inválido: `test@test.com` (se não existe na SumUp)
- Email de outro sistema: `xyz@competitor.com`

---

## Passo 3: Testara API Diretamente

Criar arquivo `php/api/test_pix_debug.php`:

```php
<?php
require_once '../includes/config.php';
require_once '../includes/sumup.php';
require_once '../includes/logger.php';

// Verificação de configuração
echo "=== DEBUG PIX CONFIGURATION ===\n\n";

// 1. Verificar constantes
echo "1. Constants:\n";
$constants = [
    'SUMUP_TOKEN' => defined('SUMUP_TOKEN') ? (strlen(SUMUP_TOKEN) > 20 ? 'OK' : 'TOO_SHORT') : 'MISSING',
    'SUMUP_PAY_TO_EMAIL' => defined('SUMUP_PAY_TO_EMAIL') ? SUMUP_PAY_TO_EMAIL : 'MISSING',
    'SUMUP_MERCHANT_CODE' => defined('SUMUP_MERCHANT_CODE') ? SUMUP_MERCHANT_CODE : 'MISSING',
];

foreach ($constants as $key => $value) {
    echo "   $key: " . ($value === 'MISSING' ? "❌ $value" : "✅ $value") . "\n";
}

// 2. Testar conexão com SumUp
echo "\n2. Testing SumUp API Connection:\n";
$sumup = new SumUpIntegration();

// Este método tenta fazer uma GET em /v0.1/me
// Se retornar TRUE, a API está ok
$isActive = $sumup->isApiActive();
echo "   isApiActive(): " . ($isActive ? "✅ TRUE" : "❌ FALSE") . "\n";

// 3. Tentar criar um PIX de teste
echo "\n3. Creating Test PIX Checkout:\n";
$testOrder = [
    'id' => 99999,
    'valor' => 10.00,
    'descricao' => 'TESTE APENAS - 2026-04-24 ' . date('H:i:s')
];

$result = $sumup->createCheckoutPix($testOrder);

if ($result) {
    echo "   ✅ SUCCESS!\n";
    echo "   checkout_id: " . $result['checkout_id'] . "\n";
    echo "   pix_code_length: " . strlen($result['pix_code'] ?? '') . "\n";
    echo "   qr_code_base64_length: " . strlen($result['qr_code_base64'] ?? '') . "\n";
} else {
    echo "   ❌ FAILED\n";
    echo "   Verifique /php/logs/chooser_app.log para detalhes\n";
}

echo "\n";
?>
```

### Executar:

```bash
cd php/api
php test_pix_debug.php
```

### Resultado Esperado:
```
=== DEBUG PIX CONFIGURATION ===

1. Constants:
   SUMUP_TOKEN: ✅ OK
   SUMUP_PAY_TO_EMAIL: ✅ financeiro@almeida.com.br
   SUMUP_MERCHANT_CODE: ✅ MCTSYDUE

2. Testing SumUp API Connection:
   isApiActive(): ✅ TRUE

3. Creating Test PIX Checkout:
   ✅ SUCCESS!
   checkout_id: chk_abc123xyz
   pix_code_length: 140
   qr_code_base64_length: 12850
```

### Se Falhar:

| Erro | Solução |
|------|---------|
| `SUMUP_PAY_TO_EMAIL: ❌ MISSING` | Adicionar a constante a config.php |
| `SUMUP_TOKEN: ❌ TOO_SHORT` | Token está corrompido, revisar config.php |
| `isApiActive(): ❌ FALSE` | Token inválido ou expirado na SumUp |
| `Creating Test PIX: ❌ FAILED` | Ver arquivo de log (próximo passo) |

---

## Passo 4: Analisar Logs do Servidor

### Ver logs recentes:

```powershell
# PowerShell - Ver últimas 50 linhas
Get-Content "php/logs/chooser_app.log" -Tail 50

# Ou procurar por "PIX" nos logs
Get-Content "php/logs/chooser_app.log" | Select-String -Pattern "PIX|createCheckout|pay_to_email" -Context 2
```

### Logs críticos a procurar:

**1. Se vir isso - PROBLEMA IDENTIFICADO:**
```
ERROR - SumUp PIX: pay_to_email não configurado
```
❌ Solução: Adicionar `SUMUP_PAY_TO_EMAIL` a config.php

**2. Se vir isso - EMAIL INVÁLIDO:**
```
ERROR - SumUp cURL POST error: 
HTTP 400
Body: {"error": "invalid email"}
```
❌ Solução: Verificar se o email é válido na SumUp

**3. Se vir isso - TOKEN EXPIRADO:**
```
ERROR - SumUp cURL POST error:
HTTP 401
Body: {"error": "Unauthorized"}
```
❌ Solução: Renovar token em https://me.sumup.com

**4. Se vir isso - SUCESSO:**
```
INFO - SumUp createCheckoutPix - resposta HTTP 201
INFO - generateQRCode: sucesso (size_bytes: 12850)
```
✅ Correto! PIX está funcionando

---

## Passo 5: Teste Ponta a Ponta (Android)

### Preparação:
1. Abra o logcat do Android (Android Studio)
2. Filtre por tag: `PAGAMENTO_DEBUG`
3. Tente realizar um pagamento PIX

### Logs Esperados:

```
D/PAGAMENTO_DEBUG: 📋 Enviando pagamento PIX
D/PAGAMENTO_DEBUG: POST /api/create_payment.php
D/PAGAMENTO_DEBUG: 📦 create_payment resposta HTTP 200
D/PAGAMENTO_DEBUG: ✅ PIX criado com sucesso
D/PAGAMENTO_DEBUG: 🎨 Exibindo QR Code...
```

### Se ver erro 500:

```
D/PAGAMENTO_DEBUG: 📦 create_payment resposta HTTP 500
D/PAGAMENTO_DEBUG: error: "Erro ao criar checkout PIX"
```

**Ações:**
1. Voltar ao Passo 1: Verificar config.php
2. Ir ao Passo 4: Analisar logs do servidor
3. Executar Passo 3: Teste da API

---

## Passo 6: Limpar Cache

### Se modificou config.php mas ainda vê erro:

**Opção 1: Reiniciar PHP-FPM (se está rodando)**
```powershell
# Windows - Reiniciar Apache via XAMPP
Stop-Service -Name "Apache2.4"
Start-Service -Name "Apache2.4"
```

**Opção 2: Limpar arquivos de sessão PHP**
```powershell
# Remover arquivos de cache PHP
Remove-Item "C:\xampp\tmp\*" -Force

# Se estiver usando Redis/Memcached:
# Executar: FLUSHALL
```

**Opção 3: Forçar recarregar no navegador**
```
Pressie: Ctrl + Shift + Delete (abrir cache do navegador)
Limpar tudo
Ctrl + F5 (Hard refresh)
```

---

## Passo 7: Verificar Banco de Dados

```sql
-- Ver se há pedidos PIX em FAILED status
SELECT id, checkout_id, checkout_status, created_at
FROM `order`
WHERE method = 'pix' AND checkout_status = 'FAILED'
ORDER BY created_at DESC
LIMIT 10;

-- Ver detalhes de transação (v3.x)
SELECT id, checkout_id, status, last_error, created_at
FROM `payment_transaction`
WHERE payment_method = 'pix'
ORDER BY created_at DESC
LIMIT 10;
```

### Se houver muitos registros FAILED:
- Indica que o problema persiste
- Voltar aos passos 1-6

---

## Passo 8: Contato SumUp

Se ainda não funcionar após todos os passos:

### Informações para SumUp:
1. **Merchant Code:** `MCTSYDUE`
2. **Email autorizado:** (do SUMUP_PAY_TO_EMAIL)
3. **Erro:** HTTP 500 / 400 / 401 (qual?)
4. **Timestamp:** Data/hora do erro

### Contato:
- Email: support@sumup.com
- Portal: https://me.sumup.com/help
- Status da API: https://status.sumup.com

**Mencione:** "PIX Checkout Cloud API returns error when pay_to_email is configured"

---

## Checklist de Troubleshooting

- [ ] Passo 1: Verificou se constante foi adicionada? ✅
- [ ] Passo 2: Email é válido? ✅
- [ ] Passo 3: Teste da API retornou sucesso? ✅
- [ ] Passo 4: Logs do servidor não mostram erro? ✅
- [ ] Passo 5: Android mostra QRCODE? ✅
- [ ] Passo 6: Cache foi limpo? ✅
- [ ] Passo 7: Banco de dados mostra pedido com checkout_id? ✅

Se TODOS estão ✅, **PIX ESTÁ FUNCIONANDO!**

---

## Último Recurso: Reset Completo

Se nada acima funcionou (improvável):**

```bash
# 1. Restaurar config.php do backup
cp php/includes/config.php.bak php/includes/config.php

# 2. Re-adicionar constante manualmente
# Abrir arquivo e adicionar:
# define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');

# 3. Limpar logs antigos
rm php/logs/chooser_app.log
touch php/logs/chooser_app.log

# 4. Reiniciar servidor
systemctl restart apache2  # Linux
# ou via XAMPP Control Panel no Windows
```

---

## FAQ Rápido

**P: Por que HTTP 500 e não 400?**  
R: O PHP está respondendo com 500 quando a criação do checkout PIX falha internamente.

**P: Email pode ser de outro banco/sistema?**  
R: Não! Deve ser o email registrado/autorizado na conta SumUp.

**P: Por quanto tempo leva para entrar em vigor?**  
R: Imediatamente após salvar config.php.

**P: E se houver múltiplas lojas?**  
R: Cada loja deve ter seu próprio email/token no config.php.

**P: Posso testar com valor fictício?**  
R: Sim, use 0.01 BRL para test checkouts na SumUp.
