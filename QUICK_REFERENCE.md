# ⚡ QUICK REFERENCE - API CONNECTIVITY FIX

## 📌 O QUE MUDOU?

**ANTES** ❌
```
http://192.168.1.100/choppon/api/  ← IP LOCAL + HTTP (INSEGURO)
```

**DEPOIS** ✅
```
https://ochoppoficial.com.br/api/  ← HTTPS + DOMÍNIO OFICIAL (SEGURO)
```

---

## 🚀 COMO USAR AGORA?

### Desenvolvedor
Não precisa fazer nada especial! A URL está centralizada em:
```java
ApiConfig.getBaseUrl()  // Retorna automaticamente a URL correta
```

### Se Precisar Customizar
Edite apenas um arquivo:
```
app/src/main/java/com/example/choppontap/ApiConfig.java
```

Linha 12:
```java
private static final String PRODUCTION_URL = "https://ochoppoficial.com.br/api/";
```

---

## 🧪 TESTE RÁPIDO

**Verificar URL**:
```bash
adb logcat | grep "ApiConfig\|URL"
```

**Logs Esperados**:
```
[ApiConfig] [URL] https://ochoppoficial.com.br/api/
[ApiConfig] [HTTPS] true ✅
```

**Se falhar**:
```bash
adb logcat | grep "ApiConfig\|ERROR"
# Procurar por erros de validação
```

---

## 📋 CHECKLIST PRÉ-DEPLOY

- [ ] Build com sucesso: `./gradlew clean build`
- [ ] Instalar: `adb install -r app-debug.apk`
- [ ] Logs OK: `adb logcat | grep ApiConfig`
- [ ] Login funciona: Imei -> verify_tap.php
- [ ] Liberar líquido: Sem erros de conexão
- [ ] API call única: Verificar uma única POST request

---

## 🔍 TROUBLESHOOTING RÁPIDO

| Erro | Solução |
|------|---------|
| `SSL verification failed` | Domínio está correto? `https://ochoppoficial.com.br/` |
| `Network timeout` | Device tem internet? `adb shell ping google.com` |
| `HTTP 403 Forbidden` | IP bloqueado? Contatar DevOps |
| `Cleartext traffic not permitted` | Verificar usando `https://` (com s) |
| `API call duplicada` | Verificar guard flag em ChopperController |

---

## 📂 ARQUIVOS PRINCIPAIS

```
app/src/main/java/com/example/choppontap/
├── ApiConfig.java ← EDITAAQUI para mudara URL
├── ApiHelper.java ← Usa ApiConfig.getBaseUrl()
├── ChoppOnApplication.java ← Valida na startup
└── AndroidManifest.xml ← Registra ChoppOnApplication

app/src/main/res/xml/
└── network_security_config.xml ← Força HTTPS
```

---

## ⚡ SNAPSHOT DE MUDANÇAS

```bash
# Ver exatamente o que mudou:
git diff 007d195..HEAD -- api* Android* network*

# Ver commits:
git log --oneline -3
```

---

## 🎯 RESUMO

✅ URL: `https://ochoppoficial.com.br/api/`
✅ Centralizado: ApiConfig.java
✅ Seguro: HTTPS obrigatório
✅ Validado: Na startup
✅ Protegido: IP bloqueado em produção
✅ Documentado: API_CONNECTIVITY_FIX.md

---

**Data**: 2026-03-26
**Commit**: 9e797c5
**Status**: 🟢 PRONTO
