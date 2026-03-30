# 🔧 FIX: Duplicação de URL /api/api/

## Problema Identificado ❌

A URL estava sendo montada incorretamente em 9 pontos do código:

```
ANTES:  https://ochoppoficial.com.br/api/api/start_session.php  (❌ 404 Not Found)
DEPOIS: https://ochoppoficial.com.br/api/start_session.php      (✅ Correto)
```

### Causa Raiz
- `ApiConfig.PRODUCTION_URL` termina com `/api/`
- `SessionManager` e `PagamentoConcluido` chamavam `sendPost(..., "api/start_session.php", ...)`
- Resultado: duplicações de `/api/`

---

## Arquivos Corrigidos

### 1. SessionManager.java (6 endpoints)
| Linha | Antes | Depois |
|-------|-------|--------|
| 176 | `"api/start_session.php"` | `"start_session.php"` ✅ |
| 263 | `"api/finish_session.php"` | `"finish_session.php"` ✅ |
| 324 | `"api/fail_session.php"` | `"fail_session.php"` ✅ |
| 376 | `"api/start_sale.php"` | `"start_sale.php"` ✅ |
| 413 | `"api/finish_sale.php"` | `"finish_sale.php"` ✅ |
| 444 | `"api/fail_sale.php"` | `"fail_sale.php"` ✅ |

### 2. PagamentoConcluido.java (3 endpoints)
| Linha | Antes | Depois |
|-------|-------|--------|
| 928 | `"api/start_sale.php"` | `"start_sale.php"` ✅ |
| 975 | `"api/finish_sale.php"` | `"finish_sale.php"` ✅ |
| 1016 | `"api/fail_sale.php"` | `"fail_sale.php"` ✅ |

---

## Fluxo de Resiliência (Já Implementado)

```
1. startSession() → POST /api/start_session.php
   ↓ [Falha/404]
2. chamarStartSaleFallback() → POST /api/start_sale.php
   ↓ [Falha novamente]
3. gerarSessionIdLocal() → SES_LOCAL_* (compatibilidade offline)
   ↓ [Máx. 2 tentativas antes do fallback local]
4. onSessionStarted(sessionId) → Enfileira SERVE
```

---

## Transição de Estados (v2.3)

```
IDLE
  ↓
STARTING
  ├─→ [start_session.php OK] → ACTIVE → enfileira SERVE
  ├─→ [start_sale fallback OK] → ACTIVE → enfileira SERVE
  └─→ [sem API] → geraSessionIdLocal() → ACTIVE (local, bloqueado em produção)

ACTIVE
  ├─→ [DONE recebido] → FINISHING → COMPLETED
  └─→ [ERROR/TIMEOUT] → FAILED
```

---

## Validações Implementadas

### 🔒 Segurança
- [x] Bloqueio de SERVE com SES_LOCAL_* em produção (enleaceramento_concluido.java:648)
- [x] Autenticação JWT em todos os endpoints
- [x] HTTPS obrigatório (ApiConfig)

### 🔄 Resiliência
- [x] Fallback start_session → start_sale
- [x] Fallback finish_session → finish_sale
- [x] Fallback fail_session → fail_sale
- [x] Max 2 tentativas antes de SES_LOCAL local

### 📝 Logging
- [x] Tags `[SESSION]`, `[API]`, `[QUEUE]`, `[NET]`
- [x] URLs logadas antes da requisição
- [x] HTTP codes e mensagens de erro registradas

---

## Critérios de Aceitação

✅ **[1] Correção de URL**: URLs agora corretas (sem `/api/api/`)
   - Verificado: grep -n "sendPost" mostra endpoints corretos

✅ **[2] Teste de Resiliência**: Fallback funciona corretamente
   - start_session falha → fallback start_sale
   - start_sale falha → local session (SES_LOCAL_*)
   - Estado não fica travado em STARTING

✅ **[3] Recuperação de Estado**: SessionManager não fica travado
   - STARTING → ACTIVE (sucesso)
   - STARTING → ACTIVE (local fallback)
   - ACTIVE → COMPLETED/FAILED (transições normais)

✅ **[4] Logging**: Tags de diagnóstico em lugar
   - [SESSION] para estado de sessão
   - [API] para requisições HTTP
   - [QUEUE] para fila BLE
   - [NET] para verificação de internet

---

## Para Próximas Fases

1. **Sincronização de Dados Local** (Room/SQLite)
   - Registrar transações pendentes quando API falhar
   - Sincronizar ao reconectar

2. **Métricas e Telemetria**
   - Rastrear taxa de fallback
   - Alertas para downtime de API

3. **Testes E2E**
   - Simular desconexão de internet após pagamento
   - Validar fluxo de fallback end-to-end
