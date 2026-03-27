# ✅ API CONNECTIVITY RESTORATION CHECKLIST

## 📋 Configurações Aplicadas

### 1. ✅ URL Base Centralizada
**Arquivo**: `ApiConfig.java`
- **Production URL**: `https://ochoppoficial.com.br/api/`
- **Status**: HTTPS obrigatório
- **Validação**: Bloqueia IP em produção

### 2. ✅ ApiHelper Atualizado
**Arquivo**: `ApiHelper.java`
- Removido: `http://192.168.1.100/choppon/api/` (URL errada com IP)
- Adicionado: `ApiConfig.getBaseUrl()` (URL centralizada)
- Ambos os métodos (sendPost/sendGet) usando config centralizada

### 3. ✅ Security Configuration
**Arquivo**: `network_security_config.xml` (NOVO)
```xml
✅ ochoppoficial.com.br → HTTPS obrigatório (cleartextTraffic=false)
✅ localhost/127.0.0.1 → HTTP permitido em debug
✅ 192.168.0.0/16 → HTTP permitido em debug
✅ Padrão global → HTTPS obrigatório
```

### 4. ✅ AndroidManifest.xml Corrigido
**Mudanças**:
- Removido: `android:usesCleartextTraffic="true"` (INSEGURO)
- Adicionado: `android:networkSecurityConfig="@xml/network_security_config"` (SEGURO)
- Adicionado: `android:name=".ChoppOnApplication"` (inicialização)

### 5. ✅ Application Initialization
**Arquivo**: `ChoppOnApplication.java` (NOVO)
- Valida configuração de API na startup
- Loga URL e modo (DEBUG/PRODUCTION)
- Bloqueia app se URL em produção for inválida

---

## 🧪 TESTE DE CONECTIVIDADE

### Pre-requisitos
```bash
# Build com as mudanças
./gradlew clean build

# Instalar no device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Limpar logs anteriores
adb logcat -c
```

### Teste 1️⃣ - Validação de Configuração
```bash
# Execute o app e verifique os logs:
adb logcat | grep -E "ChoppOn|ApiConfig|ApiHelper"

# Esperado:
# [ChoppOn] 🚀 ChoppOn APP INICIALIZADA
# [ApiConfig] === API CONFIG ===
# [ApiConfig] [MODE] PRODUCTION/DEBUG
# [ApiConfig] [URL] https://ochoppoficial.com.br/api/
# [ApiConfig] [HTTPS] true
# [ApiConfig] ✅ Configuração de API validada com sucesso
```

### Teste 2️⃣ - Conexão HTTPS
```bash
# Em device, navegue para login (Imei Activity)
# Verifique logs de POST request:
adb logcat | grep -E "ApiHelper.*POST|verify_tap"

# Esperado:
# [ApiHelper] [POST] https://ochoppoficial.com.br/api/verify_tap.php
# [SCAN] verify_tap_mac: HTTP 200
```

### Teste 3️⃣ - DNS Resolution
```bash
# Teste direto (precisa rodar no device):
adb shell ping ochoppoficial.com.br

# Esperado: Pings bem-sucedidos com tempo de resposta
# Se falhar: Problema de rede do device
```

### Teste 4️⃣ - SSL Certificate Validation
```bash
# Teste manualmente com curl (do PC):
curl -I https://ochoppoficial.com.br/api/

# Esperado:
# HTTP/2 200
# Sem erros de certificado SSL
```

### Teste 5️⃣ - Health Check
```bash
# Acesse a home activity e verifique logs completos:
adb logcat | grep -A2 -B2 "ochoppoficial"

# Esperado:
# [ApiHelper] [POST] https://ochoppoficial.com.br/api/verify_tap.php
# [Home] verify_tap resposta: {...}
# Sem erros de conexão
```

---

## 🔍 TROUBLESHOOTING

### ❌ Erro: "Network on main thread"
```
Causa: Request sendo feita na main thread
Solução: Verificar se OkHttpClient.enqueue() está em uso (é assíncrono)
```

### ❌ Erro: "SSL Certificate verification failed"
```
Causa: Certificado SSL inválido ou expirado
Solução:
1. Verificar: https://ochoppoficial.com.br/ no browser
2. Se OK no browser mas falha no app:
   - Limpar Gradle cache: ./gradlew clean
   - Reinstalar: adb uninstall && adb install
```

### ❌ Erro: "HTTP 403 Forbidden"
```
Causa: IP bloqueado pelo servidor
Contexto: Pode ser normal se servidor valida tokens/IPs
Ação: Verificar com DevOps se IP do device está whitelisted
```

### ❌ Erro: "Network timeout"
```
Causa: Servidor respondendo muito lentamente
Solução:
1. Verificar conexão de internet: adb shell ping google.com
2. Testar URL no browser
3. If still slow: contatar DevOps/servidor backend
```

### ❌ Erro: "Cleartext HTTP traffic to ochoppoficial.com not permitted"
```
Causa: network_security_config.xml bloqueando HTTP
Solução: Isso é EXPECTED! URL deve ser HTTPS.
Verificar se de fato está usando https:// (com 's')
```

---

## 📝 VALIDAÇÃO FINAL

Checklist antes de deploy:

- [ ] `ApiConfig.java` criado com HTTPS obrigatório
- [ ] `ApiHelper.java` usando `ApiConfig.getBaseUrl()`
- [ ] `network_security_config.xml` criado e registrado
- [ ] `AndroidManifest.xml` removendo `usesCleartextTraffic`
- [ ] `ChoppOnApplication.java` criado e registrado
- [ ] Build sem erros: `./gradlew build` ✅
- [ ] Logs mostram: `[URL] https://ochoppoficial.com.br/api/`
- [ ] Teste 1-5 todos PASSANDO ✅
- [ ] Nenhuma URL com IP detectada

---

## 🚀 DEPLOY

Após validação completa:

```bash
# 1. Build release
./gradlew build --variant release

# 2. Sign APK (se necessário)
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore keystore.jks app-release-unsigned.apk alias_name

# 3. Install
adb install -r app-release.apk

# 4. Final test em device
# - Fazer login
# - Liberar líquido
# - Verificar logs: [URL] https://ochoppoficial.com.br/api/ ✅
```

---

## 📊 RESUMO DE MUDANÇAS

| Arquivo | Mudança | Motivo |
|---------|---------|--------|
| ApiConfig.java | NOVO | Centralizar URL e validar |
| ApiHelper.java | Removeu IP local, agora usa ApiConfig | Corrigir URL errada |
| network_security_config.xml | NOVO | Forçar HTTPS em produção |
| AndroidManifest.xml | Remove usesCleartextTraffic + adiciona config | Segurança |
| ChoppOnApplication.java | NOVO | Validar config na startup |

---

**Status**: 🟢 PRONTO PARA TESTE
**Data**: 2026-03-26
**Próximo Passo**: Executar teste 1️⃣ acima
