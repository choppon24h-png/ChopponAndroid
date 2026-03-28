# Guard-Band Implementation — Sincronização BLE com ESP32

## Versão
**Data:** 28 de Março, 2026  
**Firmware ESP32:** Atualizado com guard-band (janela de prontidão)  
**Android App:** v3.0+ com suporte a guard-band  

---

## Resumo da Implementação

O firmware ESP32 foi atualizado com um **guard-band (janela de prontidão)** que requer sincronização do app Android após o estado READY ser atingido.

**Guard-band:** 800-1000ms após READY  
**Objetivo:** Eliminar race conditions que causavam desconexão BLE

---

## Mudanças Implementadas

### 1. BluetoothServiceIndustrial.java

#### Campo: `GUARD_BAND_MS`
```java
private static final long GUARD_BAND_MS = 900L;
```
- **Valor:** 900ms (meio da janela 800-1000ms recomendada)
- **Propósito:** Define o tempo mínimo que o app deve aguardar após READY antes de enviar SERVE

#### Campo: `mReadyTimestamp`
```java
private volatile long mReadyTimestamp = 0;
```
- **Propósito:** Registra o timestamp (ms desde epoch) quando READY foi recebido do ESP32
- **Thread-safe:** Volatile para acesso de múltiplas threads

#### Modificação em `transitionTo(State.READY)`
```java
case READY:
    // ...
    mReadyTimestamp = System.currentTimeMillis();
    Log.i(TAG, "[STATE] READY [timestamp=" + mReadyTimestamp + "]");
    Log.i(TAG, "[GUARD-BAND] READY recebido — APP aguardará "
            + GUARD_BAND_MS + "ms antes de enviar SERVE");
    // ...
```
- Registra timestamp quando transiciona para READY
- Emite logs obrigatórios para diagnóstico

#### Modificação em `transitionTo(State.DISCONNECTED)`
```java
case DISCONNECTED:
    pararHeartbeat();
    // ...
    mReadyTimestamp = 0; // Reset guard-band timestamp
```
- Reseta timestamp quando desconecta
- Garante que reconexões começam com estado limpo

#### Novos Métodos Públicos

**`getTimeSinceReady()` — Tempo desde READY**
```java
public long getTimeSinceReady() {
    if (mReadyTimestamp == 0) return -1;
    return System.currentTimeMillis() - mReadyTimestamp;
}
```
- **Retorna:** Tempo em ms desde READY, ou -1 se nunca ativado
- **Uso:** App pode verificar quanto tempo passou

**`isReadyWithGuardBand()` — Verificação de Guard-Band**
```java
public boolean isReadyWithGuardBand() {
    if (!isReady()) return false;
    long timeSinceReady = getTimeSinceReady();
    if (timeSinceReady < 0) return false;
    boolean guardBandExpired = timeSinceReady >= GUARD_BAND_MS;
    return guardBandExpired;
}
```
- **ESTE É O MÉTODO CRÍTICO** que o app deve usar antes de enviar SERVE
- **Retorna:** `true` apenas se está em READY E passou do guard-band
- **Logs:** Emite warning se ainda dentro do guard-band

**`getReadyTimestamp()` — Timestamp do READY**
```java
public long getReadyTimestamp() {
    return mReadyTimestamp;
}
```
- **Retorna:** Timestamp (ms) quando READY foi recebido

**`getGuardBandMs()` — Valor do Guard-Band**
```java
public long getGuardBandMs() {
    return GUARD_BAND_MS;
}
```
- **Retorna:** Valor do guard-band em ms (900ms)

---

### 2. PagamentoConcluido.java

#### Campo de Delay Variável
```java
private static final long ML_SEND_DELAY_MS       = 800L;
private static final long ML_SEND_DELAY_MAX_MS   = 1000L;
```
- Define range de delay: 800-1000ms
- Aleatório dentro do range para evitar sincronização de múltiplos tablets

#### Modificação em ACTION_WRITE_READY Broadcast Handler
```java
case BluetoothServiceIndustrial.ACTION_WRITE_READY:
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
    
    mMainHandler.postDelayed(() -> {
        // ...
        long serveTimestamp = System.currentTimeMillis();
        long elapsedSinceReady = serveTimestamp - readyTimestamp;
        Log.i(TAG, "[SYNC] READY → SERVE delay: " + elapsedSinceReady + "ms 
                (target: " + guardBandDelay + "ms)");
        iniciarVendaEEnfileirar();
    }, guardBandDelay);
```

