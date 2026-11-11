<!-- translated-from: README.md @ bd8976dbee3c -->

<div align="center">
  <img src="../../app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Icono de Flash phone" />
  <h1>Flash phone</h1>
  <p>Un asistente de IA para hacer y recibir llamadas con tu SIM en Android.</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>Descargar el APK</strong></a> ·
    <a href="../README.en.md">Guía de uso (inglés)</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">Informar de un problema</a>
  </p>
</div>

[English](../../README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · Español

## Qué puede hacer

- **Atender llamadas.** Elige qué llamadas puede contestar la IA, reserva los números importantes o pausa la respuesta automática. Puedes tomar el control durante la llamada sin cortarla.
- **Llamar con un objetivo.** Elige el número y la SIM, añade qué preguntar, el contexto y qué puede prometer la IA. Revisa el encargo antes de llamar.
- **Atender y llamar con la pantalla bloqueada.** Si el asistente de audio y el modo de espera en segundo plano están listos, la IA puede contestar las llamadas autorizadas. También puedes confirmar una llamada saliente para que se intente una sola vez si bloqueas la pantalla en los siguientes 45 segundos.
- **Revisar la conversación.** Lee la transcripción junto al encargo original, reproduce la grabación local de dos canales, marca la llamada como atendida o reutiliza el encargo como borrador.

## Instalación y primeros pasos

1. Usa un teléfono con Android 12 o posterior y una SIM activa. Abre [la versión más reciente de GitHub](https://github.com/DzsGatsby/FlashPhone/releases/latest), descarga `FlashPhone-vX.Y.Z.apk` y sigue las indicaciones de Android. Si el sistema lo pide, permite que la aplicación que abrió el APK lo instale.
2. Empareja de forma inalámbrica el asistente de audio en el propio teléfono y añade las credenciales del servicio de voz en tiempo real de Volcengine. La compatibilidad de audio depende del teléfono y de la versión de Android.
3. Comprueba que ambas partes se oigan y que puedas retomar la llamada antes de activar la respuesta automática. Tras verificar el audio y activarla, la espera en segundo plano se inicia automáticamente; desactivarla también revoca esa espera. Consulta la [guía de configuración y compatibilidad (inglés)](../README.en.md).

Si ya tienes la aplicación, usa **Ajustes → Acerca de → Buscar actualizaciones** o instala el APK firmado encima de la versión existente para conservar la configuración y el historial. Consulta la [guía de actualización y migración (chino simplificado)](../app-updates.md).

## Servicio y datos

Flash phone utiliza tu SIM y mantiene disponible la aplicación de teléfono habitual. Durante la fase gestionada por la IA, el audio se envía al servicio de voz configurado y se graba automáticamente en el dispositivo en dos canales: la captura del interlocutor a la izquierda y el audio de IA aceptado por la pista telefónica del dispositivo a la derecha. La grabación se detiene al tomar el control. Las grabaciones y transcripciones se guardan cifradas, y reproducirlas no las sube a ningún servicio. Una interrupción puede descartar audio aún en cola; el canal derecho no demuestra que el interlocutor lo haya oído. El operador y el servicio de voz pueden cobrar por separado.

Cada grabación está limitada a 600 segundos, con un máximo de 50 archivos y 200 MiB; los más antiguos se eliminan cuando hace falta espacio. Tras emparejar y obtener los permisos admitidos por el dispositivo, la aplicación intenta recuperar la depuración inalámbrica en una Wi-Fi conectada y de confianza, y restablecer la espera de llamadas entrantes previamente autorizada después de arrancar, desbloquear o actualizar. No elude la confianza del sistema, la detención forzada ni las restricciones del fabricante, y no obtiene root. Las nuevas funciones tienen comprobaciones sin conexión; la grabación de llamadas reales y la recuperación tras reiniciar o actualizar siguen pendientes de validación en el teléfono.

## Proyecto y licencia

Flash phone es un proyecto independiente basado en [Mika, de wangzan101 y sus colaboradores](https://github.com/wangzan101/Mika). Consulta la [licencia Apache-2.0](../../LICENSE), el [aviso de derechos de autor de Mika](../../NOTICE), los [avisos de terceros](../../licenses/THIRD_PARTY_NOTICES.md) y la [guía para colaborar (inglés)](../../.github/CONTRIBUTING.md).
