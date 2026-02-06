AiChat (Paper 1.21.11) — AI-чат бот с динамическими персонами

Сборка:
  gradle build

Готовый JAR:
  build/libs/ai-npc-chat-1.2.4.jar   (fat jar, с зависимостями)

Установка:
  1) Скопируй JAR в server/plugins/
  2) Запусти сервер (создастся plugins/AiChat/config.yml)
  3) Настрой endpoint и ключ:
       /aichat setendpoint https://openrouter.ai/api/v1/chat/completions
       /aichat setkey sk-or-xxxxxxxx
  4) Проверка:
       /aichat test
