# Referência do Protocolo ESP32 NUS v1.0.0 (2026-05-05)

Este documento descreve o protocolo de comunicação Bluetooth Low Energy (BLE) entre o aplicativo Android Chopp On e o firmware do ESP32 (Nordic UART Service - NUS).

## 1. Comandos de Consulta (GET)

| Comando | Resposta Esperada | Descrição |
|---------|-------------------|-----------|
| `$PL:0` | `PL:<valor>` | Consulta o fator atual de pulsos por litro gravado na EEPROM. |
| `$TO:0` | `TO:<valor_ms>` | Consulta o timeout de inatividade atual em milissegundos. |
| `$VR:` | `VR:<versao>/<data>` | Consulta a versão atual do firmware e data de compilação. |
| `$DB:` | `(várias linhas)` | Solicita um dump completo de diagnóstico do ESP32. |

## 2. Comandos de Configuração (SET)

| Comando | Resposta Esperada | Descrição |
|---------|-------------------|-----------|
| `$PL:<n>` | `OK` | Grava um novo fator de pulsos por litro na EEPROM. |
| `$TO:<s>` | `OK` | Configura o timeout de inatividade em **segundos** (o ESP32 converte para ms internamente). |

## 3. Comandos de Fluxo e Teste

| Comando | Resposta Esperada | Descrição |
|---------|-------------------|-----------|
| `$ML:<n>` | `OK` → `IN:` → `VP:...` → `QP:<n>` → `ML:<n>` → `FN:` | Inicia a liberação de `<n>` mililitros. |
| `$LB:` | `OK` → `IN:` → `VP:...` | Inicia a liberação contínua (livre). Só encerra por timeout de inatividade ou `$ML:0`. |
| `$ML:0` | `ML:<n>` → `FN:` | Interrompe imediatamente qualquer liberação em andamento (incluindo `$LB:`). |
| `$RS:<n>` | `OK` → `IN:` → `VP:...` | Retoma um ciclo incompleto, liberando os `<n>` mililitros restantes. |

## 4. Fluxo de Calibração Automática ($CA / $CF)

O processo de calibração foi atualizado para ser mais preciso e guiado por hardware. Ele ocorre em duas etapas:

### Passo 1: Dispensação de Teste
O Android envia o comando `$CA:<ml>` (ex: `$CA:300`) para iniciar um ciclo especial de calibração.
- O ESP32 abre a válvula (`IN:`).
- O ESP32 envia o volume parcial (`VP:`) usando o fator de pulsos atual.
- Ao atingir o volume solicitado, o ESP32 fecha a válvula, envia o total de pulsos contados (`QP:<n>`) e o marcador de fim de dispensação de calibração (`CA:`).
- **Importante:** O ESP32 **não** envia `ML:` nem `FN:` neste momento. Ele entra em estado de espera (`aguardandoCalibracao = true`).

### Passo 2: Confirmação do Volume Real
O operador mede o volume real de líquido na proveta e o Android envia o comando `$CF:<ml_real>`.
- O ESP32 calcula o novo fator: `pulsosLitro = (QP / ml_real) * 1000`.
- O ESP32 grava o novo fator na EEPROM.
- O ESP32 responde com o novo fator (`PL:<novo_valor>`) e encerra o ciclo de calibração com `FN:`.

## 5. Máquina de Estados do Android (CalibrarPulsos.java)

Para suportar o fluxo de calibração em duas etapas, a tela `CalibrarPulsos` implementa a seguinte máquina de estados:

1. **`IDLE`**: Estado inicial. O botão "Iniciar Calibração" está disponível.
2. **`CAL_DISPENSANDO`**: O Android enviou `$CA:300`. A UI bloqueia os botões e exibe "Calibrando...". Aguarda `IN:`, `VP:` e `CA:`.
3. **`CAL_AGUARDANDO`**: O ESP32 enviou `CA:`. A UI exibe o campo para o operador digitar o volume medido na proveta e o botão "Confirmar Medição".
4. **`CAL_CONCLUIDA`**: O Android enviou `$CF:<ml_real>`. Aguarda `PL:` e `FN:` do ESP32. Ao receber, exibe mensagem de sucesso e retorna para `IDLE`.

## 6. Tratamento de Erros e Timeout

- Se o ESP32 rejeitar um comando (ex: `$CF:` enviado sem um `$CA:` prévio), ele responderá com `ERRO`.
- Se a conexão BLE cair durante a calibração, o Android aborta a máquina de estados e retorna para `IDLE`.
- O timeout de inatividade (`$TO`) continua ativo durante a dispensação de calibração (`$CA:`). Se não houver fluxo, a válvula fechará por segurança.
