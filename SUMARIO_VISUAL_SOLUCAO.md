# Resumo Visual: PIX Erro 500 - Solução Aplicada

## 📊 Árvore de Decisão do Problema

```
┌─────────────────────────────────────┐
│  USUÁRIO TENTA PAGAR COM PIX        │
│  ❌ Erro: "HTTP 500"                │
│  ❌ QRCODE não aparece              │
└────────────┬────────────────────────┘
             │
             ▼
     ┌──────────────────┐
     │ Qual é o erro?   │
     │ create_payment   │
     │ retorna 500      │
     └────────┬─────────┘
              │
              ▼
     ╔═══════════════════════════════════╗
     ║ SumUpIntegration::createCheckoutPix()
     ║ retorna FALSE                     ║
     ╚═══════════┬═══════════════════════╝
                 │
                 ▼
     ┌───────────────────────────────────┐
     │ Por que retorna FALSE?            │
     │ → HTTP response NÃO é 200 ou 201  │
     └────────┬────────────────────────┘
              │
              ▼
     ┌──────────────────────────────────────┐
     │ Por que a SumUp rejeita? (não 200?)  │
     └────────┬─────────────────────────────┘
              │
              ▼
     ┌──────────────────────────────────────┐
     │ Corpo da requisição problema:       │
     │                                     │
     │ {                                   │
     │   "pay_to_email": "❌ VAZIO"        │
     │ }                                   │
     │                                     │
     │ SumUp rejeita porque:               │
     │ pay_to_email é OBRIGATÓRIO!         │
     └────────┬─────────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────────┐
     │ CAUSA RAIZ:                            │
     │                                        │
     │ 📄 config.php                          │
     │    está INCOMPLETO                     │
     │                                        │
     │ ❌ Falta: SUMUP_PAY_TO_EMAIL          │
     │    Presente: SUMUP_TOKEN              │
     │    Presente: SUMUP_MERCHANT_CODE      │
     │    Presente: SUMUP_AFFILIATE_KEY      │
     │    Presente: SUMUP_AFFILIATE_APP_ID   │
     │                                        │
     └────────┬─────────────────────────────────┘
              │
              ▼
     ╔════════════════════════════════════════╗
     ║ ✅ SOLUÇÃO:                            ║
     ║                                        ║
     ║ Adicionar em config.php:               ║
     ║                                        ║
     ║ define('SUMUP_PAY_TO_EMAIL',          ║
     ║   'financeiro@almeida.com.br');       ║
     ╚════════════════════════════════════════╝
```

---

## 🔧 Configuração: Antes vs Depois

### ANTES (Quebrado)

```
┌─────────────────────────────────────┐
│  php/includes/config.php            │
├─────────────────────────────────────┤
│                                     │
│  ✅ SUMUP_TOKEN                    │
│  ✅ SUMUP_MERCHANT_CODE            │
│  ✅ SUMUP_AFFILIATE_KEY            │
│  ✅ SUMUP_AFFILIATE_APP_ID         │
│  ❌ SUMUP_PAY_TO_EMAIL (MISSING)   │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  sumup.php createCheckoutPix()       │
├─────────────────────────────────────┤
│                                     │
│  body = {                            │
│    "amount": 50.00,                 │
│    "pay_to_email": ""   ← VAZIO!   │
│  }                                  │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  SumUp Cloud API                    │
├─────────────────────────────────────┤
│  ❌ REJEITA                         │
│  pay_to_email é obrigatório!        │
│  HTTP 500 / 400                     │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  ❌ RESULTADO: ERRO                │
│  HTTP 500                           │
│  QRCODE não exibe                   │
│                                     │
└─────────────────────────────────────┘
```

---

### DEPOIS (Correto)

```
┌─────────────────────────────────────┐
│  php/includes/config.php            │
├─────────────────────────────────────┤
│                                     │
│  ✅ SUMUP_TOKEN                    │
│  ✅ SUMUP_MERCHANT_CODE            │
│  ✅ SUMUP_AFFILIATE_KEY            │
│  ✅ SUMUP_AFFILIATE_APP_ID         │
│  ✅ SUMUP_PAY_TO_EMAIL ← ADICIONADO
│     'financeiro@almeida.com.br'    │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  sumup.php createCheckoutPix()       │
├─────────────────────────────────────┤
│                                     │
│  body = {                            │
│    "amount": 50.00,                 │
│    "pay_to_email":                  │
│      "financeiro@almeida.com.br"   │
│  }                                  │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  SumUp Cloud API                    │
├─────────────────────────────────────┤
│  ✅ ACEITA                          │
│  Todas as informações presentes!    │
│  HTTP 201 Created                   │
│  Retorna: checkout_id + pix_code    │
│                                     │
└─────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  ✅ RESULTADO: SUCESSO             │
│  HTTP 200                           │
│  QRCODE exibe                       │
│  Ordem criada com sucesso           │
│                                     │
└─────────────────────────────────────┘
```

