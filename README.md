# Nota do dia · celular

App Android mínimo que lê o tempo de tela (UsageStats) e envia por dia para uma planilha do Google.
A Nota do dia (programa do Windows) lê essa planilha.

- `app/` — app Android (Java, sem bibliotecas externas)
- `apps-script/Code.gs` — script da planilha
- `.github/workflows/build.yml` — monta o APK a cada envio para `main` e publica em Releases ("APK mais recente")
