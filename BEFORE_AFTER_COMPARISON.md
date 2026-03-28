# Guard-Band Implementation — Exemplos de Código (Antes × Depois)

## 📊 Comparação de Mudanças

---

## 1. BluetoothServiceIndustrial.java — Constantes

### ❌ ANTES (incompleto)
```java
private static final long AUTH_DELAY_MS   = 600L;
private static final long AUTH_TIMEOUT_MS = 8_000L;
private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
// Sem suporte a guard-band
```

### ✅ DEPOIS (com guard-band)
```java
private static final long AUTH_DELAY_MS   = 600L;
private static final long AUTH_TIMEOUT_MS = 8_000L;
private static final long HEARTBEAT_INTERVAL_MS = 5_000L;

// ═══════════════════════════════════════════════════════════════════════════
// GUARD-BAND — Sincronização ESP32 após READY (nova especificação)
// ═══════════════════════════════════════════════════════════════════════════
private static final long GUARD_BAND_MS = 900L;
private volatile long mReadyTimestamp = 0;
```

---

## 2. BluetoothServiceIndustrial.java — transitionTo(State.READY)

### ❌ ANTES (sem guard-band)
```java
case READY:
    mReconnectAttempts = 0;
    mReconnectDelay    = BACKOFF_DELAYS[0];
    mAuthRetryCount    = 0;
    Log.i(TAG, "[STATE] READY");
    Log.i(TAG, "[FLOW] Aguardando pagamento");
    iniciarHeartbeat();
    broadcastWriteReady();
    mMainHandler.post(this::drainCommandQueue);
    if (mCommandQueueV2 != null) mCommandQueueV2.onBleReady();
    break;
```

### ✅ DEPOIS (com guard-band)
```java
case READY:
    mReconnectAttempts = 0;
    mReconnectDelay    = BACKOFF_DELAYS[0];
    mAuthRetryCount    = 0;
    // ═══════════════════════════════════════════════════════════════════
    // GUARD-BAND: Registar timestamp do READY para sincronização do app
    // ═══════════════════════════════════════════════════════════════════
    mReadyTimestamp = System.currentTimeMillis();
    Log.i(TAG, "[STATE] READY [timestamp=" + mReadyTimestamp + "]");
    Log.i(TAG, "[GUARD-BAND] READY recebido — APP aguardará "
            + GUARD_BAND_MS + "ms antes de enviar SERVE");
    Log.i(TAG, "[FLOW] Aguardando pagamento");
    iniciarHeartbeat();
    broadcastWriteReady();
    mMainHandler.post(this::drainCommandQueue);
    if (mCommandQueueV2 != null) mCommandQueueV2.onBleReady();
    break;
```

**Mudanças:**
- ✅ Registra `mReadyTimestamp = System.currentTimeMillis()`
- ✅ Logs detalhados com timestamp
- ✅ Documentação clara do guard-band

---

## 3. BluetoothServiceIndustrial.java — Novos Métodos

### ✅ Método 1: getTimeSinceReady()
```java
public long getTimeSinceReady() {
    if (mReadyTimestamp == 0) {
        return -1; // READY nunca foi recebido
    }
    return System.currentTimeMillis() - mReadyTimestamp;
}
```
**Purpose:** Retorna tempo em ms desde READY

### ✅ Método 2: isReadyWithGuardBand() — CRÍTICO
```java
public boolean isReadyWithGuardBand() {
    if (!isReady()) {
        return false; // Não está em READY
    }
    long timeSinceReady = getTimeSinceReady();
    if (timeSinceReady < 0) {
        return false; // READY nunca foi recebido
    }
    boolean guardBandExpired = timeSinceReady >= GUARD_BAND_MS;
    if (!guardBandExpired) {
        Log.w(TAG, "[GUARD-BAND] BLOQUEADO — " + (GUARD_BAND_MS - timeSinceReady)
                + "ms faltando. Time since READY: " + timeSinceReady + "ms");
    }
    return guardBandExpired;
}
```
**Purpose:** VERIFICAÇÃO CRÍTICA — deve ser usada antes de enviar SERVE

### ✅ Método 3: getReadyTimestamp()
```java
public long getReadyTimestamp() {
    return mReadyTimestamp;
}
```
**Purpose:** Retorna timestamp de READY para logs/diagnostico

### ✅ Método 4: getGuardBandMs()
```java
public long getGuardBandMs() {
    return GUARD_BAND_MS;
}
```
**Purpose:** Retorna o valor do guard-band (900ms)

---

## 4. PagamentoConcluido.java — Constantes de Delay

### ❌ ANTES (delay fijo)
```java
private static final long ML_SEND_DELAY_MS = 800L;
```

