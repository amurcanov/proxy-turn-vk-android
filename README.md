> [!CAUTION]
> ## Проект архивирован. Новое продолжение - https://github.com/amurcanov/csqtt
> Разработка и поддержка WDTT прекращены. Репозиторий сохранён исключительно в ознакомительных и исследовательских целях. Обновления, исправления и техническая поддержка больше не предоставляются.

<div align="center">

# WDTT — WireGuard over TURN Tunnel

<br>

<img src="https://img.shields.io/badge/Android-SDK_29--35-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK">
<img src="https://img.shields.io/badge/Go-1.25-00ADD8?style=for-the-badge&logo=go&logoColor=white" alt="Go Version">
<img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
<a href="https://github.com/amurcanov/proxy-turn-vk-android/stargazers">
  <img src="https://img.shields.io/github/stars/amurcanov/proxy-turn-vk-android?style=for-the-badge&logo=github&color=ffca28&labelColor=24292e" alt="Stars">
</a>

[WDTT Telegram Community](https://t.me/wdttcommunity)

</div>

<br>

**WDTT** — это Android-приложение для создания защищённого **WireGuard-туннеля поверх TURN/DTLS**. Клиент поднимает локальный VPN-интерфейс на устройстве, получает WireGuard-конфигурацию от вашего VPS и передаёт транспорт через TURN-серверы VK, маскируя соединение под обычный зашифрованный медиатрафик звонка.

**Кросс., проекты с поддержкой WDTT**: 
- Windows & Linux & MacOS порт - [luminescq/pwdtt](https://github.com/luminescq/PWDTT)
- IOS совместимый клиент - [anton48/vk-turn-proxy-ios](https://github.com/anton48/vk-turn-proxy-ios)

> [!WARNING]
> ## Назначение проекта и дисклеймер
> Приложение является техническим инструментом для защищённого туннелирования трафика через ваш собственный TURN-сервер. Проект WDTT носит исключительно витринно-ознакомительный характер на примере уже имеющейся структуры в целях исследования сетевых протоколов.
>
> Я (**amurcanov**) не призываю использовать WDTT для обхода блокировок или нарушения правил каких-либо платформ, а также не несу ответственности за финальные сценарии использования утилиты пользователями в реальной жизни или сети ИНТЕРНЕТ. Любые специфические технические особенности приложения — не более чем архитектурное совпадение, созданное без какого-либо умысла.
>
> Проект является полностью некоммерческим, не содержит платных функций, скрытых подписок или коммерческой выгоды.

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0**.