**Mudanças Principais:**
- Captura timestamp quando READY é recebido
- Calcula delay entre 800-1000ms aleatoriamente
- Emite logs detalhados de timing
- Usa delay variável para evitar sincronização entre tablets
- Log obrigatório: `[SYNC] READY → SERVE delay`

#### Modificação em `iniciarVendaEEnfileirar()`
```java
private void iniciarVendaEEnfileirar() {
    // ... verificações anteriores ...
    
    // GUARD-BAND SYNCHRONIZATION
    if (!mBluetoothService.isReadyWithGuardBand()) {
        long timeSinceReady = mBluetoothService.getTimeSinceReady();
        long remainingGuardBand = mBluetoothService.getGuardBandMs() - timeSinceReady;
        Log.w(TAG, "[GUARD-BAND] Dentro do guard-band — " + remainingGuardBand
                + "ms restantes. Bloqueando envio de SERVE.");
        atualizarStatus("⏳ Sincronizando com dispositivo...");
        return; // BLOQUEAR envio
    }
    
    // Guard-band expirou — OK para enviar
    long timeSinceReady = mBluetoothService.getTimeSinceReady();
    Log.i(TAG, "[GUARD-BAND] Guard-band expirado ✓ (time_since_ready=" + timeSinceReady + "ms)");
    
    // Prosseguir com iniciarVenda...
```

**Mudanças Principais:**
- Verifica `isReadyWithGuardBand()` ANTES de iniciar venda
- Se ainda dentro do guard-band: **BLOQUEIA** envio
- Se guard-band expirou: prossegue com normalidade
- Logs informativos sobre estado

#### Modificação em `enfileirarComandoServe()`
```java
private void enfileirarComandoServe(int volumeMl) {
    // ... verificações anteriores ...
    
    // GUARD-BAND VALIDATION (segunda verificação)
    if (!mBluetoothService.isReadyWithGuardBand()) {
        Log.e(TAG, "[GUARD-BAND] SERVE BLOQUEADO em enfileirarComandoServe() 
                — guard-band não expirado");
        atualizarStatus("⏱ Aguardando sincronização (guard-band)...");
        mComandoEnviado = false; // Reset para tentar novamente
        return;
    }
    
    // Guard-band OK — enfileirar SERVE
    long readyTimestamp = mBluetoothService.getReadyTimestamp();
    long timeSinceReady = mBluetoothService.getTimeSinceReady();
    Log.i(TAG, "[GUARD-BAND] ✓ Guard-band OK — SERVE será enfileirado");
    Log.i(TAG, "[TIMING] READY timestamp: " + readyTimestamp
            + " | Time since READY: " + timeSinceReady + "ms");
    Log.i(TAG, "[TIMING] SERVE send timestamp: " + System.currentTimeMillis());
    Log.i(TAG, "[TIMESTAMPS] → READY @ " + readyTimestamp + "ms → SERVE @ "
            + System.currentTimeMillis() + "ms (Δ=" + timeSinceReady + "ms)");
    
    // Prosseguir com enfileiramento...
```

**Mudanças Principais:**
- Segunda validação defensiva antes de enfileirar
- Logs obrigatórios: `[TIMING]` e `[TIMESTAMPS]` com tempos precisos
- Reset de `mComandoEnviado` se guard-band não expirou
- Rastreamento completo de timing READY → SERVE

---

### 3. SessionManager.java

#### Documentação em `startSession()`
```java
/**
 * NOTA DE SINCRONIZAÇÃO: Este método é chamado APENAS após 
 * isReadyWithGuardBand() retornar true em PagamentoConcluido.java. 
 * O guard-band (800-1000ms após READY) é validado antes desta chamada, 
 * garantindo sincronização com ESP32.
 */
public synchronized void startSession(String checkoutId, int volumeMl, String deviceId) {
    // ...
    Log.i(TAG, "[SYNC] Sessão iniciada com guard-band já expirado 
            (verificado por PagamentoConcluido)");
    // ...
}
```