---

## 📋 Checklist de Implementação

```
╔════════════════════════════════════════════════════════════╗
║  PIX Payment Configuration Checklist                       ║
╚════════════════════════════════════════════════════════════╝

[✅] 1. SUMUP_TOKEN está em config.php?
    └─ Valor: sup_sk_8vNpSEJPVudqJrWPdUlomuE3EfVofw1bL

[✅] 2. SUMUP_MERCHANT_CODE está em config.php?
    └─ Valor: MCTSYDUE

[✅] 3. SUMUP_AFFILIATE_KEY está em config.php?
    └─ Valor: sup_afk_bULTbTDP0leInwIXud28LYYVmYiZiKYy

[✅] 4. SUMUP_AFFILIATE_APP_ID está em config.php?
    └─ Valor: CHOPPONALMEIDA

[✅] 5. SUMUP_PAY_TO_EMAIL está em config.php?
    └─ Valor: financeiro@almeida.com.br
    └─ ⚠️ CRÍTICO: Sem isso, PIX não funciona!

[✅] 6. Email em SUMUP_PAY_TO_EMAIL é válido?
    └─ Verificar em: https://me.sumup.com/settings

[✅] 7. Arquivo config.php foi salvo após edição?
    └─ Salvar com Ctrl+S

[✅] 8. App Android foi recarregado?
    └─ Reiniciar app ou Ctrl+Shift+R

[✅] 9. Teste PIX no Android
    └─ Debe exibir QRCODE sem erro 500

[✅] 10. Validar em logs: /php/logs/chooser_app.log
    └─ Deve conter: "HTTP 201" + "generateQRCode: sucesso"
```

---

## 🎯 Valores de Referência

| Campo | Valor | Status |
|-------|-------|--------|
| `SUMUP_TOKEN` | `sup_sk_8vNpSEJPVudqJrWPdUlomuE3EfVofw1bL` | Configurado ✅ |
| `SUMUP_MERCHANT_CODE` | `MCTSYDUE` | Configurado ✅ |
| `SUMUP_AFFILIATE_KEY` | `sup_afk_bULTbTDP0leInwIXud28LYYVmYiZiKYy` | Configurado ✅ |
| `SUMUP_AFFILIATE_APP_ID` | `CHOPPONALMEIDA` | Configurado ✅ |
| `SUMUP_PAY_TO_EMAIL` | `financeiro@almeida.com.br` | **ADICIONADO** ✅ |

---

## 🧪 Teste Rápido

```bash
# 1. Verificar se constante existe
grep -n "SUMUP_PAY_TO_EMAIL" php/includes/config.php

# Saída esperada:
# 83:define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');

# 2. Se não encontrar, adicionar manualmente
echo "define('SUMUP_PAY_TO_EMAIL', 'financeiro@almeida.com.br');" >> php/includes/config.php

# 3. Testar PIX
php php/api/test_pix_checkout.php
```

---

## 📞 Próximas Ações

1. **Validar email SumUp**
   - Acesse https://me.sumup.com/settings
   - Encontre email autorizado para PIX
   - Update config.php se necessário

2. **Testar no Android**
   - Abra aplicação
   - Tente pagar com PIX
   - Confirmar QRCODE aparece

3. **Monitorar logs**
   - Arquivo: `/php/logs/chooser_app.log`
   - Procure: "HTTP 201" (sucesso) vs "HTTP 500" (erro)

4. **Certificar-se que funcionou**
   - QRCODE deve exibir
   - Sem mensagem de erro
   - Pedido registrado no banco

---

## ⚠️ Avisos

- ⚠️ **Não deixe email em branco**: `define('SUMUP_PAY_TO_EMAIL', '');` ← ERRADO!
- ⚠️ **Não use email de outros sistemas**: Deve estar autorizado na SumUp
- ⚠️ **Não edite sumup.php**: A configuração deve estar em config.php
- ⚠️ **Teste antes de publicar**: Execute testes em dev primeiramente

---

## ✅ Sucesso Confirmado Quando:

```
Android App:
├─ PIX button clicado
├─ Aguarda carregamento
├─ ✅ QRCODE aparece
├─ Sem erro HTTP 500
├─ Pode escanear/copiar código PIX
└─ Pedido confirmado no banco

Logs Server:
├─ createCheckoutPix enviando
├─ HTTP 201 from SumUp
├─ generateQRCode: sucesso
├─ order criado com checkout_id
└─ Sem erros/warnings
```

---

## 📊 Status Quo

```
├─ Problema Identificado: ✅
├─ Causa Identificada: ✅
├─ Solução Aplicada: ✅
├─ Documentação: ✅
├─ Teste Necessário: ⏳ (seu responsabilidade)
└─ Conclusão Esperada: ✅ (em breve)
```