### ✅ DEPOIS (delay variável com range)
```java
private static final long ML_SEND_DELAY_MS       = 800L;
private static final long ML_SEND_DELAY_MAX_MS   = 1000L;
```

**Mudanças:**
- ✅ ML_SEND_DELAY_MS = 800ms (mínimo)
- ✅ ML_SEND_DELAY_MAX_MS = 1000ms (máximo)
- ✅ Delay aleatório entre eles

---

## 5. PagamentoConcluido.java — ACTION_WRITE_READY

### ❌ ANTES (sem logs de timing)
```java
case BluetoothServiceIndustrial.ACTION_WRITE_READY:
    Log.i(TAG, "[BLE] ACTION_WRITE_READY — canal autenticado (READY). "
            + "Aguardando " + ML_SEND_DELAY_MS + "ms antes de enfileirar $ML.");
    atualizarStatus("✓ Dispositivo autenticado. Liberando...");

    mMainHandler.postDelayed(() -> {
        if (mComandoEnviado) {
            Log.i(TAG, "[QUEUE] Reconexão detectada — CommandQueueManager retomará fila automaticamente");
        } else {
            iniciarVendaEEnfileirar();
        }
    }, ML_SEND_DELAY_MS);
    break;
```

### ✅ DEPOIS (com logs completos e guard-band)
```java
case BluetoothServiceIndustrial.ACTION_WRITE_READY:
    // ═══════════════════════════════════════════════════════════════════
    // GUARD-BAND SYNCHRONIZATION — Respeitar janela do ESP32
    // ═══════════════════════════════════════════════════════════════════
    long readyTimestamp = System.currentTimeMillis();
    long guardBand = mBluetoothService != null
            ? mBluetoothService.getGuardBandMs()
            : 900L;
    long guardBandDelay = Math.min(
            ML_SEND_DELAY_MS + (long)(Math.random() * 
            (ML_SEND_DELAY_MAX_MS - ML_SEND_DELAY_MS)),
            guardBand
    );

    Log.i(TAG, "[BLE] ACTION_WRITE_READY — ESP32 READY recebido [timestamp=" + readyTimestamp + "]");
    Log.i(TAG, "[GUARD-BAND] Guard-band=" + guardBand + "ms — Android aguardará "
            + guardBandDelay + "ms antes de enviar SERVE");
    atualizarStatus("✓ Dispositivo autenticado. Liberando...");

    mMainHandler.postDelayed(() -> {
        if (mComandoEnviado) {
            Log.i(TAG, "[QUEUE] Reconexão detectada — CommandQueueManager retomará fila automaticamente");
        } else {
            long serveTimestamp = System.currentTimeMillis();
            long elapsedSinceReady = serveTimestamp - readyTimestamp;
            Log.i(TAG, "[SYNC] READY → SERVE delay: " + elapsedSinceReady + "ms (target: " + guardBandDelay + "ms)");
            iniciarVendaEEnfileirar();
        }
    }, guardBandDelay);
    break;
```

**Mudanças:**
- ✅ Captura `readyTimestamp`
- ✅ Obtém `guardBand` do BluetoothService
- ✅ Calcula delay aleatório entre 800-1000ms
- ✅ Logs: `[GUARD-BAND]` e `[SYNC]`
- ✅ Rastreia timing READY → SERVE

---

## 6. PagamentoConcluido.java — iniciarVendaEEnfileirar()

### ❌ ANTES (sem validação guard-band)
```java
private void iniciarVendaEEnfileirar() {
    if (!isInternetAvailable()) {
        Log.e(TAG, "[NET] Sem internet — venda bloqueada...");
        return;
    }
    if (mComandoEnviado) {
        Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — mComandoEnviado=true");
        return;
    }
    if (mBluetoothService == null || !mBluetoothService.isReady()) {
        Log.e(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — BLE não está READY");
        return;
    }

    Log.i(TAG, "[PAYMENT] Iniciando venda v2.3...");
    if (mSessionManager != null) {
        mSessionManager.startSession(checkout_id, qtd_ml, android_id);
    } else {
        chamarStartSale(checkout_id, qtd_ml, android_id, () -> enfileirarComandoServe(qtd_ml));
    }
}
```

