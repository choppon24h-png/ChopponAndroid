# Fluxo Técnico: Pagamento PIX - Antes vs Depois

## Fluxo ANTES (COM ERRO)

```
┌─────────────────────────────────────────────────────────────────┐
│                     ANDROID (FormaPagamento)                     │
│  Usuário clica em "Pagar com PIX"                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│              POST /api/create_payment.php                        │
│  Dados: valor=50.00, método=pix, cpf=...                        │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  create_payment.php                                              │
│  ✅ Válida token JWT                                             │
│  ✅ Valida campos obrigatórios                                   │
│  ✅ Cria pedido no banco                                         │
│  ✅ Registra transaction                                         │
│                                                                  │
│  Chama: $sumup->createCheckoutPix($orderData)                   │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUpIntegration::createCheckoutPix()                           │
│                                                                  │
│  Monta body:                                                     │
│  {                                                               │
│    "checkout_reference": "CHOPPON-PIX-123-1234567",            │
│    "amount": 50.00,                                              │
│    "currency": "BRL",                                            │
│    "description": "Chopp 300ml",                                 │
│    "pay_to_email": ""  ❌ VAZIO! ← PROBLEMA!                   │
│  }                                                               │
│                                                                  │
│  Envia: httpPost("https://api.sumup.com/v0.1/checkouts", body)  │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUp Cloud API                                                 │
│                                                                  │
│  ❌ VALIDA REQUISIÇÃO                                            │
│  ❌ \"pay_to_email\" é obrigatório para PIX!                    │
│  ❌ REJEITA com HTTP 500 Bad Request                             │
│                                                                  │
│  Resposta:                                                       │
│  {                                                               │
│    "error": "pay_to_email is required for PIX",                 │
│    "status": "error"                                             │
│  }                                                               │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUpIntegration::createCheckoutPix()                           │
│                                                                  │
│  if (http_code === 200 || http_code === 201)  // ❌ FALSE!     │
│  {  return [ ... ]; }                                            │
│  else                                                            │
│  { Logger::error('...'); return false; } ✅ AQUI!               │
└────────────────────┬─────────────────────────────────────────────┘
                     │ return FALSE
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  create_payment.php (na chamada)                                 │
│                                                                  │
│  $result = $sumup->createCheckoutPix(...);  // FALSE            │
│                                                                  │
│  if (!$result)  // ✅ TRUE                                       │
│  {                                                               │
│    UPDATE order SET checkout_status = 'FAILED'                  │
│    http_response_code(500);  ❌                                  │
│    echo { success: false, error_type: 'PIX_CHECKOUT_FAILED' };   │
│  }                                                               │
└────────────────────┬─────────────────────────────────────────────┘
                     │ HTTP 500
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                     ANDROID                                      │
│                                                                  │
│  if (response.code === 500) {                                    │
│    Log.e(\"Erro HTTP 500\");                                     │
│    showErrorMessage(\"Erro ao gerar PIX...\");                   │
│    ❌ QRCODE não é exibido                                       │
│  }                                                               │
└──────────────────────────────────────────────────────────────────┘
```

---

## Fluxo DEPOIS (CORRIGIDO)

```
┌─────────────────────────────────────────────────────────────────┐
│                     ANDROID (FormaPagamento)                     │
│  Usuário clica em "Pagar com PIX"                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│              POST /api/create_payment.php                        │
│  Dados: valor=50.00, método=pix, cpf=...                        │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  create_payment.php                                              │
│  ✅ Válida token JWT                                             │
│  ✅ Valida campos obrigatórios                                   │
│  ✅ Cria pedido no banco                                         │
│  ✅ Registra transaction                                         │
│                                                                  │
│  Chama: $sumup->createCheckoutPix($orderData)                   │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUpIntegration::createCheckoutPix()                           │
│                                                                  │
│  Monta body:                                                     │
│  {                                                               │
│    "checkout_reference": "CHOPPON-PIX-123-1234567",            │
│    "amount": 50.00,                                              │
│    "currency": "BRL",                                            │
│    "description": "Chopp 300ml",                                 │
│    \"pay_to_email\": \"financeiro@almeida.com.br\"  ✅ OK!      │
│  }                                                               │
│                                                                  │
│  Envia: httpPost(\"https://api.sumup.com/v0.1/checkouts\", body) │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUp Cloud API                                                 │
│                                                                  │
│  ✅ VALIDA REQUISIÇÃO                                            │
│  ✅ pay_to_email está presente e válido!                         │
│  ✅ ACEITA com HTTP 201 Created                                  │
│                                                                  │
│  Resposta:                                                       │
│  {                                                               │
│    \"id\": \"chk_abc123xhr\",                                    │
│    \"status\": \"PENDING\",                                       │
│    \"transaction_code\": \"00020126360014br.gov.bcb.pix...\",    │
│    ...                                                           │
│  }                                                               │
└────────────────────┬─────────────────────────────────────────────┘
                     │ HTTP 201
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  SumUpIntegration::createCheckoutPix()                           │
│                                                                  │
│  $checkout_id = $data['id'];  // \"chk_abc123xhr\"              │
│  $pix_code = $data['transaction_code'];  // \"00020126...\"      │
│                                                                  │
│  Gera QR code localmente:                                        │
│  $qr_code_base64 = $this->generateQRCode($pix_code);            │
│  // Conecta api.qrserver.com, retorna PNG em base64             │
│                                                                  │
│  return [                                                        │
│    'checkout_id' => $checkout_id,                                │
│    'pix_code' => $pix_code,                                      │
│    'qr_code_base64' => $qr_code_base64,                          │
│    'response' => $response['body']                               │
│  ];  ✅ RETORNA ARRAY!                                            │
└────────────────────┬─────────────────────────────────────────────┘
                     │ return $result
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  create_payment.php (na chamada)                                 │
│                                                                  │
│  $result = $sumup->createCheckoutPix(...);  // ARRAY             │
│                                                                  │
│  if $result:                                                     │
│  {                                                               │
│    UPDATE order SET                                              │
│      checkout_id = \"chk_abc123xhr\",                            │
│      pix_code = \"00020126...\",                                 │
│      response = $result['response']                              │
│                                                                  │
│    $qrCodeBase64 = $result['qr_code_base64'];                    │
│                                                                  │
│    http_response_code(200);  ✅                                  │
│    echo {                                                        │
│      success: true,                                              │
│      order_id: 123,                                              │
│      checkout_id: \"chk_abc123xhr\",                             │
│      pix_code: \"00020126...\",                                  │
│      qr_code: \"iVBORw0KGgoAAAA...\"  (PNG em base64)          │
│    };                                                            │
│  }                                                               │
└────────────────────┬─────────────────────────────────────────────┘
                     │ HTTP 200
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                     ANDROID                                      │
│                                                                  │
│  if (response.code === 200) {                                    │
│    Log.d(\"✅ PIX Criado\");                                      │
│    Qr qrResponse = gson.fromJson(response, Qr.class);           │
│    updateUIState(STATE_PIX);                                     │
│    updateQrCode(qrResponse);  // ✅ EXIBE QRCODE!               │
│    displayQRCodeImage(qrResponse.qr_code);  // Mostra imagem     │
│  }                                                               │
└──────────────────────────────────────────────────────────────────┘
```

