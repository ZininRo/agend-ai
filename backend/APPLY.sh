#!/usr/bin/env bash
# Запускать из корня репозитория ПОСЛЕ копирования файлов архива поверх проекта.
set -e
mkdir -p _parked
git mv src/main/java/com/example/agend_ai/entity/ItemOccurrence.java       _parked/
git mv src/main/java/com/example/agend_ai/entity/RecurrenceRule.java       _parked/
git mv src/main/java/com/example/agend_ai/entity/PushSubscription.java     _parked/
git mv src/main/java/com/example/agend_ai/entity/ReminderDelivery.java     _parked/
git mv src/main/java/com/example/agend_ai/entity/enums/DeliveryStatus.java _parked/
git mv src/main/java/com/example/agend_ai/entity/enums/PushProvider.java   _parked/
git rm -f src/main/resources/application.properties
echo "Готово. Запуск: docker compose up --build   |   Тесты: ./mvnw test (нужен Docker)"