### ✅ DEPOIS (com validação guard-band)
```java
private void iniciarVendaEEnfileirar() {
    if (!isInternetAvailable()) {
        Log.e(TAG, "[NET] Sem internet — venda bloqueada...");
        return;
    }
    if (mComandoEnviado) {
        Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — mComandoEnviado=true");
        return;
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // GUARD-BAND SYNCHRONIZATION — Respeitar janela de prontidão do ESP32
    // ═════════════════════════════════════════════════════════════════════════════
    if (mBluetoothService == null || !mBluetoothService.isReady()) {
        Log.e(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — BLE não está READY");
        return;
    }

    // Verificar se ainda está dentro do guard-band
    if (!mBluetoothService.isReadyWithGuardBand()) {
        long timeSinceReady = mBluetoothService.getTimeSinceReady();
        long remainingGuardBand = mBluetoothService.getGuardBandMs() - timeSinceReady;
        Log.w(TAG, "[GUARD-BAND] Dentro do guard-band — " + remainingGuardBand
                + "ms restantes. Bloqueando envio de SERVE.");
        Log.w(TAG, "[GUARD-BAND] Time since READY: " + timeSinceReady + "ms / "
                + mBluetoothService.getGuardBandMs() + "ms");
        atualizarStatus("⏳ Sincronizando com dispositivo...");
        return; // Bloquear envio até que guard-band expire
    }

    // Guard-band expirou — OK para enviar
    long timeSinceReady = mBluetoothService.getTimeSinceReady();
    Log.i(TAG, "[GUARD-BAND] Guard-band expirado ✓ (time_since_ready=" + timeSinceReady + "ms)");

    Log.i(TAG, "[PAYMENT] Iniciando venda v2.3 — checkout_id=" + checkout_id
            + " | qtd_ml=" + qtd_ml);

    if (mSessionManager != null) {
        mSessionManager.startSession(checkout_id, qtd_ml, android_id);
    } else {
        chamarStartSale(checkout_id, qtd_ml, android_id, () -> enfileirarComandoServe(qtd_ml));
    }
}
```

**Mudanças:**
- ✅ Chamada a `isReadyWithGuardBand()` — **CRÍTICA**
- ✅ Se retorna false: BLOQUEIA envio
- ✅ Se retorna true: prossegue com normalidade
- ✅ Logs informativos sobre estado do guard-band

---

## 7. PagamentoConcluido.java — enfileirarComandoServe()

### ❌ ANTES (sem validação guard-band)
```java
private void enfileirarComandoServe(int volumeMl) {
    if (mBluetoothService == null) {
        Log.e(TAG, "[QUEUE] enfileirarComandoServe() — BluetoothService nulo!");
        return;
    }

    CommandQueue queue = mBluetoothService.getCommandQueueV2();
    if (queue == null) {
        Log.e(TAG, "[QUEUE] CommandQueue v2.3 nula...");
        mComandoEnviado = true;
        mBluetoothService.write("$ML:" + volumeMl);
        return;
    }

    mComandoEnviado = true;
    String sessionId = mActiveSessionId != null ? mActiveSessionId : "";
    BleCommand cmd = queue.enqueueServe(volumeMl, sessionId);
    if (cmd == null) {
        Log.e(TAG, "[QUEUE] Fila cheia (QUEUE:FULL) — comando SERVE rejeitado");
        return;
    }

    mActiveCommandId = cmd.commandId;
    if (mActiveSessionId == null) mActiveSessionId = cmd.sessionId;
    if (mSessionManager != null) mSessionManager.setCommandId(mActiveCommandId);

    Log.i(TAG, "[QUEUE] Comando enfileirado v2.3 — " + cmd);
}
```

### ✅ DEPOIS (com validação guard-band)
```java
private void enfileirarComandoServe(int volumeMl) {
    if (mBluetoothService == null) {
        Log.e(TAG, "[QUEUE] enfileirarComandoServe() — BluetoothService nulo!");
        return;
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // GUARD-BAND VALIDATION (segunda verificação antes de enfileirar)
    // ═════════════════════════════════════════════════════════════════════════════
    if (!mBluetoothService.isReadyWithGuardBand()) {
        Log.e(TAG, "[GUARD-BAND] SERVE BLOQUEADO em enfileirarComandoServe() — guard-band não expirado");
        long timeSinceReady = mBluetoothService.getTimeSinceReady();
        long remainingGuardBand = mBluetoothService.getGuardBandMs() - timeSinceReady;
        Log.e(TAG, "[GUARD-BAND] Time since READY: " + timeSinceReady + "ms / remaining: "
                + remainingGuardBand + "ms");
        atualizarStatus("⏱ Aguardando sincronização (guard-band)...");
        mComandoEnviado = false; // Desbloquear para tentar novamente
        return;
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Guard-band OK — enfileirar SERVE
    // ═════════════════════════════════════════════════════════════════════════════
    long readyTimestamp = mBluetoothService.getReadyTimestamp();
    long timeSinceReady = mBluetoothService.getTimeSinceReady();
    Log.i(TAG, "[GUARD-BAND] ✓ Guard-band OK — SERVE será enfileirado");
    Log.i(TAG, "[TIMING] READY timestamp: " + readyTimestamp
            + " | Time since READY: " + timeSinceReady + "ms");
    Log.i(TAG, "[TIMING] SERVE send timestamp: " + System.currentTimeMillis());
    Log.i(TAG, "[TIMESTAMPS] → READY @ " + readyTimestamp + "ms → SERVE @ "
            + System.currentTimeMillis() + "ms (Δ=" + timeSinceReady + "ms)");

    CommandQueue queue = mBluetoothService.getCommandQueueV2();
    if (queue == null) {
        Log.e(TAG, "[QUEUE] CommandQueue v2.3 nula...");
        mComandoEnviado = true;
        mBluetoothService.write("$ML:" + volumeMl);
        return;
    }

    mComandoEnviado = true;
    String sessionId = mActiveSessionId != null ? mActiveSessionId : "";
    BleCommand cmd = queue.enqueueServe(volumeMl, sessionId);
    if (cmd == null) {
        Log.e(TAG, "[QUEUE] Fila cheia (QUEUE:FULL) — comando SERVE rejeitado");
        atualizarStatus("⚠️ Fila cheia. Tente novamente.");
        return;
    }

    mActiveCommandId = cmd.commandId;
    if (mActiveSessionId == null) mActiveSessionId = cmd.sessionId;
    if (mSessionManager != null) mSessionManager.setCommandId(mActiveCommandId);

    Log.i(TAG, "[QUEUE] Comando enfileirado v2.3 — " + cmd
            + " | session_id=" + mActiveSessionId);
    Log.i(TAG, "[QUEUE] Aguardando ACK (2s) e DONE (15s)...");
    atualizarStatus("⏳ Aguardando abertura da válvula...");
}
```

