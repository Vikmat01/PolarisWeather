# Polaris Weather

Минималистичное приложение погоды для Android в стиле «liquid glass» (Kotlin, Jetpack Compose).

- Прогноз на 7 дней и почасовой, ощущаемая температура, влажность, ветер со стрелкой направления и анимированным ветряком
- Фон и тема зависят от времени суток и погоды (две темы: неон и «небо»)
- Избранные города, поиск по названию, определение места
- Карта осадков (RainViewer)
- Четыре виджета в стиле системных: «Системный», «Циферблат», «Календарь», «Акцент»
- Стеклянные элементы на библиотеке [Kyant0/AndroidLiquidGlass (backdrop)](https://github.com/Kyant0/AndroidLiquidGlass); эффект линзы работает на Android 13+

Данные: [Open-Meteo](https://open-meteo.com) (без ключа; запасной хост и OpenStreetMap Nominatim на случай блокировок), [RainViewer](https://www.rainviewer.com), карты © Esri, © OpenStreetMap.

## Сборка

Откройте проект в Android Studio и запустите конфигурацию `app`.
Нужны Kotlin 2.3+ (для backdrop 1.0.6) и Android SDK 36.

## Лицензия

MIT, см. [LICENSE](LICENSE).
