# Pack de recursos de HardcoreWorldReset

Los sonidos del mod viven dentro del jar (`src/main/resources/assets/hardcoreworldreset/sounds`),
así que cualquier cliente que tenga el mod instalado los oye sin hacer nada más.

Para los clientes **sin** el mod (Minecraft vanilla), el servidor puede ofrecerles los mismos
sonidos como pack de recursos:

1. `./gradlew build` genera `build/libs/hardcoreworldreset-resourcepack-<versión>.zip`
   a partir de los mismos archivos del jar; este directorio solo aporta el `pack.mcmeta`.
2. Sube el zip a algún sitio con URL directa (GitHub Releases, Dropbox, un servidor web...).
3. En `server.properties`:

   ```
   resource-pack=https://.../hardcoreworldreset-resourcepack-<versión>.zip
   resource-pack-sha1=<sha1 del zip>
   require-resource-pack=true
   ```

   El sha1 se obtiene con `sha1sum build/libs/hardcoreworldreset-resourcepack-<versión>.zip`.

Los ids de sonido son `hardcoreworldreset:dead`, `hardcoreworldreset:jumpscare`,
`hardcoreworldreset:limbo` y `hardcoreworldreset:resetworld`. El mod los envía como entradas
directas en el paquete, no como ids numéricos de registro, y por eso un cliente vanilla con el
pack los resuelve igual que uno con el mod.