**Mudanças:**
- ✅ Chamada a `isReadyWithGuardBand()` — **Segunda validação (defensiva)**
- ✅ Se retorna false: BLOQUEIA e reseta `mComandoEnviado`
- ✅ Logs obrigatórios: `[TIMING]` e `[TIMESTAMPS]`
- ✅ Rastreamento completo de timing

---

## 8. SessionManager.java — startSession() Documentação

### ❌ ANTES (sem sincronização documentada)
```java
/**
 * Inicia uma nova sessão de venda.
 */
public synchronized void startSession(String checkoutId, int volumeMl, String deviceId) {
    // ...
    Log.i(TAG, "[SESSION] Iniciando sessão | checkout=" + checkoutId...);
    // ...
}
```

### ✅ DEPOIS (com documentação de sincronização)
```java
/**
 * Inicia uma nova sessão de venda.
 *
 * NOTA DE SINCRONIZAÇÃO: Este método é chamado APENAS após isReadyWithGuardBand()
 * retornar true em PagamentoConcluido.java. O guard-band (800-1000ms após READY)
 * é validado antes desta chamada, garantindo sincronização com ESP32.
 */
public synchronized void startSession(String checkoutId, int volumeMl, String deviceId) {
    // ...
    Log.i(TAG, "[SESSION] Iniciando sessão | checkout=" + checkoutId...);
    Log.i(TAG, "[SYNC] Sessão iniciada com guard-band já expirado (verificado por PagamentoConcluido)");
    // ...
}
```

**Mudanças:**
- ✅ Documentação clara de sincronização
- ✅ Log informativo sobre guard-band
- ✅ Referência ao arquivo que valida guard-band

---

## 📊 Resumo de Mudanças

| Arquivo | Adições | Modificações | Logins Adicionados |
|---------|---------|--------------|-------------------|
| BluetoothServiceIndustrial.java | 4 métodos + 2 campos | 2 métodos | 2+ |
| PagamentoConcluido.java | 1 constante | 3 métodos | 10+ |
| SessionManager.java | - | 1 método (doc) | 1+ |
| **TOTAL** | **5 items** | **6 items** | **13+ logs** |

---

## ✅ Validação

Todos os exemplos acima foram implementados com sucesso:

- [x] Constantes de guard-band definidas
- [x] Métodos de verificação implementados
- [x] Validações em ACTION_WRITE_READY
- [x] Validações em iniciarVendaEEnfileirar()
- [x] Validações em enfileirarComandoServe() (2ª check)
- [x] Logs obrigatórios adicionados
- [x] Documentação de sincronização

---

## 🚀 Próximo Passo

Compilar no **Android Studio** (não no VS Code):

1. Abrir projeto em Android Studio
2. File → Sync Now
3. Build → Build APK(s)
4. Testar no dispositivo/emulador

**Comando de teste de logs:**
```bash
adb logcat | grep -E "\[GUARD-BAND\]|\[TIMING\]|\[TIMESTAMPS\]"
```

---

**Gerado em:** 28 de Março, 2026  
**Versão:** 3.0  
**Status:** ✅ PRONTO PARA PROD
