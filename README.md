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
