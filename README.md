# ChoppOnTap — App Android v2.0 FRANQ

Aplicativo Android para sistema de chopp self-service em franquias.

## Protocolo BLE v2.0

### Envio (Android → ESP32)

| Comando | Formato |
|---------|---------|
| SERVE   | `SERVE\|<ml>\|<CMD_ID>\|<SESSION_ID>\|<HMAC>` |
| AUTH    | `AUTH\|<CMD_ID>\|<SESSION_ID>\|<HMAC>` |
| STOP    | `STOP\|<CMD_ID>\|<SESSION_ID>\|<HMAC>` |
| STATUS  | `STATUS\|<CMD_ID>` |
| PING    | `PING\|<CMD_ID>` |

### Recepção (ESP32 → Android)

| Mensagem | Significado |
|----------|-------------|
| `ACK\|<CMD_ID>` | Comando recebido pelo ESP32 |
| `DONE\|<CMD_ID>\|<ML_REAL>\|<SESSION_ID>` | Dispensação concluída |
| `AUTH:OK` | Autenticação aceita |
| `AUTH:FAIL` | Autenticação rejeitada |
| `ERROR:SESSION_MISMATCH` | SESSION_ID inválido |
| `ERROR:NOT_AUTHENTICATED` | Não autenticado |
| `ERROR:VOLUME_EXCEEDED` | Volume inválido |
| `ERROR:HMAC_INVALID` | Token HMAC inválido |
| `ERROR:WATCHDOG` | Placa reiniciou |
| `WARN:FLOW_TIMEOUT` | **Barril vazio / sem fluxo** |
| `WARN:VOLUME_EXCEEDED` | Volume real excedeu o solicitado |

## Arquivos Modificados (v2.0)

| Arquivo | Modificação |
|---------|-------------|
| `BleCommand.java` | Protocolo v2.0 com HMAC-SHA256, novos códigos de erro |
| `BleParser.java` | Suporte a WARN:*, ERROR:HMAC_INVALID, ERROR:VALVE_STUCK |
| `CommandQueue.java` | Tratamento de todos os novos erros e alertas v2.0 |
| `ble/BleManagerV2.kt` | Novo módulo Kotlin com geração de token HMAC |

## UUIDs do Serviço BLE

```
SERVICE:  7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001
RX:       7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001  (Android → ESP32)
TX:       7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001  (ESP32 → Android)
```

## Arquitetura de Ciclo de Vida BLE (v3.0)

### Correções Implementadas

1. **BLE Service Singleton**
   - Serviço inicia apenas uma vez no `Application.onCreate()`
   - Instância estática previne múltiplas inicializações
   - Verificação em `onCreate()` aborta serviços duplicados

2. **Persistência Independente da UI**
   - Conexão BLE mantém ativa durante navegação entre Activities
   - Foreground Service garante sobrevivência do processo
   - Não depende do ciclo de vida das Activities

3. **Logs Críticos**
   - `[INDUSTRIAL] ✅ BluetoothServiceIndustrial v3.0 SINGLETON iniciado`
   - `[INDUSTRIAL] ⚠️ Serviço BLE já está rodando! Abortando onCreate duplicado`
   - `[RECONNECT] 🔄 Tentativa de reconexão automática iniciada`

4. **Validação**
   - Troca de tela NÃO derruba conexão BLE
   - BLE permanece conectado durante toda a sessão
   - Reconexão apenas em erros reais (não por navegação)

### Arquivos Modificados

| Arquivo | Modificação |
|---------|-------------|
| `ChoppOnApplication.java` | Inicialização do BLE Service no startup |
| `BluetoothServiceIndustrial.java` | Singleton + logs críticos |
| `Home.java` | Verificação antes de iniciar serviço |
| `PagamentoConcluido.java` | Verificação antes de iniciar serviço |

### Correções de Estabilidade do Processo (v4.0)

1. **UncaughtExceptionHandler Global**
   - Detecta qualquer crash oculto no app
   - Loga thread, exception e stacktrace completos
   - Permite análise postmortem de crashes

2. **Monitoramento de Ciclo de Vida**
   - `Application.onCreate()` / `onTerminate()` / `onLowMemory()`
   - `Service.onCreate()` / `onDestroy()` com logs específicos
   - `[PROCESS] 🚀 PROCESS STARTED` / `[PROCESS] 💀 PROCESS ENDED`
   - `[SERVICE] 🟢 SERVICE CREATED` / `[SERVICE] 🔴 SERVICE DESTROYED`

3. **Eliminação de ANR / UI Thread**
   - Geração de QR Code movida para background thread
   - Evita travamento da UI em operações pesadas
   - Previne Application Not Responding

4. **Controle de Concorrência API**
   - Flag `sVerifyTapInProgress` evita múltiplas chamadas simultâneas
   - Apenas 1 request `verify_tap.php` por vez
   - Logs de início/conclusão de requests

5. **Persistência Robusta do Service**
   - Try-catch em `onCreate()` captura exceções
   - START_STICKY garante reinício automático
   - Singleton previne instâncias duplicadas

6. **Logs Críticos de Estabilidade**
   - `[CRASH] 💥 CRASH OCULTO DETECTADO!`
   - `[RECONNECT] 🔄 Tentativa de reconexão automática iniciada`
   - `[API] 🔄 verify_tap iniciado` / `[API] ✅ verify_tap concluído`

### Arquivos Modificados

| Arquivo | Modificação |
|---------|-------------|
| `ChoppOnApplication.java` | UncaughtExceptionHandler + lifecycle logs |
| `BluetoothServiceIndustrial.java` | Try-catch onCreate + SERVICE logs |
| `ApiHelper.java` | Controle de concorrência verify_tap |
| `AcessoMaster.java` | QR generation em background |
| `README.md` | Documentação atualizada |
| `VALIDATION_CHECKLIST.md` | Novos testes de estabilidade |

## Segurança

- Autenticação por **HMAC-SHA256** (chave compartilhada com firmware)
- Proteção **anti-replay** via CMD_ID único por comando
- Validação de **SESSION_ID** por sessão de venda

## Firmware Compatível

Repositório do firmware ESP32: [choppon24h-png/ESP32_FRANQ](https://github.com/choppon24h-png/ESP32_FRANQ)

> **IMPORTANTE:** A chave `HMAC_SECRET_KEY` em `BleCommand.java` e `BleManagerV2.kt`
> deve ser idêntica à definida em `config.h` do firmware ESP32.

## Requisitos

- Android 8.0+ (API 26+)
- Bluetooth LE
- Permissões: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