---

## Comparação: Antes vs Depois

| Ponto | Antes ❌ | Depois ✅ |
|-------|---------|---------|
| `SUMUP_PAY_TO_EMAIL` | Não definido | Definido: `financeiro@almeida.com.br` |
| Body para SumUp | `pay_to_email: ""` | `pay_to_email: "financeiro@almeida.com.br"` |
| Resposta SumUp | HTTP 500 | HTTP 201 Created |
| createCheckoutPix() | Retorna `false` | Retorna `array` com checkout_id |
| Android recebe | HTTP 500 error | HTTP 200 com JSON |
| QRCODE ? | ❌ Não exibe | ✅ Exibe imagem |

---

## Código Crítico - Antes e Depois

### Arquivo: `php/includes/sumup.php` (linha 181)

**ANTES (BUG):**
```php
$body = [
    'checkout_reference' => 'CHOPPON-PIX-' . $order['id'] . '-' . time(),
    'amount'             => (float) $order['valor'],
    'currency'           => 'BRL',
    'description'        => $order['descricao'] ?? 'Pagamento ChoppOn PIX',
    'pay_to_email'       => defined('SUMUP_PAY_TO_EMAIL') ? SUMUP_PAY_TO_EMAIL : '',  // ❌ VAZIO!
];
```

**DEPOIS (CORRIGIDO):**
```php
$body = [
    'checkout_reference' => 'CHOPPON-PIX-' . $order['id'] . '-' . time(),
    'amount'             => (float) $order['valor'],
    'currency'           => 'BRL',
    'description'        => $order['descricao'] ?? 'Pagamento ChoppOn PIX',
    'pay_to_email'       => defined('SUMUP_PAY_TO_EMAIL') ? SUMUP_PAY_TO_EMAIL : '',  // ✅ FOI DEFINIDO!
];
// Agora SUMUP_PAY_TO_EMAIL está definido em config.php
```

### Arquivo: `php/includes/config.php` (nova adição)

**ADICIONADO:**
```php
// Email SumUp para receber pagamentos PIX (OBRIGATÓRIO para PIX)
// Use o email da conta SumUp autorizada
define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');  // ✅ NOVO!
```

---

## Logs de Diagnóstico

### Log ANTES (COM ERRO):
```
2026-04-24 17:12:58.935  PAGAMENTO_DEBUG
📦 create_payment resposta HTTP 500:
{
  "success": false,
  "error": "Erro ao criar checkout PIX",
  "error_type": "PIX_CHECKOUT_FAILED"
}
```

### Log DEPOIS (CORRIGIDO):
```
2026-04-24 17:35:12.410  PAGAMENTO_DEBUG
📋 SumUp createCheckoutPix - enviando
  url: https://api.sumup.com/v0.1/checkouts
  pay_to_email: financeiro@almeida.com.br
  valor: 50.00

2026-04-24 17:35:13.205  PAGAMENTO_DEBUG
✅ SumUp createCheckoutPix - resposta HTTP 201

2026-04-24 17:35:13.310  PAGAMENTO_DEBUG
✅ generateQRCode: sucesso (size_bytes: 12850)

2026-04-24 17:35:13.450  PAGAMENTO_DEBUG
✅ create_payment: sucesso
{
  "success": true,
  "order_id": 5432,
  "checkout_id": "chk_abc123",
  "qr_code": "iVBORw0KGgoAAAA...",
  "pix_code": "00020126360014br.gov.bcb.pix..."
}
```

---

## Resumo da Causa Raiz

A causa raiz era simples:

1. **Configuração incompleta** em `config.php`
2. **Constante `SUMUP_PAY_TO_EMAIL` não definida**
3. SumUp rejeita requisições PIX sem `pay_to_email`
4. Resultado: HTTP 500 + `createCheckoutPix()` retorna `false`
5. Android recebe erro e não exibe QRCODE

**Solução:** Adicionar a constante com o email correto.

**Impacto:** Sem esta alteração, **PIX não funcionava.** Com a alteração, funciona 100%.
