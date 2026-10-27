# iPad input, accounts, and resource packs

## Keyboard and mouse

The vendored winit patch maps UIKit hardware key presses and releases to physical HID keys,
while retaining layout-dependent characters for text fields. Cancellations, focus loss, and
responder loss release held input. Software text entry continues through `set_ime_allowed`.
The game uses the player's existing key bindings; menu navigation reuses the controller targets.

On iPad, `UIViewController.prefersPointerLocked` requests capture and GameController `GCMouse`
handlers provide relative movement, buttons, and scrolling. Captured indirect-pointer touches are
suppressed to prevent duplicate clicks. Pause, inventory, chat, resource-pack confirmation,
launcher presentation, and focus loss release capture. UIKit may decline a capture request.

Touch overlays follow the last input device used. Connecting hardware alone does not hide them;
touch input or disconnection of the last-used device restores them. Controllers remain supported.
The app minimum stays iOS 16. See [the vendored patch notes](../third_party/winit/HORIZON.md)
for provenance, platform API requirements, and removal criteria.

The app declares one programmatic UIWindowScene. Winit waits for the scene connection before
creating the game window and uses scene callbacks for activation, focus, and occlusion.

## Launcher and Microsoft sign-in

The bundled launcher is enabled. Quit Game at the title reopens it, and the Play and Servers
pages expose the account card. iPad launcher presentation supports landscape. The game skips
covered rendering while continuing to poll the launcher bridge.

Sign-in uses Microsoft's device authorization flow, followed by Xbox Live, XSTS, Minecraft
entitlement and profile checks. Secrets remain in Keychain and the game receives the session
in memory. Sign-out/cancellation invalidates pending credential writes; a failed Keychain write
must not publish a signed-in profile. Token renewal does not recursively call handover.

`HorizonMSAClientID` is intentionally empty in source control. A distributor must configure its
own personal-account public-client registration and obtain any required Minecraft API approval.
The iOS workflow fills the value from repository variable `HORIZON_MSA_CLIENT_ID`; a local build
can configure its own public client ID before packaging. No client secret belongs in this app.
See [the account design and setup guide](microsoft-account.md) and
[Microsoft's device authorization documentation](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code).
An Entra registration alone is not proof that Minecraft Services accepts the application.

## Server resource packs and item tooltips

Client item definitions are discovered recursively under `assets/<namespace>/items/`, preserving
all subfolders in the identifier. Duplicate leaf names in different subfolders remain distinct.
Resource reload discovers updated definitions. Invalid definitions are logged and use the
missing-model fallback; the item atlas always includes that fallback texture.

Component conditions accept the separate predicate type and value fields. The game evaluates
custom-data conditions against the stack using partial matching and SNBT boolean conversion.

The visible hover tooltip includes `minecraft:lore` after the name. Lore keeps component colors,
fonts, bold/italic settings, legacy formatting, and blank lines. Shared tooltip layout measures
and draws styled text, and `minecraft:tooltip_display` still controls visibility.

## Shader limitations

The iOS renderer uses wgpu with Metal. The legacy-format shader loader currently supplies
post-processing, not pack-driven terrain/entity/water geometry or shadow maps. Packs requiring
those inputs are rejected as incomplete; running their remaining final passes can hide the world.
A saved selection is separate from runtime compatibility. The Packs page reports loaded programs
and warnings; zero usable passes preserve normal rendering and FXAA.

This does not establish support for Complementary, Solas, arbitrary core-shader replacements,
or Horizon-native `.hshader` renderer hooks. Those need renderer implementation and visual tests.

## Verification

Automated coverage includes the input-mode transitions, keyboard mapping, scene manifest,
nested item discovery/reload, component condition decoding, missing-texture fallback, styled lore,
and incomplete shader pipeline rejection.

Before release, validate on physical devices:

- Held movement keys, text editing, non-US layouts, key release across menus, disconnection,
  backgrounding, and switching between hardware, touch, and controllers.
- Continuous mouse rotation, capture/release across every overlay, duplicate-click suppression,
  and fallback when UIKit declines pointer lock.
- Account sign-in, cancellation, sign-out during network activity, locked Keychain, refresh,
  entitlement failure, and a compatible authenticated server, using an approved application ID.
- Server pack acceptance, custom item models in inventory and world contexts, styled descriptions,
  hidden tooltip flags, and resource reload. Pack-specific effects require separate verification.
- Shader rejection/fallback and a compatible post-only pack, in every relevant dimension.

A successful build or codec test is not a physical input, visual parity, multiplayer compatibility,
GPU timing, thermal, or performance validation.
