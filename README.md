# Avrora Map

Карта Липецка в стиле Авроры.

- Поиск адресов (Photon) и остановок
- Моё местоположение (лапка)
- Маршрут пешком / на машине (OSRM, FOSSGIS)
- Остановки → номера маршрутов → линия маршрута и список остановок

## Файлы
- `MainActivity.kt` — интерфейс и логика экранов
- `StopsRepository.kt` — остановки и маршруты из OpenStreetMap
- `GeoService.kt` — поиск адресов и построение маршрута
- `LocationHelper.kt` — геолокация
- `MapLayers.kt` — слои карты (линии, маркеры)
- `NeonTheme.kt` — цвета карты
- `MarkerFactory.kt` — иконки маркеров

Данные: © участники OpenStreetMap. Тайлы: OpenFreeMap. Движок: MapLibre.
