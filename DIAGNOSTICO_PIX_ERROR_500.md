# Diagnóstico: Erro 500 ao Criar Checkout PIX

**Data do Relatório:** 2026-04-24  
**Status:** 🔴 **PROBLEMA IDENTIFICADO E SOLUÇÃO PRONTA**

---

## 1. PROBLEMA IDENTIFICADO

### Sintomas (do Log):
```
2026-04-24 17:12:58.935  📦 create_payment resposta HTTP 500: 
{
  "success": false,
  "error": "Erro ao criar checkout PIX. Verifique a configuração SumUp e os logs do servidor.",
  "error_type": "PIX_CHECKOUT_FAILED"
}
```

### Fluxo da Falha:
```
FormaPagamento.java (Android)
    ↓ enviar dados do pagamento
create_payment.php
    ↓ chamada: $sumup->createCheckoutPix($orderData)
SumUpIntegration::createCheckoutPix()
    ↓ httpPost() → API SumUp
SumUp Cloud API
    ↓ REJEITA a requisição (HTTP 500)
    ↓ razão: pay_to_email vazio ou ausente
SumUpIntegration::createCheckoutPix()
    ↓ retorna FALSE
create_payment.php
    ↓ echo HTTP 500 com erro_type PIX_CHECKOUT_FAILED
FormaPagamento.java (Android)
    ↓ exibe erro: QRCODE não carrega
```

---

## 2. CAUSA RAIZ

### O Problema Principal:
No arquivo [php/includes/config.php](php/includes/config.php), as constantes SumUp estão incompletas:

**Linha 70-73 (ATUAL - INCOMPLETO):**
```php
define('SUMUP_TOKEN', 'sup_sk_8vNpSEJPVudqJrWPdUlomuE3EfVofw1bL');
define('SUMUP_MERCHANT_CODE', 'MCTSYDUE');
define('SUMUP_CHECKOUT_URL', 'https://api.sumup.com/v0.1/checkouts/');
define('SUMUP_MERCHANT_URL', 'https://api.sumup.com/v0.1/merchants/');
```

**Linha 76-77 (ATUAL - INCOMPLETO):**
```php
define('SUMUP_AFFILIATE_KEY',    'sup_afk_bULTbTDP0leInwIXud28LYYVmYiZiKYy');
define('SUMUP_AFFILIATE_APP_ID', 'CHOPPONALMEIDA');
```

### Falta: `SUMUP_PAY_TO_EMAIL` ❌

Ao criar um checkout PIX, a SumUp **exige obrigatoriamente** o campo `pay_to_email` (email para receber os valores).

**Código em [php/includes/sumup.php](php/includes/sumup.php) linha 181:**
```php
$body = [
    'checkout_reference' => 'CHOPPON-PIX-' . $order['id'] . '-' . time(),
    'amount'             => (float) $order['valor'],
    'currency'           => 'BRL',
    'description'        => $order['descricao'] ?? 'Pagamento ChoppOn PIX',
    'pay_to_email'       => defined('SUMUP_PAY_TO_EMAIL') ? SUMUP_PAY_TO_EMAIL : '',  // ← VAZIO!
];
```

Quando `SUMUP_PAY_TO_EMAIL` não está definido:
- A SumUp recebe: `"pay_to_email": ""`  
- A API válida rejeita: **HTTP 400/500**
- `createCheckoutPix()` retorna: `false`
- O Android recebe HTTP 500 com erro

---

## 3. SOLUÇÃO

### Passo 1: Adicionar a Constante em config.php

Adicionar a seguinte linha após `SUMUP_AFFILIATE_APP_ID`:

```php
// Email SumUp para receber pagamentos PIX
define('SUMUP_PAY_TO_EMAIL', 'seu.email@sumup.com');  // ← REQUERE EMAIL VÁLIDO
```

### Resumo das Constantes Necessárias:

| Constante | Valor Exemplo | Obrigatória? | Origem |
|-----------|-------|---|---|
| `SUMUP_TOKEN` | `sup_sk_...` | ✅ SIM | SumUp Profile |
| `SUMUP_MERCHANT_CODE` | `MCTSYDUE` | ✅ SIM | SumUp Profile |
| `SUMUP_AFFILIATE_KEY` | `sup_afk_...` | ✅ SIM (para Cloud API) | me.sumup.com > Developer Keys |
| `SUMUP_AFFILIATE_APP_ID` | `CHOPPONALMEIDA` | ✅ SIM (para Cloud API) | me.sumup.com > Developer Keys |
| `SUMUP_PAY_TO_EMAIL` | `financeiro@choppon.com.br` | ✅ SIM (para PIX) | Email autorizado SumUp |

---

## 4. ONDE ENCONTRAR O EMAIL

### Opção A: Email de Login SumUp
Use o email associado à conta SumUp que configurou o token.

### Opção B: Verificar na SumUp
1. Acesse: https://me.sumup.com
2. Faça login
3. Vá em **Settings > Payment Methods > PIX**
4. Veja o email configurado para receber

### Opção C: Usar Email Empresarial
Use o email administrador/financeiro da sua empresa:
- `financeiro@choppon.com.br`
- `admin@choppon.com.br`

---

## 5. CHECKLIST DE CONFIGURAÇÃO

- [ ] Adicionar `SUMUP_PAY_TO_EMAIL` a [php/includes/config.php](php/includes/config.php)
- [ ] Garantir que o email seja válido e vinculado à conta SumUp
- [ ] Testar chamada de pagamento PIX novamente
- [ ] Monitorar logs em `/php/logs/` para confirmar sucesso

---

## 6. VERIFICAÇÃO

### Após Aplicar a Correção:

✅ O arquivo config.php conterá todas as 5 constantes SumUp obrigatórias  
✅ A API `create_payment.php` receberá `pay_to_email` válido  
✅ `SumUpIntegration::createCheckoutPix()` enviará requisição completa para SumUp  
✅ SumUp responderá HTTP 200-201 em vez de 500  
✅ O QRCODE será gerado e exibido no Android ✅

---

## 7. LOGS ESPERADOS APÓS CORREÇÃO

Se configurado corretamente, você verá:

```
📋 SumUp createCheckoutPix - enviando
   url: https://api.sumup.com/v0.1/checkouts
   valor: 50.00
   order_id: 1234
   
✅ SumUp createCheckoutPix - resposta
   http_code: 201
   body_short: {"id":"chk_...","status":"PENDING","transaction_code":"00020126360..."}
   
📦 generateQRCode: sucesso
   size_bytes: 12543
```

---

## 8. REFERÊNCIAS

- [SumUp Cloud API - PIX Checkout](https://developer.sumup.com/terminal-payments/cloud-api/#create-checkout)
- [SumUp Developer Settings](https://me.sumup.com/settings/developer)
- Código: [php/includes/sumup.php](php/includes/sumup.php) linhas 165-228
- Código: [php/api/create_payment.php](php/api/create_payment.php) linhas 315-340
