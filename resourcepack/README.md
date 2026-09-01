# HardcoreWorldReset resource pack

Death audio and the final-death jumpscare are sent to clients as **raw packets**, so
nothing here needs a client-side mod. HardcoreWorldReset stays a server-side mod; the
only thing players need is this resource pack, and the server can push it to them
automatically.

## What is in here

| Path | What it is |
| --- | --- |
| `assets/hardcoreworldreset/sounds.json` | Declares the custom sound ids. |
| `assets/hardcoreworldreset/sounds/final_song.ogg` | The track that plays after the jumpscare. |
| `pack.mcmeta` | `pack_format` 34 (Minecraft 1.21 / 1.21.1). |

## Serving it to players

1. Build the zip and grab its hash:

   ```sh
   ./resourcepack/build-pack.sh
   ```

   The zip **must** have `pack.mcmeta` at its root — that is what the script does. Zipping
   the folder itself instead of its contents is the usual reason a pack "does not load".

2. Upload `hardcoreworldreset-resources.zip` to any static HTTPS host (a GitHub release
   asset works, so does Dropbox with `?dl=1`). The URL has to return the raw bytes.

3. Point the server at it in `server.properties`:

   ```properties
   resource-pack=https://example.com/hardcoreworldreset-resources.zip
   resource-pack-sha1=<the sha1 printed by build-pack.sh>
   require-resource-pack=true
   resource-pack-prompt=Sounds and effects for the hardcore run.
   ```

   `resource-pack-sha1` is not optional in practice: without it clients re-download the
   pack on every join and cache it badly. Re-run `build-pack.sh` and update the hash every
   time you change a file in here.

Clients that decline the pack are not broken — the custom sounds simply do not play for
them. Everything else (the red screen, the titles, the vanilla sounds) still works.

### Two vanilla rendering caveats

Both come straight from `InGameHud` and there is nothing a server-side mod can do about
them, so they are worth knowing before you blame the pack:

- The red screen is the world border vignette, and vanilla only draws the vignette on
  **Fancy graphics or better**. Players on Fast graphics get the sound, the title and the
  status effects, but no red flash.
- The full-screen overlay is only drawn in **first person** (and not while a spyglass is
  raised). Someone sitting in third person misses the image.

## Adding your own scare assets

The mod defaults to vanilla sounds for the two death stingers so it works with no assets
at all. To use your own, drop the files in and point the config at them.

**Sounds** must be **Ogg Vorbis** (`.ogg`). Minecraft cannot read mp3/wav/m4a:

```sh
ffmpeg -i your-file.mp3 -map_metadata -1 -vn -ac 2 -ar 44100 -c:a libvorbis -q:a 4 out.ogg
```

Keep long tracks **stereo**: Minecraft plays stereo files without 3D attenuation, so they
stay at full volume even after the player is teleported into Limbo. Mono files fade with
distance.

Put them in `assets/hardcoreworldreset/sounds/` and declare them in `sounds.json`:

```json
{
	"death": {
		"category": "master",
		"sounds": [ { "name": "hardcoreworldreset:death" } ]
	},
	"jumpscare": {
		"category": "master",
		"sounds": [ { "name": "hardcoreworldreset:jumpscare" } ]
	},
	"final_song": {
		"category": "master",
		"sounds": [ { "name": "hardcoreworldreset:final_song", "stream": true } ]
	}
}
```

`"stream": true` matters for anything longer than a few seconds — without it the client
decodes the whole track into memory before playing.

**The jumpscare image** goes at `assets/minecraft/textures/misc/pumpkinblur.png`. On the
final death the mod tells each client it is wearing a carved pumpkin (a client-side lie —
the server-side inventory is untouched), which makes vanilla draw that texture stretched
over the whole screen. Replace it with whatever you want people to see. Use a square PNG
with an alpha channel, 512x512 or 1024x1024; the vanilla file is mostly opaque black with
a cut-out in the middle, and anything transparent shows the world through it. The overlay
is removed again when the song starts.

## Config

`config/hardcoreworldreset.properties`, on the **server**:

| Key | Default | Meaning |
| --- | --- | --- |
| `death-sound` | `minecraft:entity.ender_dragon.growl` | Played to everyone when a player loses a life but survives. |
| `death-sound-pitch` | `0.7` | Pitch for the above. Lower is more ominous. |
| `final-death-sound` | `minecraft:entity.wither.spawn` | The jumpscare stinger, on the last life. |
| `final-death-song` | `hardcoreworldreset:final_song` | Track that starts after the jumpscare. Needs this pack. |
| `final-song-delay-ticks` | `40` | Gap between jumpscare and song. 20 ticks = 1 second. |
| `scare-screen-effects` | `true` | Red screen, full-screen overlay, title, nausea and darkness. |

Any sound key accepts a vanilla `minecraft:*` id, a pack id, or `none` / an empty value to
mute that one.

## Trying it without dying

```
/hwr testScare    # fires the whole final-death sequence, the world is not touched
/hwr stopScare    # clears the screen effects and stops the song
```
