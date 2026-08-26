# Metadata pipeline cleanup notes

The ordinary YouTube upload uploader-name bug is fixed while intentionally keeping `ytm-kt` at `0.4.3`.

## Why this code is currently a little spaghetti-shaped

Track metadata can come from three places: `ytm-kt` `LoadSong`, Fartnite's custom `/next` parser (`EchoSongEndPoint`), and the original/fallback `Track`. `EchoEnhancedSongEndpoint` then merges those sources.

For ordinary YouTube uploads, `ytm-kt` can return an artist entry with a valid channel ID but no display name. After conversion that becomes `Unknown`. Before the fix, that non-empty artist list won the merge even though its metadata quality was worse.

The custom `/next` response already contains both the channel browse ID and byline text. The current fix matches the menu channel ID back to the byline run and treats `Unknown` as incomplete metadata so the named result can win.

## Cleanup targets

- Centralize artist/uploader validity checks instead of scattering `Unknown` handling.
- Make merge priority field-quality-aware instead of only checking whether a list is non-empty.
- Reduce duplicate song metadata parsing between `ytm-kt` and `EchoSongEndPoint` where safe.
- Document which source owns title, artist/uploader, album, extras, cover, and streamables.
- Add regression coverage for one ordinary YouTube upload and one normal YouTube Music catalogue track.

## Do not break while cleaning

1. Keep `ytm-kt` at `0.4.3` unless a separate migration is explicitly tested.
2. Logged-in recommendations must stay personalized.
3. Preserve radio/autoplay behavior from `4392e029f56c612ba02992857c2fe675af9743a1`.
4. Ordinary YouTube uploads must keep the real uploader name and working channel link.
5. Proper YouTube Music tracks must retain normal artist and album metadata.

The reason this is tracked instead of refactored immediately is simple: the narrow fix is runtime-verified. Refactoring the metadata pipeline at the same time would increase the blast radius around playback, auth, recommendations, and radio.
