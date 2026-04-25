# TESTES E VALIDAÇÃO - PIX Checkout

## Teste 1: Validação de Configuração

### Via Terminal PowerShell:

```powershell
# Verificar se o config.php contém a constante
Get-Content "php/includes/config.php" | Select-String "SUMUP_PAY_TO_EMAIL"
```

**Resultado esperado:**
```
define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');
```

---

## Teste 2: Teste de API (Curl)

### Criar um script de teste: `php/api/test_pix_checkout.php`

```php
<?php
// Teste de criação de checkout PIX
require_once '../includes/config.php';
require_once '../includes/jwt.php';
require_once '../includes/sumup.php';
require_once '../includes/logger.php';

$sumup = new SumUpIntegration();

// Simular dados de pedido
$order = [
    'id' => 99999,
    'valor' => 50.00,
    'descricao' => 'TESTE: Chopp 300ml'
];

echo "=== TESTE DE PIX CHECKOUT ===\n\n";

// Verificar configuração
echo "1. Verificando Configuração:\n";
echo "   SUMUP_TOKEN: " . (defined('SUMUP_TOKEN') && !empty(SUMUP_TOKEN) ? "✅ OK" : "❌ VAZIO") . "\n";
echo "   SUMUP_PAY_TO_EMAIL: " . (defined('SUMUP_PAY_TO_EMAIL') && !empty(SUMUP_PAY_TO_EMAIL) ? "✅ OK (" . SUMUP_PAY_TO_EMAIL . ")" : "❌ VAZIO") . "\n";

// API está ativa?
echo "\n2. Verificando Conexão com SumUp:\n";
$isActive = $sumup->isApiActive();
echo "   isApiActive(): " . ($isActive ? "✅ OK" : "❌ FALHA") . "\n";

// Tentar criar checkout PIX
echo "\n3. Criando Checkout PIX:\n";
$result = $sumup->createCheckoutPix($order);

if ($result) {
    echo "   ✅ SUCESSO!\n";
    echo "   checkout_id: " . $result['checkout_id'] . "\n";
    echo "   pix_code: " . substr($result['pix_code'] ?? '', 0, 50) . "...\n";
    echo "   qr_code: " . (strlen($result['qr_code_base64'] ?? '') > 100 ? "✅ Presente (" . strlen($result['qr_code_base64']) . " bytes)" : "❌ Ausente") . "\n";
} else {
    echo "   ❌ FALHA - Verifique os logs em /php/logs/\n";
}
?>
```

### Executar teste:

```bash
# Via terminal
cd php/api
php test_pix_checkout.php
```

---

## Teste 3: Teste Completo via Android

### Passos:
1. Abrir o app ChoppOn Android
2. Selecionar **PIX** como forma de pagamento
3. Inserir dados de teste:
   - CPF: `12345678901` (ou CPF válido)
   - Descrição: `TESTE PIX` ou `Chopp 300ml`
   - Valor: `10.00`

### Esperado:
- ✅ O QRCODE aparece na tela
- ✅ Mensagem de sucesso "PIX gerado com sucesso"
- ✅ Botão "Copiar código PIX" fica ativo

### Se falhar:
- ❌ Log de erro: "Erro ao criar checkout PIX"
- ❌ QRCODE não aparece

---

## Teste 4: Monitorar Logs do Servidor

### Arquivo de Log:
```
php/logs/chooser_app.log
```

### Executar (PowerShell):
```powershell
# Ver últimas 20 linhas do log
Get-Content "php/logs/chooser_app.log" -Tail 20

# Ou buscar por "PIX" nos últimos 100 registros
Get-Content "php/logs/chooser_app.log" -Tail 100 | Select-String -Pattern "PIX|createCheckout|SUMUP"
```

### Logs esperados após correção:

```
[2026-04-24 17:30:45] INFO - SumUp createCheckoutPix - enviando
  {
    "url": "https://api.sumup.com/v0.1/checkouts",
    "valor": 50,
    "order_id": 5432,
    "pay_to_email": "financeiro@almeida.com.br"
  }

[2026-04-24 17:30:47] INFO - SumUp createCheckoutPix - resposta
  {
    "http_code": 201,
    "body_short": "{\"id\":\"chk_abc123\",\"status\":\"PENDING\"...}"
  }

[2026-04-24 17:30:47] INFO - generateQRCode: sucesso
  {
    "size_bytes": 12850
  }

[2026-04-24 17:30:48] INFO - create_payment: resposta sucesso
  {
    "success": true,
    "checkout_id": "chk_abc123",
    "pix_code": "00020126360014br.gov.bcb.pix...",
    "qr_code": "data:image/png;base64,iVBORw0KGg..."
  }
```

---

## Teste 5: Caso de Erro - Diagnosticar

Se ainda retornar erro 500, executar:

```bash
# Ver logs de erro detalhado
tail -50 php/logs/chooser_app.log | grep -i "error\|sumup\|pix"

# Ou se estiver em Windows PowerShell:
Get-Content "php/logs/chooser_app.log" -Tail 50 | Select-String -Pattern "error|sumup|pix" -CaseSensitive
```

### Possíveis erros e soluções:

| Erro | Causa | Solução |
|------|-------|---------|
| "SumUp: token não configurado" | SUMUP_TOKEN vazio | Verificar config.php linha 70 |
| "SumUp PIX: pay_to_email vazio" | SUMUP_PAY_TO_EMAIL não definido | ✅ JÁ FOI CORRIGIDO |
| "HTTP 401" | Token SumUp inválido/expirado | Renovar em me.sumup.com |
| "HTTP 400" | Dados inválidos enviados | Verificar se email é válido |
| "cURL error: Timeout" | Conexão com SumUp lenta | Tentar novamente ou verificar internet |

---

## Teste 6: Validar no Banco de Dados

### SQL - Verificar pedidos criados:

```sql
-- Verificar pedidos PIX criados nos últimos 30 minutos
SELECT 
  id,
  method,
  valor,
  checkout_id,
  checkout_status,
  created_at
FROM `order` 
WHERE method = 'pix' 
  AND created_at > DATE_SUB(NOW(), INTERVAL 30 MINUTE)
ORDER BY created_at DESC;

-- Verificar se há payment_transaction (novo na v3.x)
SELECT 
  id,
  payment_method,
  checkout_id,
  status,
  last_error,
  created_at
FROM `payment_transaction`
WHERE payment_method = 'pix'
ORDER BY created_at DESC
LIMIT 10;
```

### Resultado esperado:
- `checkout_status`: PENDING (aguardando pagamento)
- `checkout_id`: Não vazio (ex: `chk_abc123`)
- `method`: `pix`

---

## Teste 7: Atualizar Config Antes de Testar

⚠️ **IMPORTANTE:** Se está testando em ambiente local, edite config.php em:

```
c:\xampp\htdocs\dashboard\ChopponAndroid\php\includes\config.php
```

E altere o email para seu email de teste:

```php
// Para teste local - use seu email pessoal SumUp
define('SUMUP_PAY_TO_EMAIL', 'seu.email@dominio.com');
```

**Nunca deixe o email em branco ou inválido!**

---

## Checklist Final ✅

- [ ] `SUMUP_PAY_TO_EMAIL` foi adicionado a config.php
- [ ] Email está preenchido e é válido
- [ ] Log de teste PIX mostra HTTP 201 (não 500)
- [ ] Android carrega QRCODE com sucesso
- [ ] Pedido está registrado no banco com checkout_id

Se tudo acima está ✅, **A CORREÇÃO FUNCIONOU!**