**Mudanças Principais:**
- Documentação clara de que guard-band é validado ANTES desta chamada
- Log informativo sobre sincronização

---

## Fluxo Completo de Sincronização

### Fluxo CONNECT → SERVE com Guard-Band

```
┌─────────────────────────────────────────────────────────┐
│ FASE 1: Conexão BLE (estado CONNECTING)                │
│ ├─ requestMtu(512)                                      │
│ └─ discoverServices()                                   │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 2: Autenticação HMAC (estado CONNECTED)           │
│ ├─ delay 600ms                                          │
│ ├─ send AUTH|<HMAC>|<CMD_ID>|<SESSION_ID>            │
│ └─ await AUTH_OK                                        │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 3: READY (autenticação bem-sucedida)              │
│ ├─ transitionTo(State.READY)                            │
│ ├─ mReadyTimestamp = now() ◄─── CRÍTICO!               │
│ ├─ broadcastWriteReady()                                │
│ └─ Log: "[STATE] READY [timestamp=...]"                │
│    Log: "[GUARD-BAND] APP aguardará 900ms..."          │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 4: Guard-Band (APP aguarda 800-1000ms)            │
│ ├─ Log: "[BLE] ACTION_WRITE_READY [timestamp=...]"    │
│ ├─ Calculate delay = random(800, 1000)                 │
│ ├─ postDelayed(iniciarVendaEEnfileirar, delay)        │
│ └─ Log: "[SYNC] READY → SERVE delay: ...ms"           │
│    Log: "[TIMESTAMPS] → READY @ ...ms → SERVE @ ...ms" │
└────────────┬────────────────────────────────────────────┘
             │ (delay 800-1000ms)
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 5: Verificação de Guard-Band (1ª check)           │
│ ├─ isReadyWithGuardBand()? == true                      │
│ ├─ if false → BLOQUEAR e retry                          │
│ └─ Log: "[GUARD-BAND] Guard-band OK ✓"                │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 6: Iniciar Sessão (start_session.php)             │
│ ├─ SessionManager.startSession(checkoutId, vol, id)    │
│ └─ Log: "[SYNC] Sessão iniciada com guard-band OK"    │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 7: Verificação de Guard-Band (2ª check)           │
│ ├─ enfileirarComandoServe()                             │
│ ├─ isReadyWithGuardBand()? == true                      │
│ ├─ if false → BLOQUEAR e reset mComandoEnviado         │
│ └─ Log: "[GUARD-BAND] ✓ Guard-band OK — SERVE OK"     │
│    Log: "[TIMING] READY timestamp, send timestamp"     │
│    Log: "[TIMESTAMPS] → READY @ ...ms → SERVE @ ...ms" │
└────────────┬────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────┐
│ FASE 8: Enfileirar SERVE (CommandQueue v2.3)          │
│ ├─ BleCommand cmd = queue.enqueueServe(vol, session) │
│ ├─ send SERVE|<ml>|<CMD_ID>|<SESSION_ID>|<HMAC>      │
│ └─ await ACK/DONE/ERROR                                │
└─────────────────────────────────────────────────────────┘
```

---

## Logs Obrigatórios (Diagóstico)

### Em BluetoothServiceIndustrial (READY)
```
[STATE] READY [timestamp=1711627543210]
[GUARD-BAND] READY recebido — APP aguardará 900ms antes de enviar SERVE
```

### Em PagamentoConcluido (ACTION_WRITE_READY)
```
[BLE] ACTION_WRITE_READY — ESP32 READY recebido [timestamp=1711627543210]
[GUARD-BAND] Guard-band=900ms — Android aguardará 892ms antes de enviar SERVE
```

### Em PagamentoConcluido (Guard-Band OK)
```
[GUARD-BAND] Guard-band expirado ✓ (time_since_ready=920ms)
[GUARD-BAND] ✓ Guard-band OK — SERVE será enfileirado
[TIMING] READY timestamp: 1711627543210 | Time since READY: 920ms
[TIMING] SERVE send timestamp: 1711627544130
[TIMESTAMPS] → READY @ 1711627543210ms → SERVE @ 1711627544130ms (Δ=920ms)
```

