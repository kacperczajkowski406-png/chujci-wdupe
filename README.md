# Chat2Discord Client (Fabric 1.21.4)

Mod kliencki: instalujesz go u siebie i działa na **każdym serwerze**, bez zgody i bez dostępu do serwera.
Wpisujesz w grze `/dc cokolwiek`, a bot wysyła na Twój kanał Discorda:

**TwójNick** wysłał: cokolwiek

Komenda działa tylko lokalnie i nie jest wysyłana na serwer Minecrafta.

## 1. Webhook na Discordzie
Discord → Ustawienia kanału → Integracje → Webhooki → Nowy webhook → Kopiuj URL.

## 2. Budowanie
Wymagane: Java 21 i Gradle.

```
gradle build
```

Gotowy plik: `build/libs/chat2discord-client-1.0.0.jar`

## Nazwa komendy i webhook (w kodzie, nie w configu)
Otwórz `src/main/java/pl/chat2discord/client/Chat2DiscordClient.java` i znajdź na górze pliku dwie linie:
```java
private static final List<String> COMMANDS = List.of("dc");
private static final String WEBHOOK_URL = "";
```
- `COMMANDS` — zmień `"dc"` na swoją nazwę, albo podaj kilka naraz: `List.of("dc", "zglos", "name")`.
- `WEBHOOK_URL` — wklej cały URL w cudzysłowie, dokładnie tak:
  ```java
  private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123456789/AbCdEf...";
  ```
  Musi mieć dwukropek `:` zaraz po `https` i dwa ukośniki `//` po nim — to najczęstszy błąd przy ręcznym przepisywaniu. Najlepiej skopiuj URL z Discorda przyciskiem "Kopiuj URL webhooka" i wklej, zamiast przepisywać ręcznie.

Zapisz plik i dopiero wtedy buduj moda — zmiana wymaga przebudowania.

**WAŻNE:** jeśli budujesz przez GitHub Actions (patrz niżej), rób to w **prywatnym** repozytorium (private), bo inaczej Twój webhook będzie widoczny publicznie w kodzie źródłowym na GitHubie, dla każdego. Przy tworzeniu repozytorium zaznacz "Private".

## 3. Instalacja
1. Zainstaluj Fabric Loader dla 1.21.4 oraz Fabric API.
2. Wrzuć jar do folderu `mods/` w swoim `.minecraft`.
3. Gotowe — webhook jest już w modzie, nie trzeba nic ustawiać w grze.

## Konfiguracja (plik `chat2discord-client.properties`, powstaje przy pierwszym uruchomieniu)
| Opcja | Opis |
|---|---|
| `mode` | wiadomości z czatu: `off`, `filter` (tylko z triggerem), `all` |
| `triggers`, `match`, `strip_trigger` | ustawienia filtra czatu |
| `format` | `{player}` nick, `{message}` treść, `{server}` adres serwera |
| `notify_player` / `notify_text` | potwierdzenie w grze |
| `blocked_words` | słowa/nicki po przecinku — jeśli Twoja wiadomość zawiera którekolwiek z nich, NIE zostanie wysłana |
| `blocked_text` | komunikat, który zobaczysz, gdy wiadomość zostanie zablokowana |

Wysyła tylko to, co sam wpiszesz. Wiadomości innych graczy nie są wysyłane.
Uwaga: część serwerów zabrania modów klienckich, więc sprawdź regulamin.

## Budowanie bez instalowania czegokolwiek (GitHub Actions)
1. Załóż darmowe konto na github.com i utwórz nowe repozytorium (New repository), zaznacz **Private**.
2. Kliknij "uploading an existing file" i wgraj całą zawartość tego folderu (razem z folderem `.github`), już z wklejonym `WEBHOOK_URL`.
3. Kliknij Commit changes. Wejdź w zakładkę **Actions** i poczekaj na zielony znaczek (kilka minut).
4. Otwórz ukończone uruchomienie, na dole w sekcji **Artifacts** pobierz `mod-jar` i rozpakuj. W środku jest gotowy plik `.jar`.
