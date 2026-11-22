# Notificaciones de destete (APP-17)

La app recibe por Firebase Cloud Messaging (FCM) los avisos que el `WeaningAlertScheduler` del
backend (BE-015) manda cuando un cordero cumple la edad de destete.

## Flujo

1. Firebase entrega un token al celular (`OviApplication` al arrancar y `OviMessagingService.onNewToken`
   cuando rota). El token se guarda como **pendiente** en `SharedPreferences`.
2. `DeviceTokenWorker` (WorkManager, solo con red, backoff exponencial) llama a
   `DeviceTokenRegistrar.registerIfNeeded`:
   - sin sesión → no envía nada, el token espera al login;
   - token ya registrado → no reenvía;
   - error de red o 5xx → `Result.retry()`, el token sigue pendiente.
3. Al llegar un push con `type = WEANING_DUE`, `OviMessagingService` muestra una notificación en el
   canal **Destetes**. Al tocarla se abre `MainActivity` con el animal en los extras del intent.

## Contrato con el backend (BE-015)

```http
POST /api/v1/notifications/device-token
Authorization: Bearer <jwt>
Content-Type: application/json

{"token": "<token FCM>", "platform": "ANDROID"}
```

Cualquier 2xx se considera registrado. El ticket BE-015 no fija el cuerpo; este es el que propone la app.

Datos del push (bloque `data` de FCM):

| clave      | obligatoria | ejemplo        |
|------------|-------------|----------------|
| `type`     | sí          | `WEANING_DUE`  |
| `animalId` | sí          | UUID del animal|
| `ranchId`  | sí          | UUID del rancho|
| `folio`    | no          | `101`          |

Si el backend también manda el bloque `notification`, su título y texto se usan tal cual.

## Para activarlo

- Colocar `androidApp/google-services.json` del proyecto Firebase del equipo. Sin ese archivo la app
  compila y funciona, pero no se inicializa Firebase ni llegan pushes.
- URL del backend: `./gradlew assembleDebug -Pbaapp.apiBaseUrl=https://...` (por defecto
  `http://10.0.2.2:8080`, el host visto desde el emulador).
- Login (APP-02/03): asignar `Notifications.session` con la sesión real, encolar
  `DeviceTokenWorker` después de entrar y llamar a `Notifications.registrar(context).onLogout()` al salir.
- Pantalla de destetes (APP-16): reemplazar el texto provisional de `MainActivity` por la navegación
  al animal de `WeaningAlert`.