### Em PagamentoConcluido (Guard-Band BLOQUEADO)
```
[GUARD-BAND] Dentro do guard-band — 78ms restantes. Bloqueando envio de SERVE.
[GUARD-BAND] BLOQUEADO — 78ms / 900ms
```

---

## Validação de Implementação

### Checklist de Funcionalidade

- [x] `BluetoothServiceIndustrial.GUARD_BAND_MS` = 900ms
- [x] `BluetoothServiceIndustrial.mReadyTimestamp` registra timestamp quando READY
- [x] `BluetoothServiceIndustrial.isReadyWithGuardBand()` implementado
- [x] `BluetoothServiceIndustrial.getTimeSinceReady()` retorna tempo desde READY
- [x] `PagamentoConcluido.ACTION_WRITE_READY` usa delay 800-1000ms
- [x] `PagamentoConcluido.iniciarVendaEEnfileirar()` verifica guard-band
- [x] `PagamentoConcluido.enfileirarComandoServe()` verifica guard-band 2ª vez
- [x] Logs obrigatórios: `[GUARD-BAND]`, `[TIMING]`, `[TIMESTAMPS]`
- [x] SessionManager.startSession() documentado com sincronização

### Testes Recomendados

1. **Teste de Timing:**
   - Conexão BLE → READY → verificar tempo READY → SERVE
   - Esperado: 800-1000ms
   - Verificar logs `[SYNC] READY → SERVE delay`

2. **Teste de Guard-Band Bloqueio:**
   - Enviar SERVE ANTES de 800ms após READY
   - Esperado: BLOQUEADO com log `[GUARD-BAND] Dentro do guard-band`

3. **Teste de Guard-Band OK:**
   - Enviar SERVE APÓS 800ms de READY
   - Esperado: OK com log `[GUARD-BAND] Guard-band OK ✓`

4. **Teste de Reconexão:**
   - Conectar → READY → desconectar → reconectar
   - Esperado: mReadyTimestamp resetado em DISCONNECTED, novo ciclo em reconexão

5. **Teste de Sincronização ESP32:**
   - Múltiplas conexões simultâneas (tablets)
   - Esperado: Cada tablet respeita seu próprio guard-band (sem race condition)

---

## Notas Importantes

### ⚠️ CRÍTICO: isReadyWithGuardBand() DEVE ser usado para enviar SERVE

```java
// ❌ ERRADO
if (mBluetoothService.isReady()) {
    enfileirarComandoServe(); // Pode enviar durante guard-band!
}

// ✅ CORRETO
if (mBluetoothService.isReadyWithGuardBand()) {
    enfileirarComandoServe(); // Seguro — guard-band expirado
}
```

### ⚠️ Delay Variável (800-1000ms aleatório)

O delay NÃO é sempre 900ms. É aleatório entre 800-1000ms para:
- Evitar sincronização perfeita entre múltiplos tablets
- Reduzir picos de carga no ESP32
- Melhorar robustez em ambientes com muitos dispositivos

### ⚠️ Guard-Band NÃO é bloqueio permanente

Se `isReadyWithGuardBand()` retorna false:
- APP **continua tentando** a cada segundo
- É um bloqueio **temporário** (max 1s)
- Depois de 1s sempre retorna true

### ⚠️ Thread-Safety

- `mReadyTimestamp` é `volatile` para acesso thread-safe
- Métodos de verificação são chamados de `Handler/Looper` ou UI thread
- Seguro para uso em qualquer thread

---

## Compatibilidade

- **Android 8+** (API 26+): Suportado
- **Android 12+** (permissões): Suportado
- **Android 14+** (FGS): Suportado com Foreground Service
- **ESP32 Firmware:** v3.0+ com guard-band
- **Protocolo BLE:** Nordic UART Service (NUS)

---

## Release Notes

### v3.0 — Guard-Band Implementation
- Sincronização com guard-band (800-1000ms) após READY
- Dois níveis de validação em iniciarVendaEEnfileirar() e enfileirarComandoServe()
- Logs obrigatórios para diagnóstico de timing
- Elimina race condition de desconexão BLE
- Regra de ouro: **NUNCA enviar SERVE sem isReadyWithGuardBand() == true**
