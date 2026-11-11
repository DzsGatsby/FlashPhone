<!-- translated-from: README.md @ e5ab1ebcba05 -->

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

Si ya tienes la aplicación, usa **Acerca de → Buscar actualizaciones** (icono de información arriba a la derecha; en versiones anteriores, Ajustes → Acerca de) o instala el APK firmado encima de la versión existente para conservar la configuración y el historial. Consulta la [guía de actualización y migración (chino simplificado)](../app-updates.md).

## Varios dispositivos y herramientas para agentes

La versión 1.1.0 añade un servicio de control privado opcional: Android, iPhone y los navegadores de escritorio comparten la lista de dispositivos, las tareas telefónicas, las transcripciones y las grabaciones protegidas de un mismo propietario. Registra una sola vez el dispositivo Android que ejecutará las llamadas y activa expresamente las tareas telefónicas remotas en sus ajustes. El navegador y las siete herramientas MCP `flashphone_*` utilizan las mismas tareas y permiten consultar sus resultados finales; la recepción de una solicitud de llamada no se presenta como una conversación completada. Consulta la [guía de configuración y uso (chino simplificado)](../../tools/control-plane/README.md) y la [skill para Codex / Grok Bot (chino simplificado)](../../tools/flashphone-client/skill/SKILL.md).

El puente Mac/iPhone es experimental y actualmente está bloqueado en el equipo de desarrollo por el requisito de cuenta de iCloud/FaceTime de Apple. El control real de llamadas y el audio bidireccional de la línea del operador siguen sin verificar; el puente no anuncia capacidad para llamar hasta que se superen esas comprobaciones. La consola web en un iPhone ya puede funcionar como controlador, independientemente de que ese iPhone pueda ejecutar las llamadas. Consulta el [estado y la configuración del puente Mac (inglés)](../../tools/mac-bridge/README.md).

## Servicio y datos

Flash phone utiliza tu SIM y mantiene disponible la aplicación de teléfono habitual. Durante la fase gestionada por la IA, el audio se envía al servicio de voz configurado y se graba automáticamente en el dispositivo en dos canales: la captura del interlocutor a la izquierda y el audio de IA aceptado por la pista telefónica del dispositivo a la derecha. La grabación se detiene al tomar el control. Las grabaciones y transcripciones se guardan cifradas, y reproducirlas no las sube a ningún servicio. Una interrupción puede descartar audio aún en cola; el canal derecho no demuestra que el interlocutor lo haya oído. El operador y el servicio de voz pueden cobrar por separado.

Cada grabación está limitada a 600 segundos, con un máximo de 50 archivos y 200 MiB; los más antiguos se eliminan cuando hace falta espacio. Tras emparejar y obtener los permisos admitidos por el dispositivo, la aplicación intenta recuperar la depuración inalámbrica en una Wi-Fi conectada y de confianza, y restablecer la espera de llamadas entrantes previamente autorizada después de arrancar, desbloquear o actualizar. No elude la confianza del sistema, la detención forzada ni las restricciones del fabricante, y no obtiene root. Las nuevas funciones tienen comprobaciones sin conexión; la grabación de llamadas reales y la recuperación tras reiniciar o actualizar siguen pendientes de validación en el teléfono.

Cuando se activan expresamente las tareas telefónicas remotas, la transcripción y la grabación disponible de esa tarea también se suben por HTTPS al servicio de control autenticado del propietario y a su bucket privado de R2. No tienen una URL pública para compartirlas. Esta copia remota es independiente de la reproducción local habitual y tiene sus propios límites de conservación. El servicio de control distribuye tareas y resultados; no retransmite el audio de la llamada en tiempo real ni ejecuta un modelo de voz.

## Proyecto y licencia

Flash phone es un proyecto independiente basado en [Mika, de wangzan101 y sus colaboradores](https://github.com/wangzan101/Mika). Consulta la [licencia Apache-2.0](../../LICENSE), el [aviso de derechos de autor de Mika](../../NOTICE), los [avisos de terceros](../../licenses/THIRD_PARTY_NOTICES.md) y la [guía para colaborar (inglés)](../../.github/CONTRIBUTING.md).
